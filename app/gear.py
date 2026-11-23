"""Vites tahmini (Android core/Gear.kt ile aynı algoritma).

Oran r = hız (km/s) / (devir/1000). Devir ve hız ECU'dan ayrı isteklerle okunduğu için örnekler zamanca
hizalanır (PairAligner: hız anına devir enterpolasyonu). Hizalı çiftler en yakın vites merkezine eşlenir;
merkezler kararlı sürüşte çevrimiçi kümeleme ile araca göre düzeltilir (varsayılanın ±%bound içinde).
Sonuç histerezisle yayınlanır: yeni vites ~0,5 sn ve en az 2 örnek sürmeli; tutarsız (debriyaj/geçiş)
örneklerde son vites kısa süre korunur. "Boşta" (NEUTRAL) yalnızca duruyorken ya da hareket hâlinde rölantide
oran uyuşmuyorsa verilir; veri yoksa/eskiyse sonuç bilinmiyor (None)."""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Optional

# 2017 C-Elysée 1.6 HDi 92 hp, BE4 5 ileri: km/s per 1000 d/dk (yaklaşık; kullanıldıkça öğrenilir)
DEFAULT_RATIOS = [7.5, 14.0, 21.5, 29.0, 37.0]
MODEL_VERSION = 2
NEUTRAL = 0

CFG = dict(
    # eşleme
    tol=0.10,             # merkeze göre oransal tolerans
    tol_speed_q=0.5,      # + bu/hız (OBD hızı tam sayı km/s)
    min_rpm=450,          # altı: motor çalışmıyor -> bilinmiyor
    idle_rpm_max=1050,    # rölanti bandı
    stop_speed=3.0,       # altı: duruyor -> boşta
    coast_speed=5.0,      # üstünde rölanti + uyumsuz oran -> boşta/debriyaj
    # zaman hizalama
    max_skew=0.35, max_bracket=1.5,
    # histerezis
    hold_s=0.5, hold_n=2, unknown_hold_s=2.0, stale_s=3.0,
    # öğrenme
    window_s=1.5, min_learn_rpm=1100, min_learn_speed=8.0,
    max_dspeed=8.0, max_drpm=1000, max_spread=0.03,  # hizalı örneklerde ivmelenme de öğretir; ölçüt oran dağılımı
    assign_radius=0.10, bound=0.18,
    count_cap=300, prior=5, min_sep=0.12, learned_count=40,
    gap_reset_s=8.0,      # vites arası en fazla boşluk (kavrama/boşta)
)


def gear_label(state) -> str:
    return "—" if state is None else "N" if state == NEUTRAL else str(state)


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


class PairAligner:
    """Ayrı zamanlarda okunan devir ve hız örneklerinden zamanca hizalı (t, devir, hız) çiftleri üretir."""

    def __init__(self, max_skew: float = 0.35, max_bracket: float = 1.5):
        self.max_skew, self.max_bracket = max_skew, max_bracket
        self.rpm: deque = deque(maxlen=16)
        self.pend: deque = deque(maxlen=16)
        self.latest = 0.0

    def clear(self):
        self.rpm.clear()
        self.pend.clear()
        self.latest = 0.0

    def push_rpm(self, t: float, v: float) -> list:
        self.rpm.append((t, v))
        self.latest = max(self.latest, t)
        return self._resolve()

    def push_speed(self, t: float, v: float) -> list:
        self.pend.append((t, v))
        self.latest = max(self.latest, t)
        return self._resolve()

    def _nearest(self, t):
        if not self.rpm:
            return None
        n = min(self.rpm, key=lambda s: abs(s[0] - t))
        return n if abs(n[0] - t) <= self.max_skew else None

    def _resolve(self) -> list:
        out = []
        while self.pend:
            ts, sp = self.pend[0]
            before = next((s for s in reversed(self.rpm) if s[0] <= ts), None)
            after = next((s for s in self.rpm if s[0] >= ts), None)
            if before is not None and after is not None:
                self.pend.popleft()
                span = after[0] - before[0]
                if span <= 1e-9:
                    out.append((ts, before[1], sp))
                elif span <= self.max_bracket:
                    f = (ts - before[0]) / span
                    out.append((ts, before[1] + (after[1] - before[1]) * f, sp))
                else:
                    n = self._nearest(ts)
                    if n:
                        out.append((ts, n[1], sp))
            elif after is None:
                if self.latest - ts <= self.max_bracket:
                    break                      # sonraki devir örneğini bekle
                self.pend.popleft()
                n = self._nearest(ts)
                if n:
                    out.append((ts, n[1], sp))
            else:
                self.pend.popleft()
                n = self._nearest(ts)
                if n:
                    out.append((ts, n[1], sp))
        return out


