"""Vites tahmini: oran r = hız (km/s) / (devir/1000). Kullanıcı vites bilgisini ECU'dan alamadığı için
oran kümelerinden (çevrimiçi 1-B k-means, 5 küme) tahmin edilir. Başlangıç değerleri TAHMİNDİR; stabil
örneklerle araca göre otomatik düzeltilir."""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Optional

# C-Elysée 1.6 HDi 5 ileri için km/s per 1000 d/dk (TAHMİN; kullanıldıkça öğrenilir)
DEFAULT_RATIOS = [8.0, 15.0, 22.5, 30.0, 38.0]

CFG = dict(
    window_s=1.0,         # kararlılık penceresi
    min_rpm=900, min_speed=8.0,
    max_dspeed=6.0,       # pencerede hız değişimi (km/s)
    max_drpm=600,         # pencerede devir değişimi
    max_spread=0.03,      # oran dağılımı (+ 1/hız payı)
    tol=0.07,             # sınıflama toleransı (oransal)
    idle_rpm_max=900,     # bunun altında hareket ederken: kavrama/rölanti -> None
    min_moving=3.0,
    assign_radius=0.20,   # örnek kümeye bu uzaklıktan yakınsa öğrenmeye katılır
    count_cap=300, prior=5, min_sep=0.08, learned_count=40,
    debounce=2,           # vites değişimi için ardışık örnek sayısı
    gap_reset_s=8.0,      # vites arası en fazla boşluk (kavrama/boşta)
)


@dataclass
class Shift:
    t: float
    from_gear: int
    to_gear: int
    rpm: float            # eski viteste son tepe devri

    @property
    def up(self) -> bool:
        return self.to_gear > self.from_gear


class ShiftTracker:
    """Vites dizisinden değişimleri ve değişim devrini çıkarır."""

    def __init__(self, gap_reset_s: float = CFG["gap_reset_s"]):
        self.gap = gap_reset_s
        self.last: Optional[int] = None
        self.last_t = 0.0
        self.recent: deque = deque(maxlen=12)
        self.shifts: list = []

    def update(self, t: float, rpm, gear: Optional[int]) -> Optional[Shift]:
        if gear is None:
            if self.last is not None and t - self.last_t > self.gap:
                self.last = None
                self.recent.clear()
            return None
        out = None
        if self.last is not None and gear != self.last and t - self.last_t <= self.gap:
            pk = [r for tt, r in self.recent if self.last_t - tt <= 1.5 and r is not None]
            out = Shift(t, self.last, gear, max(pk) if pk else (rpm or 0))
            self.shifts.append(out)
        if gear != self.last:
            self.recent.clear()
        self.recent.append((t, rpm))
        self.last, self.last_t = gear, t
        return out


def detect_shifts(seq) -> list:
    """seq: (t, rpm, gear) dizisi -> [Shift]"""
    tr = ShiftTracker()
    for t, rpm, g in seq:
        tr.update(t, rpm, g)
    return tr.shifts


class GearEstimator:
    def __init__(self, ratios=None, counts=None, cfg: dict = None):
        self.cfg = dict(CFG, **(cfg or {}))
        self.ratios = list(ratios) if ratios and len(ratios) == 5 else list(DEFAULT_RATIOS)
        self.counts = list(counts) if counts and len(counts) == 5 else [0] * 5
        self.win: deque = deque(maxlen=64)
        self.gear: Optional[int] = None
        self._cand = None
        self._cand_n = 0
        self.tracker = ShiftTracker(self.cfg["gap_reset_s"])
        self.dirty = False

    # --- kalıcılık
    def to_dict(self) -> dict:
        return {"ratios": [round(r, 3) for r in self.ratios], "counts": list(self.counts)}

    @classmethod
    def from_dict(cls, d: Optional[dict], cfg: dict = None) -> "GearEstimator":
        d = d or {}
        return cls(d.get("ratios"), d.get("counts"), cfg)

    def reset(self):
        self.ratios, self.counts = list(DEFAULT_RATIOS), [0] * 5
        self.dirty = True

    def learned(self, gear: int) -> bool:
        return self.counts[gear - 1] >= self.cfg["learned_count"]

    @property
    def shifts(self) -> list:
        return self.tracker.shifts

    # --- sınıflama
    def _nearest(self, r: float):
        i = min(range(5), key=lambda k: abs(r - self.ratios[k]) / self.ratios[k])
        return i, abs(r - self.ratios[i]) / self.ratios[i]

    def classify(self, rpm, speed) -> Optional[int]:
        c = self.cfg
        if rpm is None or speed is None or speed < c["min_moving"] or rpm <= 0:
            return None
        if rpm < c["idle_rpm_max"]:
            return None
        i, err = self._nearest(speed / (rpm / 1000.0))
        return i + 1 if err <= c["tol"] else None

    # --- öğrenme
    def _learn(self, t: float):
        c = self.cfg
        w = [s for s in self.win if t - s[0] <= c["window_s"]]
        if len(w) < 3 or w[-1][0] - w[0][0] < c["window_s"] * 0.7:
            return
        rp = [s[1] for s in w]
        sp = [s[2] for s in w]
        mr, ms = sum(rp) / len(rp), sum(sp) / len(sp)
        if mr <= c["min_rpm"] or ms <= c["min_speed"]:
            return
        if max(sp) - min(sp) > c["max_dspeed"] or max(rp) - min(rp) > c["max_drpm"]:
            return
        rs = [s[2] / (s[1] / 1000.0) for s in w if s[1] > 0]
        if not rs or (max(rs) - min(rs)) / (sum(rs) / len(rs)) > c["max_spread"] + 1.0 / ms:
            return
        self._update_cluster(ms / (mr / 1000.0))

    def _update_cluster(self, r: float):
        c = self.cfg
        i, err = self._nearest(r)
        if err > c["assign_radius"]:
            return
        self.counts[i] += 1
        n = min(self.counts[i] + c["prior"], c["count_cap"])
        new = self.ratios[i] + (r - self.ratios[i]) / n
        lo = self.ratios[i - 1] * (1 + c["min_sep"]) if i > 0 else 0
        hi = self.ratios[i + 1] / (1 + c["min_sep"]) if i < 4 else 1e9
        if lo < new < hi:
            self.ratios[i] = new
            self.dirty = True

    def fit(self, pairs) -> list:
        """Toplu öğrenme: pairs=[(t, rpm, speed)] sırayla beslenir; ratios döner."""
        for t, rpm, spd in pairs:
            self.update(t, rpm, spd)
        return self.ratios

    # --- ana giriş
    def update(self, t: float, rpm, speed) -> Optional[int]:
        if rpm is None or speed is None:
            return self.gear
        self.win.append((t, rpm, speed))
        self._learn(t)
        raw = self.classify(rpm, speed)
        if raw == self.gear:
            self._cand, self._cand_n = None, 0
        else:
            if raw == self._cand:
                self._cand_n += 1
            else:
                self._cand, self._cand_n = raw, 1
            if self._cand_n >= self.cfg["debounce"]:
                self.gear, self._cand, self._cand_n = raw, None, 0
        self.tracker.update(t, rpm, self.gear)
        return self.gear
