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
            keys = self.state.poll_keys()
            if not keys:
                time.sleep(0.2)
                continue
            for k in keys:
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
            cycle += 1
        self.stopped.emit("")


class AppState(QObject):
    connection_changed = Signal(bool)
    log = Signal(str)
    value = Signal(str, object)
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
        self._lock = threading.Lock()
        self.rebuild_catalog()

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
        self._stop_poller()
        self.mfr_reachable = None
        elm, self.elm = self.elm, None
        if elm:
            run_bg(elm.disconnect)
        self.rebuild_catalog()
        self.connection_changed.emit(False)

    # --- poller
    def set_poll(self, owner: str, keys):
        with self._lock:
            if keys:
                self._owners[owner] = list(keys)
            else:
                self._owners.pop(owner, None)

    def poll_keys(self) -> list:
        with self._lock:
            out = []
            for ks in self._owners.values():
                out += [k for k in ks if k not in out]
            return out

    def _start_poller(self):
        self._stop_poller()
        self.poller = Poller(self)
        self.poller.value.connect(self._on_value, Qt.QueuedConnection)
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
        if msg and self.connected:
            self.log.emit(f"! Okuma durdu: {msg}")

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
