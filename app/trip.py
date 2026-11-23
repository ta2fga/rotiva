"""Sürüş kaydedici (saf mantık, Qt yok).

* Sürüş yalnızca kullanıcı bitirince (stop) sona erer. Duruşlar (ışık, trafik) aynı sürüşte rölanti sayılır.
* Bağlantı koparsa kayıt duraklar (pause); veri yeniden gelince aynı sürüşe devam eder.
* Her ``autosave_s`` saniyede kısmi kayıt yazılır (unfinished.*); uygulama çökerse sonraki açılışta devam edilebilir.
* Hız > ``auto_start_kmh`` olunca otomatik başlatma ayarlıdır (varsayılan açık).
"""
from __future__ import annotations

import csv
import json
import os
import time
from collections import deque
from datetime import datetime
from typing import Optional

from . import coach, scoring
from .gear import GearEstimator
from .scoring import Sample

DEFAULTS = dict(auto_start=True, auto_start_kmh=5.0, autosave_s=30.0, min_samples=10, prebuffer=40,
                stall_s=10.0)
FIELDS = ["t", "rpm", "speed", "load", "coolant", "map", "maf", "gear"]
UNF = "unfinished"


def trips_dir(base: str = None) -> str:
    from .paths import data_dir
    d = base or os.path.join(data_dir(), "trips")
    os.makedirs(d, exist_ok=True)
    return d


def _fmt(v):
    return "" if v is None else (f"{v:.3f}" if isinstance(v, float) else str(v))


def _num(x):
    if x in ("", None):
        return None
    return float(x)


