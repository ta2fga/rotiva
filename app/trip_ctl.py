"""Sürüş kaydedicisini canlı veriye bağlayan Qt denetleyicisi (poller -> TripRecorder -> arayüz)."""
from __future__ import annotations

import time

from PySide6.QtCore import QObject, QTimer, Signal

from .gear import GearEstimator
from .scoring import analyze
from .trip import TripRecorder

FAST = ["rpm", "speed"]                      # her döngü (>= 3 Hz hedefi)
SLOW = ["load", "coolant", "map", "maf"]     # her 4 döngüde bir
LIVE_WINDOW = 1800                           # canlı puan için son örnek sayısı


class TripController(QObject):
    updated = Signal()
    started = Signal()
    finished = Signal(object)        # özet sözlüğü veya None (çok kısa)
    status_changed = Signal(str)     # "" | "lost" | "offline"

    def __init__(self, state):
        super().__init__(state)
        self.state = state
        st = state.settings
        self.gear = GearEstimator.from_dict(st.get("gear_model"))
        self.rec = TripRecorder(st.get("trip", {}), self.gear)
        self.latest: dict = {}
        self.latest_ts: dict = {}
        self.live = dict(speed=None, rpm=None, gear=None, gear_label="—", score=None, subs={})
        self.status = ""
        self.retry_s = 3.0
        self._last_live = 0.0
        self._last_persist = time.time()
        state.value_ts.connect(self._on_value)
        state.connection_changed.connect(self._on_conn)
        state.connection_lost.connect(self._on_lost)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.timer.start(1000)
        self.retry = QTimer(self)
        self.retry.timeout.connect(self._retry)

    # --- ayarlar
    @property
    def auto_start(self) -> bool:
        return bool(self.rec.cfg["auto_start"])

    def set_auto_start(self, on: bool):
        self.rec.cfg["auto_start"] = bool(on)
        cfg = dict(self.state.settings.get("trip", {}))
        cfg["auto_start"] = bool(on)
        self.state.settings.put("trip", cfg)

    # --- sürüş
    @property
    def active(self) -> bool:
        return self.rec.active

    def start(self):
        if not self.rec.active:
            self.rec.start(time.time())
            self.started.emit()
            self.updated.emit()

    def finish(self):
        """Yalnızca kullanıcı çağırır: sürüşü bitirir ve kaydeder."""
        summary = self.rec.stop(time.time())
        self._persist_gear(force=True)
        self._set_status("" if self.state.connected else "offline")
        self.live["score"], self.live["subs"] = None, {}
        self.finished.emit(summary)
        self.updated.emit()
        return summary

    def unfinished(self):
        return None if self.rec.active else self.rec.find_unfinished()

    def resume(self, data):
        self.rec.resume_unfinished(data)
        self._set_status("offline" if not self.state.connected else "")
        self.started.emit()
        self.updated.emit()

    def discard_unfinished(self):
        self.rec.discard_unfinished()

    def shutdown(self):
        """Uygulama kapanırken sürüş AÇIK kalır (kısmi kayıt diskte); sonraki açılışta devam edilebilir."""
        self._persist_gear(force=True)
        if self.rec.active:
            self.rec._flush(time.time())

    # --- olaylar
    def _set_status(self, s: str):
        if s != self.status:
            self.status = s
            self.status_changed.emit(s)

    def _on_conn(self, ok: bool):
        if ok:
            self.latest.clear()
            self.latest_ts.clear()
            self.state.set_poll("trip", FAST, SLOW, 4)
            self.retry.stop()
            self._set_status("")
        else:
            self.state.set_poll("trip", [])
            if self.rec.active:
                self.rec.pause(time.time())
            self.retry.stop()
            self._set_status("offline" if self.rec.active else "")

    def _on_lost(self):
        if self.rec.active:
            self.rec.pause(time.time())
        self._set_status("lost" if self.state.reconnect_factory else "offline")
        if self.state.reconnect_factory:
            self.retry.start(int(self.retry_s * 1000))
            QTimer.singleShot(300, self._retry)

    def _retry(self):
        if self.state.link_lost and self.state.reconnect_factory and not self.state.reconnecting:
            self.state.reconnect()
        elif not self.state.link_lost:
            self.retry.stop()

    def _on_value(self, k: str, v, ts: float):
        if v is None:
            return                                 # tek NO DATA son iyi değeri silmez
        self.latest[k] = v
        self.latest_ts[k] = ts
        if k != "speed":
            return
        rpm = self.latest.get("rpm")
        if rpm is None or ts - self.latest_ts.get("rpm", 0) > 5.0:
            return
        ev = self.rec.feed(ts, rpm, v, self.latest.get("load"), self.latest.get("coolant"),
                           self.latest.get("map"), self.latest.get("maf"),
                           skew=ts - self.latest_ts.get("rpm", ts))
        self.live.update(speed=v, rpm=rpm, gear=self.gear.gear, gear_label=self.gear.label(ts))
        if self.status in ("lost", "offline") and self.state.connected:
            self._set_status("")
        if ev == "started":
            self.started.emit()
        self.updated.emit()

    def _tick(self):
        now = time.time()
        if self.rec.stalled(now) and self.state.connected:
            self.state.mark_lost()                 # veri akışı kesildi: bağlantı kopmuş say
        if self.rec.active and not self.rec.paused and now - self._last_live >= 3.0:
            self._last_live = now
            r = analyze(self.rec.samples[-LIVE_WINDOW:], self.rec.score_cfg)
            self.live["score"], self.live["subs"] = r.get("total"), r.get("subs", {})
        self._persist_gear()
        if self.rec.active:
            self.updated.emit()

    def _persist_gear(self, force: bool = False):
        now = time.time()
        if self.gear.dirty and (force or now - self._last_persist > 60):
            self.state.settings.put("gear_model", self.gear.to_dict())
            self.gear.dirty = False
            self._last_persist = now
