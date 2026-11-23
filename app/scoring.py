"""Sürüş puanlama (saf mantık, Qt yok). Olaylar zaman damgalıdır; ceza km/dakika başına normalize edilir."""
from __future__ import annotations

import statistics
from dataclasses import dataclass
from typing import Optional

from .gear import detect_shifts

# Tüm eşikler tek yerde (dizel 1.6 HDi 92 bg)
CONFIG = dict(
    harsh_accel_ms2=2.5,        # 1 sn boyunca sürdürülen ivmelenme
    harsh_brake_ms2=-3.0,
    accel_window_s=1.0,
    over_rev_rpm=3500,
    lug_rpm=1300, lug_load=70, lug_min_gear=3, lug_min_s=1.0,
    ideal_shift=(2000, 2500), late_shift_rpm=2800, early_shift_rpm=1500,
    cold_coolant=60, cold_rpm=2800, cold_min_s=0.5,
    long_idle_s=120, idle_speed=1.0, idle_min_rpm=300,
    highway_kmh=80, unstable_window_s=20.0, unstable_range_kmh=15.0, unstable_cooldown_s=30.0,
    cooldown_s=3.0, max_dt=3.0,
    min_km=2.0,                 # normalizasyon tabanı (km)
    eco_accel_ok=0.6,           # ortalama |ivme| (m/s²) üstü eko cezası
    # olay başına ceza (10 km başına birim)
    pen=dict(harsh_accel=9, harsh_brake=10, over_rev=6, lugging=5, late_upshift=3, early_upshift=1.5,
             unstable_speed=8),
    pen_cold=25, pen_long_idle=20, idle_share_ok=0.25, shift_dev_div=30.0,
    weights={"Hızlanma": 0.20, "Frenleme": 0.20, "Vites kullanımı / devir": 0.25, "Rölanti": 0.10,
             "Soğuk motor": 0.10, "Hız istikrarı / Eko": 0.15},
)

LABELS = {
    "harsh_accel": "Sert hızlanma", "harsh_brake": "Sert frenleme", "over_rev": "Yüksek devir",
    "lugging": "Düşük devirde zorlanma", "late_upshift": "Geç vites büyütme",
    "early_upshift": "Erken vites büyütme", "cold_abuse": "Soğuk motora yüklenme",
    "long_idle": "Uzun rölanti", "unstable_speed": "Dengesiz hız",
}
SUBS = list(CONFIG["weights"])


@dataclass
class Sample:
    t: float                       # epoch saniye
    rpm: Optional[float] = None
    speed: Optional[float] = None
    load: Optional[float] = None
    coolant: Optional[float] = None
    map: Optional[float] = None
    maf: Optional[float] = None
    gear: Optional[int] = None


def _dts(S, cfg):
    out = [0.0]
    for a, b in zip(S, S[1:]):
        out.append(min(max(b.t - a.t, 0.0), cfg["max_dt"]))
    return out


def distance_km(S, cfg=CONFIG) -> float:
    d = _dts(S, cfg)
    return sum(((S[i].speed or 0) + (S[i - 1].speed or 0)) / 2 * d[i] / 3600 for i in range(1, len(S)))


def accelerations(S, cfg=CONFIG) -> list:
    """Her örnek için ivme (m/s²): >= accel_window_s geriye bakan fark."""
    acc = [None] * len(S)
    j = 0
    for i in range(len(S)):
        if S[i].speed is None:
            continue
        while j + 1 < i and S[i].t - S[j + 1].t >= cfg["accel_window_s"]:
            j += 1
        if j < i and S[j].speed is not None:
            dt = S[i].t - S[j].t
            if cfg["accel_window_s"] * 0.8 <= dt <= cfg["max_dt"]:
                acc[i] = (S[i].speed - S[j].speed) / 3.6 / dt
    return acc


def _episodes(S, flags, vals, min_dur=0.0, cooldown=3.0):
    """Ardışık bayrakları (cooldown içinde birleştirerek) olaya çevirir -> [(i0, i1, değerler)]"""
    runs, i, n = [], 0, len(S)
    while i < n:
        if flags[i]:
            k = i
            while k + 1 < n and flags[k + 1]:
                k += 1
            runs.append([i, k])
            i = k + 1
        else:
            i += 1
    merged = []
    for r in runs:
        if merged and S[r[0]].t - S[merged[-1][1]].t <= cooldown:
            merged[-1][1] = r[1]
        else:
            merged.append(r)
    out = []
    for a, b in merged:
        if S[b].t - S[a].t >= min_dur:
            seg = [vals[x] for x in range(a, b + 1) if vals[x] is not None]
            out.append((a, b, seg))
    return out