def read_samples(path: str) -> list:
    out = []
    with open(path, "r", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            s = Sample(t=float(r["t"]))
            for k in FIELDS[1:7]:
                setattr(s, k, _num(r.get(k)))
            g = _num(r.get("gear"))
            s.gear = int(g) if g else None
            out.append(s)
    return out


def _write_rows(f, samples, header):
    w = csv.writer(f)
    if header:
        w.writerow(FIELDS)
    for s in samples:
        w.writerow([_fmt(getattr(s, k)) for k in FIELDS])


def list_trips(base: str = None) -> list:
    """Kayıtlı sürüş özetleri (yeniden eskiye)."""
    d = trips_dir(base)
    out = []
    for fn in os.listdir(d):
        if fn.endswith(".json") and not fn.startswith(UNF):
            try:
                with open(os.path.join(d, fn), "r", encoding="utf-8") as f:
                    j = json.load(f)
                j["_json"] = os.path.join(d, fn)
                out.append(j)
            except (OSError, ValueError):
                pass
    return sorted(out, key=lambda j: j.get("start_ts", 0), reverse=True)


class TripRecorder:
    def __init__(self, cfg: dict = None, gear: GearEstimator = None, base: str = None, score_cfg: dict = None):
        self.cfg = dict(DEFAULTS, **(cfg or {}))
        self.gear = gear or GearEstimator()
        self.base = base
        self.score_cfg = score_cfg
        self.samples: list = []
        self.pre: deque = deque(maxlen=self.cfg["prebuffer"])
        self.active = False
        self.paused = False
        self.pauses: list = []            # [(t0, t1)]
        self._pause_t = 0.0
        self.start_ts = 0.0
        self.last_t = 0.0
        self._flushed = 0
        self._last_save = 0.0
        self.last_summary: Optional[dict] = None

    # --- yaşam döngüsü
    def start(self, t: float = None, with_prebuffer: bool = False):
        if self.active:
            return
        t = time.time() if t is None else t
        self.samples = list(self.pre) if with_prebuffer else []
        self.pre.clear()
        self.active, self.paused, self.pauses = True, False, []
        self.start_ts = self.samples[0].t if self.samples else t
        self.last_t = self._last_save = t
        self._flushed = 0
        self._remove_unfinished()
        self._flush(t)

    def pause(self, t: float = None):
        """Bağlantı koptu / veri akmıyor: kayıt durur, sürüş açık kalır."""
        if self.active and not self.paused:
            self.paused = True
            self._pause_t = self.last_t or t

    def _resume(self, t: float):
        if self.paused:
            self.paused = False
            self.pauses.append((self._pause_t, t))

    def feed(self, t: float, rpm=None, speed=None, load=None, coolant=None, map_kpa=None, maf=None,
             skew: float = 0.0):
        """Bir örnek işler (rpm+hız her döngüde). skew: devir ile hız okumaları arasındaki süre (vites için).
        Olay döner: 'started' | 'resumed' | None."""
        g = self.gear.update(t, rpm, speed, skew)
        if rpm is None and speed is None:
            return None
        s = Sample(t, rpm, speed, load, coolant, map_kpa, maf, g)
        ev = None
        if not self.active:
            self.pre.append(s)
            if self.cfg["auto_start"] and speed is not None and speed > self.cfg["auto_start_kmh"]:
                self.start(t, with_prebuffer=True)
                ev = "started"
            return ev
        if self.paused:
            self._resume(t)
            ev = "resumed"
        self.samples.append(s)
        self.last_t = t
        if t - self._last_save >= self.cfg["autosave_s"]:
            self._flush(t)
        return ev

    def stalled(self, now: float) -> bool:
        """Etkin sürüşte veri akışı durdu mu? (bağlantı kopması)"""
        return self.active and not self.paused and now - self.last_t > self.cfg["stall_s"]

    # --- kalıcılık
    def _paths(self, name):
        d = trips_dir(self.base)
        return os.path.join(d, name + ".csv"), os.path.join(d, name + ".json")

    def _remove_unfinished(self):
        for p in self._paths(UNF):
            try:
                os.remove(p)
            except OSError:
                pass

    def _flush(self, t: float):
        """Artımlı kayıt: yeni satırları unfinished.csv'ye ekler, meta'yı yeniler."""
        csvp, jp = self._paths(UNF)
        try:
            new = self.samples[self._flushed:]
            with open(csvp, "a", encoding="utf-8", newline="") as f:
                _write_rows(f, new, header=self._flushed == 0 and f.tell() == 0)
            self._flushed = len(self.samples)
            with open(jp, "w", encoding="utf-8") as f:
                json.dump(dict(start_ts=self.start_ts, pauses=self.pauses, saved_at=t,
                               gear=self.gear.to_dict()), f)
        except OSError:
            pass
        self._last_save = t

    def find_unfinished(self) -> Optional[dict]:
        """Önceki oturumdan kalan bitmemiş sürüş var mı? -> {'start_ts','samples','n'} veya None"""
        csvp, jp = self._paths(UNF)
        if not (os.path.exists(csvp) and os.path.exists(jp)):
            return None
        try:
            with open(jp, "r", encoding="utf-8") as f:
                meta = json.load(f)
            S = read_samples(csvp)
        except (OSError, ValueError, KeyError):
            return None
        if len(S) < self.cfg["min_samples"]:
            return None
        meta.update(samples=S, n=len(S))
        return meta

    def resume_unfinished(self, data: dict):
        """Yarım kalan sürüşü yeniden açar; bir sonraki veri ile devam eder (aradaki boşluk duraklama sayılır)."""
        self.samples = list(data["samples"])
        self.start_ts = data["start_ts"]
        self.pauses = [tuple(p) for p in data.get("pauses", [])]
        self.active, self.paused = True, True
        self.last_t = self._pause_t = self.samples[-1].t
        self._flushed = len(self.samples)
        self._last_save = time.time()

    def discard_unfinished(self):
        self._remove_unfinished()

    # --- bitiş
    def stop(self, t: float = None) -> Optional[dict]:
        """Sürüşü bitirir, CSV + JSON özetini yazar. Çok kısaysa kaydetmez (None)."""
        if not self.active:
            return None
        t = time.time() if t is None else t
        S = self.samples
        self.active = self.paused = False
        self.samples = []
        self._remove_unfinished()
        if len(S) < self.cfg["min_samples"] or max((s.speed or 0) for s in S) <= 0:
            return None
        res = scoring.analyze(S, self.score_cfg)
        name = datetime.fromtimestamp(S[0].t).strftime("%Y%m%d_%H%M%S")
        csvp, jp = self._paths(name)
        summary = dict(id=name, start_ts=S[0].t, end_ts=S[-1].t, pauses=self.pauses, csv=os.path.basename(csvp),
                       total=res["total"], subs=res["subs"], events=res["events"], metrics=res["metrics"],
                       short=res["short"], tips=coach.tips(res, self.score_cfg),
                       ratios=[round(r, 2) for r in self.gear.ratios], gear_counts=list(self.gear.counts))
        try:
            with open(csvp, "w", encoding="utf-8", newline="") as f:
                _write_rows(f, S, True)
            with open(jp, "w", encoding="utf-8") as f:
                json.dump(summary, f, ensure_ascii=False, indent=1)
        except OSError:
            return None
        self.last_summary = summary
        return summary

    def live_result(self) -> dict:
        return scoring.analyze(self.samples, self.score_cfg) if self.active else {}
