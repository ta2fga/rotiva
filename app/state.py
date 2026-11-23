"""Uygulama durumu: bağlantı, canlı veri okuyucu (poller), katalog, ayarlar."""
from __future__ import annotations

import threading
import time
from typing import Optional

from PySide6.QtCore import QObject, QThread, Signal, Qt

from .datafile import DataStore
from .elm327 import ELM327, ElmError, NoData
from .logger import DataLogger
from .pids import build_catalog, BATTERY_KEY, Item
from .safe_eval import FormulaError
from .settings import Settings


class Task(QObject):
    """Arka plan iş parçacığında fn() çalıştırır; sonucu ana iş parçacığında iletir."""
    done = Signal(object)
    failed = Signal(object)
    _live: set = set()

    def __init__(self, fn, on_ok=None, on_err=None):
        super().__init__()
        self.fn = fn
        if on_ok:
            self.done.connect(on_ok, Qt.QueuedConnection)
        if on_err:
            self.failed.connect(on_err, Qt.QueuedConnection)
        self.done.connect(self._cleanup, Qt.QueuedConnection)
        self.failed.connect(self._cleanup, Qt.QueuedConnection)
        Task._live.add(self)
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        try:
            self.done.emit(self.fn())
        except Exception as e:  # noqa: BLE001
            self.failed.emit(e)

    def _cleanup(self, *_):
        Task._live.discard(self)


def run_bg(fn, on_ok=None, on_err=None) -> Task:
    return Task(fn, on_ok, on_err)


class Poller(QThread):
    value = Signal(str, object)
    value_ts = Signal(str, object, float)   # (anahtar, değer, okuma zamanı)
    stopped = Signal(str)

    def __init__(self, state: "AppState"):
        super().__init__()
        self.state = state
        self._stop = False

    def stop(self):
        self._stop = True

    def run(self):
        cycle = 0
        elm = self.state.elm
        while not self._stop and elm and elm.connected:
            if not self.state.poll_keys():
                time.sleep(0.2)
                continue
            plan = self.state.poll_plan(cycle)
            if not plan:
                time.sleep(0.01)
            for k in plan:
                if self._stop:
                    break
                item = self.state.by_key.get(k)
                if item is None or not item.supported or (item.source == "psa" and cycle % 3):
                    continue
                try:
                    v = self.state.read_item(item)
                except (ElmError, FormulaError) as e:
                    if "kapat" in str(e):
                        self.stopped.emit(str(e))
                        return
                    v = None
                except Exception as e:  # noqa: BLE001
                    self.stopped.emit(str(e))
                    return
                self.state.live[k] = v
                self.value.emit(k, v)
                self.value_ts.emit(k, v, time.time())
            cycle += 1
        self.stopped.emit("" if self._stop else "Bağlantı kesildi")