def analyze(S: list, cfg: dict = None, t0: float = None) -> dict:
    cfg = cfg or CONFIG
    S = [s for s in S if s.speed is not None or s.rpm is not None]
    res = dict(total=None, subs={}, events=[], metrics={}, short=True)
    if len(S) < 3:
        return res
    t0 = S[0].t if t0 is None else t0
    dts = _dts(S, cfg)
    dur = sum(dts)
    km = distance_km(S, cfg)
    ev = []

    def add(typ, idx, value, unit, text, dur_s=0.0):
        ev.append(dict(type=typ, label=LABELS[typ], t=round(S[idx].t - t0, 1), ts=S[idx].t,
                       value=round(value, 1), unit=unit, text=text, dur=round(dur_s, 1)))

    acc = accelerations(S, cfg)
    for a, b, seg in _episodes(S, [x is not None and x > cfg["harsh_accel_ms2"] for x in acc], acc,
                               cooldown=cfg["cooldown_s"]):
        pk = max(seg)
        add("harsh_accel", a, pk, "m/s²", f"{pk:.1f} m/s² ivmelenme")
    for a, b, seg in _episodes(S, [x is not None and x < cfg["harsh_brake_ms2"] for x in acc], acc,
                               cooldown=cfg["cooldown_s"]):
        pk = min(seg)
        add("harsh_brake", a, pk, "m/s²", f"{pk:.1f} m/s² yavaşlama")
    rp = [s.rpm for s in S]
    for a, b, seg in _episodes(S, [s.rpm is not None and s.rpm > cfg["over_rev_rpm"] for s in S], rp,
                               cooldown=cfg["cooldown_s"]):
        add("over_rev", a, max(seg), "d/dk", f"{max(seg):.0f} d/dk")
    lug = [s.gear is not None and s.gear >= cfg["lug_min_gear"] and s.rpm is not None and s.rpm < cfg["lug_rpm"]
           and s.load is not None and s.load > cfg["lug_load"] for s in S]
    for a, b, seg in _episodes(S, lug, rp, cfg["lug_min_s"], cfg["cooldown_s"]):
        add("lugging", a, min(seg), "d/dk", f"{S[a].gear}. viteste {min(seg):.0f} d/dk, yük >%{cfg['lug_load']}",
            S[b].t - S[a].t)
    cold = [s.coolant is not None and s.coolant < cfg["cold_coolant"] and s.rpm is not None
            and s.rpm > cfg["cold_rpm"] for s in S]
    for a, b, seg in _episodes(S, cold, rp, cfg["cold_min_s"], 5.0):
        add("cold_abuse", a, max(seg), "d/dk", f"Su {S[a].coolant:.0f} °C iken {max(seg):.0f} d/dk")

    # vites değişimleri
    shifts = detect_shifts([(s.t, s.rpm, s.gear) for s in S])
    ups = [x for x in shifts if x.up]
    t_index = {s.t: i for i, s in enumerate(S)}
    for x in ups:
        i = t_index.get(x.t, 0)
        if x.rpm > cfg["late_shift_rpm"]:
            add("late_upshift", i, x.rpm, "d/dk", f"{x.from_gear}→{x.to_gear} geçişi {x.rpm:.0f} d/dk")
        elif x.rpm < cfg["early_shift_rpm"]:
            add("early_upshift", i, x.rpm, "d/dk", f"{x.from_gear}→{x.to_gear} geçişi {x.rpm:.0f} d/dk")
    avg_shift = statistics.mean(x.rpm for x in ups) if ups else None

    # rölanti (duruş + motor çalışıyor: trafik/ışık beklemeleri de dahil)
    idle = [s.speed is not None and s.speed <= cfg["idle_speed"] and s.rpm is not None
            and s.rpm >= cfg["idle_min_rpm"] for s in S]
    idle_s = sum(d for d, f in zip(dts, idle) if f)
    run, fired = 0.0, False
    for i, f in enumerate(idle):
        if f:
            run += dts[i]
            if run >= cfg["long_idle_s"] and not fired:
                fired = True
                add("long_idle", i, run, "sn", f"{run / 60:.1f} dk rölanti", run)
        else:
            run, fired = 0.0, False

    # dengesiz hız (otoyol)
    w = cfg["unstable_window_s"]
    last_t, lo = -1e9, 0
    for i, s in enumerate(S):
        if s.speed is None:
            continue
        while S[lo].t < s.t - w:
            lo += 1
        seg = [x.speed for x in S[lo:i + 1] if x.speed is not None]
        if len(seg) > 5 and s.t - S[lo].t >= w * 0.9 and min(seg) >= cfg["highway_kmh"] - 5 and \
                max(seg) - min(seg) > cfg["unstable_range_kmh"] and s.t - last_t > cfg["unstable_cooldown_s"]:
            add("unstable_speed", i, max(seg) - min(seg), "km/s",
                f"{w:.0f} sn içinde {max(seg) - min(seg):.0f} km/s oynama")
            last_t = s.t

    # alt puanlar
    per10 = max(km, cfg["min_km"]) / 10.0
    cnt = {k: 0 for k in LABELS}
    for e in ev:
        cnt[e["type"]] += 1
    P = cfg["pen"]

    def clamp(v):
        return max(0.0, min(100.0, v))

    subs = {}
    subs["Hızlanma"] = clamp(100 - P["harsh_accel"] * cnt["harsh_accel"] / per10)
    subs["Frenleme"] = clamp(100 - P["harsh_brake"] * cnt["harsh_brake"] / per10)
    lo_i, hi_i = cfg["ideal_shift"]
    dev = 0.0
    if len(ups) >= 2:
        dev = max(0.0, avg_shift - hi_i, lo_i - avg_shift)
    g_pen = sum(P[k] * cnt[k] for k in ("over_rev", "lugging", "late_upshift", "early_upshift")) / per10
    subs["Vites kullanımı / devir"] = clamp(100 - g_pen - dev / cfg["shift_dev_div"])
    idle_share = idle_s / dur if dur > 0 else 0
    subs["Rölanti"] = clamp(100 - cfg["pen_long_idle"] * cnt["long_idle"]
                            - max(0.0, idle_share - cfg["idle_share_ok"]) * 100)
    subs["Soğuk motor"] = clamp(100 - cfg["pen_cold"] * cnt["cold_abuse"])
    moving = [abs(a) for a, s in zip(acc, S) if a is not None and (s.speed or 0) > 5]
    mean_abs_a = statistics.mean(moving) if moving else 0.0
    subs["Hız istikrarı / Eko"] = clamp(100 - P["unstable_speed"] * cnt["unstable_speed"] / per10
                                       - max(0.0, mean_abs_a - cfg["eco_accel_ok"]) * 40)
    wsum = sum(cfg["weights"].values())
    total = sum(subs[k] * cfg["weights"][k] for k in subs) / wsum

    speeds = [s.speed for s in S if s.speed is not None]
    gear_t = {}
    for s, d in zip(S, dts):
        if s.gear:
            gear_t[s.gear] = gear_t.get(s.gear, 0.0) + d
    res.update(
        total=round(total), subs={k: round(v) for k, v in subs.items()}, events=sorted(ev, key=lambda e: e["t"]),
        short=(km < 0.3 or dur < 60),
        metrics=dict(duration_s=round(dur, 1), distance_km=round(km, 2),
                     avg_speed=round(km / (dur / 3600), 1) if dur > 0 else 0.0,
                     max_speed=max(speeds) if speeds else 0, idle_s=round(idle_s, 1),
                     idle_share=round(idle_share, 3), shift_count=len(shifts), upshift_count=len(ups),
                     avg_shift_rpm=round(avg_shift) if avg_shift else None,
                     mean_abs_accel=round(mean_abs_a, 2),
                     max_rpm=max((s.rpm for s in S if s.rpm is not None), default=0),
                     gear_time={str(k): round(v, 1) for k, v in sorted(gear_t.items())},
                     counts={k: v for k, v in cnt.items() if v}),
    )
    return res
