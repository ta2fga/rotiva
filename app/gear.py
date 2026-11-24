"""Vites tahmini (Android core/Gear.kt ile aynı algoritma).

Oran r = hız (km/s) / (devir/1000). Devir ve hız ECU'dan ayrı isteklerle okunduğu için örnekler zamanca
hizalanır (PairAligner: hız anına devir enterpolasyonu). Hizalı çiftler en yakın vites merkezine eşlenir.

Öğrenme (model v3): kararlı sürüş pencerelerinden gelen oran gözlemleri log ölçekli bir histogramda birikir
(~%1,5 genişlikte kutular, 4..70 km/s/1000 d/dk; toplam ağırlık sınırlı, eskiler orantılı söner). Her
`reestimate_n` yeni gözlemde merkezler yeniden kestirilir: düzleştirilmiş histogramın tepeleri bulunur, tepeler
"akla yatkın şanzıman" modeline göre vites numaralarına atanır (oranlar artan, ardışık adım 1,15..2,1;
varsayılan oranların ŞEKLİ tek bir ölçek s ∈ 0,6..1,6 ile ölçeklenir, verisi olan viteste gerçek tepe kullanılır,
verisi olmayan vites komşularının düzeltmesiyle şekilden doldurulur). Tüm atamalar denenir, en düşük maliyetli
seçilir. Böylece gerçek oranlar varsayılandan çok farklı olsa da (ör. ×1,25) vitesler doğru numaralanır.

Sınıflama toleransı komşu merkezler arasındaki (log) yarı aralığa göredir: merkezden yarı aralığın
`gap_frac` kadarına kadar kabul edilir, tam ortadaki dar bant (debriyaj kaydırma/geçiş) tutarsızdır.
Sonuç histerezisle yayınlanır: yeni vites ~0,5 sn ve en az 2 örnek sürmeli; tutarsız (debriyaj/geçiş)
örneklerde son vites kısa süre korunur. "Boşta" (NEUTRAL) yalnızca duruyorken ya da hareket hâlinde rölantide
oran uyuşmuyorsa verilir; veri yoksa/eskiyse sonuç bilinmiyor (None)."""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from typing import Optional

# 2017 C-Elysée 1.6 HDi 92 hp, BE4 5 ileri: km/s per 1000 d/dk (yalnızca başlangıç ve ŞEKİL; gerçek oranlar
# veriden öğrenilir, araç bunlardan %60'a kadar farklı olabilir)
DEFAULT_RATIOS = [7.5, 14.0, 21.5, 29.0, 37.0]
MODEL_VERSION = 3
NEUTRAL = 0
_LD = [math.log(x) for x in DEFAULT_RATIOS]                     # varsayılan şekil (log)
_KER = [math.exp(-0.5 * (k / 1.3) ** 2) for k in range(-4, 5)]   # histogram düzleştirme çekirdeği