class GearEstimator:
    def __init__(self, ratios=None, counts=None, cfg: dict = None):
        self.cfg = dict(CFG, **(cfg or {}))
        ok = self._valid(ratios)
        self.ratios = [float(r) for r in ratios] if ok else list(DEFAULT_RATIOS)
        self.counts = list(counts) if ok and counts and len(counts) == 5 else [0] * 5
        self.win: deque = deque(maxlen=64)
        self.aligner = PairAligner(self.cfg["max_skew"], self.cfg["max_bracket"])
        self.state: Optional[int] = None       # 1..5, NEUTRAL, None (bilinmiyor)
        self._cand = None
        self._cand_n = 0
        self._cand_t0 = 0.0
        self._last_consistent = -1e9
        self.last_pair_t = -1e9
        self.tracker = ShiftTracker(self.cfg["gap_reset_s"])
        self.dirty = False

    def _valid(self, r) -> bool:
        if not r or len(r) != 5:
            return False
        for i in range(5):
            if abs(r[i] - DEFAULT_RATIOS[i]) / DEFAULT_RATIOS[i] > self.cfg["bound"] + 1e-9:
                return False
            if i and r[i] <= r[i - 1]:
                return False
        return True

    # --- durum
    @property
    def gear(self) -> Optional[int]:
        """Kayıt/puanlama için vites (boşta ve bilinmiyor = None)."""
        return self.state if self.state and self.state >= 1 else None

    @property
    def neutral(self) -> bool:
        return self.state == NEUTRAL

    def state_at(self, now: float) -> Optional[int]:
        return None if now - self.last_pair_t > self.cfg["stale_s"] else self.state

    def gear_at(self, now: float) -> Optional[int]:
        s = self.state_at(now)
        return s if s and s >= 1 else None

    def label(self, now: float) -> str:
        return gear_label(self.state_at(now))

    # --- kalıcılık
    def to_dict(self) -> dict:
        return {"v": MODEL_VERSION, "ratios": [round(r, 3) for r in self.ratios], "counts": list(self.counts)}

    @classmethod
    def from_dict(cls, d: Optional[dict], cfg: dict = None) -> "GearEstimator":
        d = d or {}
        if d.get("v", 1) != MODEL_VERSION:          # eski (hizasız) öğrenme atılır
            return cls(cfg=cfg)
        return cls(d.get("ratios"), d.get("counts"), cfg)

    def reset(self):
        self.ratios, self.counts = list(DEFAULT_RATIOS), [0] * 5
        self.win.clear()
        self.aligner.clear()
        self.state, self._cand, self._cand_n = None, None, 0
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
        """Tek hizalı örnek: 1..5, NEUTRAL ya da None (tutarsız/bilinmiyor)."""
        c = self.cfg
        if rpm is None or speed is None or rpm < c["min_rpm"]:
            return None
        if speed < c["stop_speed"]:
            return NEUTRAL
        i, err = self._nearest(speed / (rpm / 1000.0))
        if err <= c["tol"] + c["tol_speed_q"] / speed:
            return i + 1
        if rpm <= c["idle_rpm_max"] and speed >= c["coast_speed"]:
            return NEUTRAL
        return None

    # --- öğrenme
    def _learn(self, t: float):
        c = self.cfg
        while self.win and t - self.win[0][0] > c["window_s"]:
            self.win.popleft()
        w = list(self.win)
        if len(w) < 3 or w[-1][0] - w[0][0] < c["window_s"] * 0.6:
            return
        rp = [s[1] for s in w]
        sp = [s[2] for s in w]
        mr, ms = sum(rp) / len(rp), sum(sp) / len(sp)
        if mr < c["min_learn_rpm"] or ms < c["min_learn_speed"]:
            return
        if max(sp) - min(sp) > c["max_dspeed"] or max(rp) - min(rp) > c["max_drpm"]:
            return
        rs = [s[2] / (s[1] / 1000.0) for s in w]
        if (max(rs) - min(rs)) / (sum(rs) / len(rs)) > c["max_spread"] + 1.0 / ms:
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
        lo = max(self.ratios[i - 1] * (1 + c["min_sep"]) if i > 0 else 0, DEFAULT_RATIOS[i] * (1 - c["bound"]))
        hi = min(self.ratios[i + 1] / (1 + c["min_sep"]) if i < 4 else 1e9, DEFAULT_RATIOS[i] * (1 + c["bound"]))
        if lo < new < hi:
            self.ratios[i] = new
            self.dirty = True

    def fit(self, pairs) -> list:
        """Toplu öğrenme: pairs=[(t, rpm, speed)] sırayla beslenir; ratios döner."""
        for t, rpm, spd in pairs:
            self.update(t, rpm, spd)
        return self.ratios

    # --- girişler
    def push_rpm(self, t: float, rpm: float) -> Optional[int]:
        for pt, r, s in self.aligner.push_rpm(t, rpm):
            self._step(pt, r, s)
        return self.gear

    def push_speed(self, t: float, speed: float) -> Optional[int]:
        for pt, r, s in self.aligner.push_speed(t, speed):
            self._step(pt, r, s)
        return self.gear

    def update(self, t: float, rpm, speed, skew: float = 0.0) -> Optional[int]:
        """Hizalanmış çift. skew: devir ve hız örnekleri arasındaki süre; max_skew'den büyükse yok sayılır."""
        if rpm is None or speed is None or abs(skew) > self.cfg["max_skew"]:
            return self.gear_at(t)
        self._step(t, rpm, speed)
        return self.gear

    def _step(self, t: float, rpm: float, speed: float):
        c = self.cfg
        self.last_pair_t = t
        if rpm >= c["min_rpm"] and speed > 0:
            self.win.append((t, rpm, speed))
            self._learn(t)
        else:
            self.win.clear()
        raw = self.classify(rpm, speed)
        if raw is None:
            self._cand, self._cand_n = None, 0
            if self.state is not None and t - self._last_consistent > c["unknown_hold_s"]:
                self.state = None
        else:
            self._last_consistent = t
            if raw == self.state:
                self._cand, self._cand_n = None, 0
            else:
                if raw == self._cand:
                    self._cand_n += 1
                else:
                    self._cand, self._cand_n, self._cand_t0 = raw, 1, t
                if self._cand_n >= c["hold_n"] and (self.state is None or t - self._cand_t0 >= c["hold_s"]):
                    self.state, self._cand, self._cand_n = raw, None, 0
        self.tracker.update(t, rpm, self.gear)