class AppState(QObject):
    connection_changed = Signal(bool)
    log = Signal(str)
    value = Signal(str, object)
    value_ts = Signal(str, object, float)
    connection_lost = Signal()
    catalog_changed = Signal()
    dashboard_changed = Signal()
    mfr_changed = Signal()

    def __init__(self):
        super().__init__()
        self.settings = Settings()
        self.store = DataStore()
        self.logger = DataLogger()
        self.elm: Optional[ELM327] = None
        self.demo = False
        self.info: dict = {}
        self.live: dict = {}
        self.dtcs: list = []            # (kod, tür, ecu, açıklama)
        self.vehicle: dict = {}
        self.poller: Optional[Poller] = None
        self.mfr_reachable = None      # None: bilinmiyor, True/False
        self._owners: dict = {}
        self._slow: dict = {}
        self.reconnect_factory = None   # () -> (ELM327, proto, demo); bağlantı sayfası ayarlar
        self.link_lost = False
        self.reconnecting = False
        self._lock = threading.Lock()
        self.rebuild_catalog()
        from .trip_ctl import TripController
        self.trip = TripController(self)

    # --- katalog
    def rebuild_catalog(self):
        sup = self.elm.supported if (self.elm and self.elm.supported) else None
        self.catalog = build_catalog(self.store, None)
        for i in self.catalog:
            if sup is not None and i.source == "std" and i.ref is not None:
                i.supported = i.ref.pid in sup
        self.available = [i for i in self.catalog if i.supported]
        self.by_key = {i.key: i for i in self.catalog}

    def reload_store(self):
        self.store = DataStore()
        self.rebuild_catalog()
        self.catalog_changed.emit()

    # --- bağlantı
    @property
    def connected(self) -> bool:
        return bool(self.elm and self.elm.connected)

    def set_elm(self, elm: ELM327, demo: bool):
        self.elm, self.demo = elm, demo
        self.link_lost = False
        self.live.clear()
        self.rebuild_catalog()
        self.mfr_reachable = None
        self.connection_changed.emit(True)
        self.catalog_changed.emit()
        self._start_poller()
        from .scanner import probe_manufacturer

        def done(ok):
            self.mfr_reachable = bool(ok)
            self.mfr_changed.emit()
        run_bg(lambda: probe_manufacturer(elm, self.store), done, lambda e: done(False))

    def disconnect(self):
        """Kullanıcı bağlantıyı keser (otomatik yeniden bağlanma yapılmaz)."""
        self.reconnect_factory = None
        self.link_lost = False
        self._stop_poller()
        self.mfr_reachable = None
        elm, self.elm = self.elm, None
        if elm:
            run_bg(elm.disconnect)
        self.rebuild_catalog()
        self.connection_changed.emit(False)

    # --- poller
    def set_poll(self, owner: str, keys, slow=None, slow_every: int = 4):
        """keys her döngüde, slow anahtarları her slow_every döngüde bir okunur (yavaş BLE adaptörler için)."""
        with self._lock:
            if keys or slow:
                self._owners[owner] = (list(keys or []), list(slow or []), max(1, slow_every))
            else:
                self._owners.pop(owner, None)

    def poll_keys(self) -> list:
        with self._lock:
            out = []
            for ks, sl, _ in self._owners.values():
                out += [k for k in ks + sl if k not in out]
            return out

    def poll_plan(self, cycle: int) -> list:
        """Bu döngüde okunacak anahtarlar (hızlılar önce)."""
        with self._lock:
            fast, slow = [], []
            for ks, sl, ev in self._owners.values():
                fast += [k for k in ks if k not in fast]
                if cycle % ev == 0:
                    slow += [k for k in sl if k not in slow]
            return fast + [k for k in slow if k not in fast]

    # --- bağlantı kopması / otomatik yeniden bağlanma
    def mark_lost(self):
        if self.elm is not None and not self.link_lost:
            self.link_lost = True
            if self.poller:
                self.poller.stop()      # veri akışı kesildi; yeni bağlantıda yeniden başlatılır
            self.connection_lost.emit()

    def reconnect(self, on_done=None):
        """Son bağlantı ayarlarıyla yeniden bağlanır (arka planda)."""
        fac = self.reconnect_factory
        if not fac or self.reconnecting:
            return False
        try:
            elm, proto, demo = fac()      # taşıma nesnesi ana iş parçacığında (arayüz okunur) oluşturulur
        except Exception:  # noqa: BLE001
            return False
        self.reconnecting = True

        def work():
            try:
                elm.connect(proto)
            except Exception:
                try:
                    elm.disconnect()
                except Exception:  # noqa: BLE001
                    pass
                raise
            return elm, demo

        def ok(r):
            if self.reconnect_factory is None:       # bu sırada kullanıcı kesti
                self.reconnecting = False
                run_bg(r[0].disconnect)
                return
            old = self.elm
            self.set_elm(*r)
            self.reconnecting = False
            if old is not None and old is not r[0]:
                run_bg(old.disconnect)
            if on_done:
                on_done(True)

        def err(_e):
            self.reconnecting = False
            if on_done:
                on_done(False)
        run_bg(work, ok, err)
        return True

    def _start_poller(self):
        self._stop_poller()
        self.poller = Poller(self)
        self.poller.value.connect(self._on_value, Qt.QueuedConnection)
        self.poller.value_ts.connect(self.value_ts, Qt.QueuedConnection)
        self.poller.stopped.connect(self._on_poller_stopped, Qt.QueuedConnection)
        self.poller.start()

    def _stop_poller(self):
        if self.poller:
            self.poller.stop()
            self.poller.wait(3000)
            self.poller = None

    def _on_value(self, k, v):
        self.value.emit(k, v)

    def _on_poller_stopped(self, msg):
        if msg:
            self.log.emit(f"! Okuma durdu: {msg}")
            self.mark_lost()

    def read_item(self, item: Item):
        """Tek değeri okur (poller iş parçacığında). Okunamazsa None."""
        elm = self.elm
        if item.key == BATTERY_KEY:
            return elm.voltage()
        if item.source == "std":
            return elm.read_pid(item.ref)
        d = item.ref
        ecu = self.store.ecu(d["ecu"])
        try:
            data = elm.read_did(ecu, d["did"], d["service"])
        except NoData:
            return None
        except ElmError as e:
            if "kapat" in str(e):
                raise
            return None
        return d["fn"](data)