CFG = dict(
    # eşleme
    tol=0.10,             # uç vitesler (1. altı, 5. üstü) için oransal tolerans
    gap_frac=0.75,        # komşu merkeze doğru: log yarı aralığın bu kadarı kabul (ortadaki dar bant tutarsız)
    tol_min=0.05,         # komşu tarafında en az bu kadar tolerans
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
    learned_count=40,     # bu kadar gözlemli tepe: vites "öğrenildi"
    # histogram + yeniden kestirim
    hist_min=4.0, hist_max=70.0, hist_bin=0.015,   # log kutular (~%1,5)
    hist_cap=6000.0,      # toplam ağırlık üst sınırı (aşılınca orantılı sönüm -> uyum)
    reestimate_n=25,      # bu kadar yeni gözlemde bir merkezler yeniden kestirilir
    peak_min=8.0, peak_frac=0.012,   # tepe kütlesi en az max(peak_min, peak_frac*toplam)
    peak_merge=0.10,      # log uzaklığı bundan küçük tepeler birleştirilir
    max_peaks=7,
    shape_sigma=0.09,     # tepe ile ölçeklenmiş varsayılan şekil arası log sapma ölçeği
    drop_cost=4.0,        # atanmayan tepe cezası (sigma^2 biriminde)
    scale_min=0.6, scale_max=1.6, scale_sigma=0.3, scale_prior_w=1.0,
    step_min=1.15, step_max=2.1,     # ardışık vites oranı adımı (r[i+1]/r[i]) akla yatkın aralığı
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
    def __init__(self, ratios=None, counts=None, cfg: dict = None, hist=None):
        self.cfg = dict(CFG, **(cfg or {}))
        ok = self._valid(ratios)
        self.ratios = [float(r) for r in ratios] if ok else list(DEFAULT_RATIOS)
        self.counts = [int(x) for x in counts] if ok and counts and len(counts) == 5 else [0] * 5
        c = self.cfg
        self._lmin = math.log(c["hist_min"])
        self._bw = math.log(1 + c["hist_bin"])
        self._nb = int(math.ceil((math.log(c["hist_max"]) - self._lmin) / self._bw))
        self.hist = [0.0] * self._nb          # log oran histogramı (kararlı gözlem ağırlıkları)
        if hist and len(hist) == self._nb:
            self.hist = [max(0.0, float(x)) for x in hist]
        self.scale = 1.0                      # son kestirimde varsayılan şekle göre ölçek
        self._pending = 0                     # son kestirimden beri yeni gözlem
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
        """Akla yatkın şanzıman: aralık içinde, artan, ardışık adımlar makul (varsayılana bağlı DEĞİL)."""
        c = self.cfg
        if not r or len(r) != 5:
            return False
        for i in range(5):
            x = r[i]
            if x is None or x != x or not c["hist_min"] <= x <= c["hist_max"]:
                return False
            if i and not (1.05 <= x / r[i - 1] <= 2.6):
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
        """Kompakt: histogram yalnızca dolu aralık (h0 = ilk kutu), 0,1 hassasiyetle."""
        nz = [i for i, x in enumerate(self.hist) if x >= 0.05]
        h0 = nz[0] if nz else 0
        h = [round(x, 1) for x in self.hist[h0:nz[-1] + 1]] if nz else []
        return {"v": MODEL_VERSION, "ratios": [round(r, 3) for r in self.ratios], "counts": list(self.counts),
                "h0": h0, "h": [int(x) if x == int(x) else x for x in h]}

    @classmethod
    def from_dict(cls, d: Optional[dict], cfg: dict = None) -> "GearEstimator":
        d = d or {}
        if d.get("v", 1) != MODEL_VERSION:          # eski (varsayılana bağlı) öğrenme atılır
            return cls(cfg=cfg)
        g = cls(d.get("ratios"), d.get("counts"), cfg)
        h, h0 = d.get("h") or [], int(d.get("h0") or 0)
        for i, x in enumerate(h):
            if 0 <= h0 + i < g._nb:
                g.hist[h0 + i] = max(0.0, float(x))
        return g

    def reset(self):
        self.ratios, self.counts = list(DEFAULT_RATIOS), [0] * 5
        self.hist = [0.0] * self._nb
        self.scale, self._pending = 1.0, 0
        self.win.clear()
        self.aligner.clear()
        self.state, self._cand, self._cand_n = None, None, 0
        self.dirty = True

    def learned(self, gear: int) -> bool:
        """Bu vitesin merkezi doğrudan veriden (yeterli gözlemli tepe) mi geliyor?"""
        return self.counts[gear - 1] >= self.cfg["learned_count"]

    @property
    def confident(self) -> bool:
        """En az 3 vites veriden öğrenildi: numaralama (hangi tepe kaçıncı vites) güvenilir."""
        return sum(1 for g in range(1, 6) if self.learned(g)) >= 3

    @property
    def shifts(self) -> list:
        return self.tracker.shifts

    # --- sınıflama
    def _nearest(self, r: float):
        """(en yakın merkez indeksi, log sapma)"""
        lr = math.log(r)
        i = min(range(5), key=lambda k: abs(lr - math.log(self.ratios[k])))
        return i, lr - math.log(self.ratios[i])

    def _tol(self, i: int, d: float) -> float:
        """i. merkezin d (log sapma) yönündeki toleransı: komşuya doğru yarı aralığın gap_frac kadarı."""
        c = self.cfg
        j = i + (1 if d > 0 else -1)
        if j < 0 or j > 4:
            return math.log(1 + c["tol"])
        half = abs(math.log(self.ratios[j] / self.ratios[i])) / 2
        return max(math.log(1 + c["tol_min"]), c["gap_frac"] * half)

    def classify(self, rpm, speed) -> Optional[int]:
        """Tek hizalı örnek: 1..5, NEUTRAL ya da None (tutarsız/bilinmiyor)."""
        c = self.cfg
        if rpm is None or speed is None or rpm < c["min_rpm"]:
            return None
        if speed < c["stop_speed"]:
            return NEUTRAL
        i, d = self._nearest(speed / (rpm / 1000.0))
        if abs(d) <= self._tol(i, d) + c["tol_speed_q"] / speed:
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
        """Kararlı gözlemi log histograma ekler; her reestimate_n gözlemde merkezleri yeniden kestirir."""
        c = self.cfg
        x = (math.log(r) - self._lmin) / self._bw - 0.5
        if x < -0.5 or x > self._nb - 0.5:
            return
        i0 = int(math.floor(x))
        f = x - i0
        if 0 <= i0 < self._nb:
            self.hist[i0] += 1 - f
        if i0 + 1 < self._nb:
            self.hist[i0 + 1] += f
        tot = sum(self.hist)
        if tot > c["hist_cap"]:                          # orantılı sönüm: eski veri yavaşça unutulur
            k = c["hist_cap"] / tot
            self.hist = [h * k for h in self.hist]
        self._pending += 1
        self.dirty = True
        if self._pending >= c["reestimate_n"]:
            self.reestimate()

    # --- kestirim: tepe bulma + akla yatkın şanzıman ataması
    def _peaks(self) -> list:
        """Düzleştirilmiş histogram tepeleri: [(log merkez, kütle)] artan sırada."""
        c, h, n = self.cfg, self.hist, self._nb
        sm = [sum(_KER[k + 4] * h[i + k] for k in range(-4, 5) if 0 <= i + k < n) for i in range(n)]
        thr = max(c["peak_min"], c["peak_frac"] * sum(h))
        cand = []
        for i in range(n):
            left = sm[i - 1] if i > 0 else 0.0
            right = sm[i + 1] if i < n - 1 else 0.0
            if sm[i] > left and sm[i] >= right:
                m = sum(h[max(0, i - 3):min(n, i + 4)])
                if m >= thr:
                    cand.append((i, m))
        merge = c["peak_merge"] / self._bw              # çok yakın tepeler birleşir (büyük olan kalır)
        cand.sort(key=lambda p: (-p[1], p[0]))
        kept = []
        for p in cand:
            if all(abs(p[0] - q[0]) >= merge for q in kept):
                kept.append(p)
        kept = sorted(kept[:c["max_peaks"]])
        out = []
        for k, (i, _) in enumerate(kept):              # merkez: komşu tepelerin ortasına kadar ağırlıklı ortalama
            lo = (kept[k - 1][0] + i) / 2 if k > 0 else -1e9
            hi = (kept[k + 1][0] + i) / 2 if k < len(kept) - 1 else 1e9
            js = [j for j in range(max(0, i - 4), min(n, i + 5)) if lo < j < hi]
            w = sum(h[j] for j in js)
            if w > 0:
                cj = sum(h[j] * j for j in js) / w
                out.append((self._lmin + (cj + 0.5) * self._bw, w))
        return out

    def _fit(self, peaks: list, assign: list):
        """assign[j]: tepe j'nin vites indeksi (0..4) ya da None (atanmadı).
        Dönüş (maliyet, oranlar, ölçek, vites kütleleri) ya da None (akla yatkın değil)."""
        c = self.cfg
        w = [math.sqrt(m) for _, m in peaks]
        used = [j for j in range(len(peaks)) if assign[j] is not None]
        sw = sum(w[j] for j in used)
        ls = sum(w[j] * (peaks[j][0] - _LD[assign[j]]) for j in used) / (sw + c["scale_prior_w"])
        if not math.log(c["scale_min"]) <= ls <= math.log(c["scale_max"]):
            return None
        cost = c["scale_prior_w"] * (ls / c["scale_sigma"]) ** 2
        for j in range(len(peaks)):
            if assign[j] is None:
                cost += c["drop_cost"] * w[j]
            else:
                cost += w[j] * ((peaks[j][0] - _LD[assign[j]] - ls) / c["shape_sigma"]) ** 2
        # vites başına log düzeltme: veri varsa tepenin kendisi, yoksa komşu düzeltmelerinden (şekil korunur)
        corr = [None] * 5
        mass = [0.0] * 5
        for j in used:
            corr[assign[j]] = peaks[j][0] - _LD[assign[j]]
            mass[assign[j]] = peaks[j][1]
        known = [g for g in range(5) if corr[g] is not None]
        full = list(corr)
        for g in range(5):
            if full[g] is not None:
                continue
            lo = [k for k in known if k < g]
            hi = [k for k in known if k > g]
            if lo and hi:
                a, b = lo[-1], hi[0]
                full[g] = corr[a] + (corr[b] - corr[a]) * (g - a) / (b - a)
            else:
                full[g] = corr[lo[-1]] if lo else corr[hi[0]]
        r = [math.exp(_LD[g] + full[g]) for g in range(5)]
        for g in range(1, 5):
            if not c["step_min"] <= r[g] / r[g - 1] <= c["step_max"]:
                return None
        return cost, r, math.exp(ls), mass

    def reestimate(self) -> bool:
        """Histogramdan 5 merkezi yeniden kestirir (tüm artan atamalar denenir); değiştiyse True."""
        self._pending = 0
        peaks = self._peaks()
        if not peaks:
            return False
        best = None
        assign = [None] * len(peaks)

        def rec(j: int, next_g: int):
            nonlocal best
            if j == len(peaks):
                if next_g > 0:
                    f = self._fit(peaks, assign)
                    if f and (best is None or f[0] < best[0]):
                        best = f
                return
            assign[j] = None
            rec(j + 1, next_g)
            for g in range(next_g, 5):
                assign[j] = g
                rec(j + 1, g + 1)
            assign[j] = None
        rec(0, 0)
        if best is None:
            return False
        _, r, s, mass = best
        changed = any(abs(a - b) / b > 1e-4 for a, b in zip(r, self.ratios))
        self.ratios, self.scale = r, s
        self.counts = [int(round(m)) for m in mass]
        self.dirty = True
        return changed

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
