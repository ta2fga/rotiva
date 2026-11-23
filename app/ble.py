"""Bluetooth LE ELM327 taşıması (bleak). Kendi asyncio iş parçacığında çalışır; Transport arayüzünü sağlar."""
from __future__ import annotations

import asyncio
import queue
import threading
from typing import Optional

from .elm327 import Transport, ElmError

OBD_HINTS = ("obd", "elm", "v-link", "vlink", "ios-vlink", "vgate", "android-vlink", "icar", "viecar")
# (servis, bildirim/notify, yazma/write)
KNOWN_UART = [("fff0", "fff1", "fff2"), ("ffe0", "ffe1", "ffe1"), ("18f0", "2af0", "2af1")]
_BASE = "-0000-1000-8000-00805f9b34fb"


def is_obd_name(name: Optional[str]) -> bool:
    n = (name or "").lower()
    return any(h in n for h in OBD_HINTS)


def _u16(uuid: str) -> str:
    u = uuid.lower()
    return u[4:8] if u.endswith(_BASE) and u.startswith("0000") else u


def pick_characteristics(services) -> tuple:
    """(notify_char, write_char). Bilinen UART'lar önce, yoksa ilk notify + ilk write."""
    by_svc = {_u16(s.uuid): s for s in services}
    for sv, rx, tx in KNOWN_UART:
        s = by_svc.get(sv)
        if not s:
            continue
        ch = {_u16(c.uuid): c for c in s.characteristics}
        if rx in ch and tx in ch:
            return ch[rx], ch[tx]
    notify = write = None
    for s in services:
        if _u16(s.uuid) in ("1800", "1801"):
            continue
        for c in s.characteristics:
            p = set(c.properties)
            if notify is None and p & {"notify", "indicate"}:
                notify = c
            if write is None and p & {"write", "write-without-response"}:
                write = c
    if notify is None or write is None:
        raise ElmError("BLE cihazında uygun UART karakteristiği bulunamadı")
    return notify, write


class BleTransport(Transport):
    def __init__(self, address: str, connect_timeout: float = 15.0):
        self.address = address
        self.connect_timeout = connect_timeout
        self.rxq: queue.Queue = queue.Queue()
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None
        self.client = None
        self.write_char = None
        self.chunk = 20
        self.info = ""

    def _run(self, coro, timeout):
        fut = asyncio.run_coroutine_threadsafe(coro, self.loop)
        try:
            return fut.result(timeout)
        except Exception as e:
            fut.cancel()
            raise ElmError(f"BLE hatası: {e}") from e

    def open(self):
        try:
            from bleak import BleakClient
        except ImportError as e:
            raise ElmError("bleak yüklü değil (pip install bleak)") from e
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True, name="ble-loop")
        self.thread.start()

        async def go():
            self.client = BleakClient(self.address, timeout=self.connect_timeout)
            await self.client.connect()
            notify, write = pick_characteristics(self.client.services)
            self.write_char = write
            self.info = f"servis {notify.service_uuid[4:8]} notify {notify.uuid[4:8]} write {write.uuid[4:8]}"
            try:
                self.chunk = max(20, int(getattr(self.client, "mtu_size", 23)) - 3)
            except Exception:
                self.chunk = 20
            await self.client.start_notify(notify, lambda _c, data: self.rxq.put(bytes(data)))

        try:
            self._run(go(), self.connect_timeout + 10)
        except Exception:
            self.close()
            raise

    def close(self):
        if self.loop:
            if self.client is not None:
                try:
                    self._run(self.client.disconnect(), 5)
                except Exception:
                    pass
            self.loop.call_soon_threadsafe(self.loop.stop)
            if self.thread:
                self.thread.join(timeout=2)
            self.loop = self.thread = self.client = None

    def write(self, data: bytes):
        wc = self.write_char
        # Birçok ELM327 BLE adaptörü (FFF2) yanıtsız yazmayı yok sayar: mümkünse response=True
        resp = "write" in wc.properties or "write-without-response" not in wc.properties

        async def go():
            for i in range(0, len(data), self.chunk):
                await self.client.write_gatt_char(wc, data[i:i + self.chunk], response=resp)
        self._run(go(), 10)

    def read(self, timeout: float) -> bytes:
        try:
            out = self.rxq.get(timeout=timeout)
        except queue.Empty:
            return b""
        while True:
            try:
                out += self.rxq.get_nowait()
            except queue.Empty:
                return out


def scan(timeout: float = 6.0) -> list:
    """Yakındaki BLE cihazları: [(ad, adres, rssi)] (OBD benzeri adlar önce)."""
    from bleak import BleakScanner

    async def go():
        found = await BleakScanner.discover(timeout=timeout, return_adv=True)
        return [(d.name or adv.local_name or "(adsız)", d.address, adv.rssi) for d, adv in found.values()]
    try:
        res = asyncio.run(go())
    except Exception as e:
        raise ElmError(f"BLE tarama hatası: {e}") from e
    return sorted(res, key=lambda r: (not is_obd_name(r[0]), -(r[2] or -999)))
