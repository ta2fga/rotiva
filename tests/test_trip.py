"""Sürüş kaydı / vites tahmini / puanlama / koçluk birim testleri (Qt yok)."""
import json
import os
import random
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault("APPDATA", tempfile.mkdtemp())

from app import coach, scoring                              # noqa: E402
from app.gear import DEFAULT_RATIOS, NEUTRAL, GearEstimator     # noqa: E402
from app.scoring import Sample                              # noqa: E402
from app.simulator import DriveCycle                        # noqa: E402
from app.trip import TripRecorder, list_trips, read_samples  # noqa: E402

T0 = 1_700_000_000.0


def run_cycle(loops=3, dt=0.3, seed=1, feed=None):
    """DriveCycle'dan ~3 Hz örnek üretir (hız tamsayı, devir 0.25 adım)."""
    c, rnd, t, out = DriveCycle(), random.Random(seed), 0.0, []
    while t < DriveCycle.LENGTH * loops:
        d = c.at(t)
        sp, rpm = round(d["speed"]), round(d["rpm"] * 4) / 4
        if feed:
            feed(T0 + t, rpm, sp, d)
        out.append((T0 + t, rpm, sp, d))
        t += dt + rnd.uniform(-0.05, 0.05)
    return out


def build_samples(loops=3):
    g = GearEstimator()
    S = []

    def feed(t, rpm, sp, d):
        S.append(Sample(t, rpm, sp, d["load"], round(d["coolant"]), None, None, g.update(t, rpm, sp)))
    run_cycle(loops, feed=feed)
    return S, g


class GearTest(unittest.TestCase):
    def test_recovers_five_ratios(self):
        S, g = build_samples(3)
        for got, true in zip(g.ratios, DriveCycle.TRUE_RATIOS):
            self.assertAlmostEqual(got / true, 1.0, delta=0.04, msg=(g.ratios, true))
        self.assertTrue(all(c > 0 for c in g.counts))
        self.assertEqual(sorted(g.ratios), g.ratios)

    def test_classify_and_none(self):
        g = GearEstimator()
        self.assertEqual(g.classify(2000, 58), 4)            # 29 km/s per 1000
        self.assertEqual(g.classify(2500, 19), 1)            # 7,6
        self.assertEqual(g.classify(850, 40), NEUTRAL)       # hareket hâlinde rölanti: boşta/debriyaj
        self.assertEqual(g.classify(900, 1), NEUTRAL)        # duruyor
        self.assertIsNone(g.classify(2000, 50))              # hiçbir merkeze uymuyor (25): geçiş/debriyaj
        self.assertIsNone(g.classify(None, 50))              # veri yok -> bilinmiyor
        self.assertIsNone(g.classify(0, 0))                  # motor kapalı -> bilinmiyor

    def test_hysteresis_and_unknown(self):
        g = GearEstimator()
        t = T0
        for _ in range(10):
            g.update(t, 2000, 43)                            # 3. vites
            t += 0.3
        self.assertEqual(g.gear, 3)
        g.update(t, 2000, 58)                                # tek sapan örnek vitesi değiştirmez
        t += 0.3
        self.assertEqual(g.gear, 3)
        for _ in range(3):                                   # kısa debriyaj (tutarsız) -> vites korunur
            g.update(t, 2000, 50)
            t += 0.3
        self.assertEqual(g.gear, 3)
        self.assertEqual(g.label(t), "3")
        self.assertEqual(g.label(t + 10), "—")               # veri eskidi -> bilinmiyor, "boş" değil
        self.assertIsNone(g.gear_at(t + 10))

    def test_alignment_rejects_stale_pairs(self):
        """Devir ve hız farklı anlarda okunuyor: hizalayıcı enterpolasyon yapar, skew büyükse çift atılır."""
        g = GearEstimator()
        t = T0
        # 2. viteste hızlanma: hız 20->40 km/s, devir = hız/14*1000 ; devir hızdan 0.4 sn önce okunuyor
        for i in range(40):
            sp = 20 + i * 0.5
            g.push_rpm(t, (sp - 0.2) / 14.0 * 1000)
            g.push_speed(t + 0.4, sp)
            t += 0.8
        self.assertEqual(g.gear, 2)
        g2 = GearEstimator()
        g2.update(T0, 2000, 43, skew=1.5)                    # çok eski devir: kullanılmaz
        self.assertIsNone(g2.state)

    def test_single_odd_peak_stays_plausible_and_old_model_discarded(self):
        """v3: öğrenme varsayılana bağlı değil; tek bir tuhaf tepe (32,8) bile akla yatkın bir şanzıman verir."""
        g = GearEstimator()
        t = T0
        for _ in range(400):
            g.update(t, 2000, 65.6)
            t += 0.3
        r = g.ratios
        self.assertTrue(all(1.15 - 1e-9 <= r[i + 1] / r[i] <= 2.1 + 1e-9 for i in range(4)), r)
        self.assertTrue(0.6 <= g.scale <= 1.6)
        self.assertIsNotNone(g.classify(2000, 65.6))          # öğrenilen tepe artık bir vites
        self.assertEqual(GearEstimator.from_dict({"ratios": [9, 16, 24, 31, 40], "counts": [1] * 5}).ratios,
                         DEFAULT_RATIOS)
        self.assertEqual(GearEstimator.from_dict({"v": 2, "ratios": [9, 16, 24, 31, 40]}).ratios, DEFAULT_RATIOS)

    def test_no_dead_gap_between_centers(self):
        g = GearEstimator()
        self.assertEqual(g.classify(2000, 32.2), 2)          # 16,1 = 2. merkezin %15 üstü: eski algoritmada boşluk
        self.assertEqual(g.classify(2000, 37.0), 3)          # 18,5
        self.assertIsNone(g.classify(2000, 34.7))            # 17,35 = 2-3 tam ortası: tutarsız (debriyaj)

    def test_shifts_detected(self):
        S, g = build_samples(1)
        ups = [s for s in g.shifts if s.up]
        self.assertGreaterEqual(len(ups), 3)
        late = [s for s in ups if (s.from_gear, s.to_gear) == (4, 5)]
        self.assertTrue(late and late[0].rpm > 3300, late)

    def test_persist(self):
        _, g = build_samples(1)
        g2 = GearEstimator.from_dict(json.loads(json.dumps(g.to_dict())))
        self.assertEqual(g2.ratios, [round(r, 3) for r in g.ratios])
        self.assertEqual(g2.counts, g.counts)
        self.assertLess(sum(abs(x - y) for x, y in zip(g.hist, g2.hist)), 0.05 * len(g.hist))
        g2.reestimate()                                      # kayıttan dönen histogram aynı oranları verir
        for x, y in zip(g.ratios, g2.ratios):
            self.assertAlmostEqual(x / y, 1.0, delta=0.005)


D = DEFAULT_RATIOS
CITY = [(4, 0, 0), (7, 14, 1), (12, 28, 2), (18, 42, 3), (30, 45, 3), (36, 55, 4), (56, 55, 4), (62, 40, 4),
        (66, 38, 3), (76, 35, 3), (80, 25, 2), (95, 25, 2), (100, 30, 2), (104, 40, 3), (110, 50, 4),
        (125, 52, 4), (128, 20, 4), (132, 0, 0), (136, 0, 0)]                 # şehir: 5. vites hiç yok
NO_FIRST = [(4, 0, 0), (6, 9, 1), (10, 26, 2), (15, 34, 2), (21, 46, 3), (27, 52, 4), (45, 52, 4), (49, 25, 4),
            (53, 0, 0), (58, 0, 0), (60, 9, 1), (63, 40, 2), (66, 58, 3), (71, 75, 4), (81, 106, 4),
            (85, 106, 5), (93, 112, 5, None, 9.0), (101, 112, 5, None, 9.0), (105, 60, 5), (111, 46, 5, 82),
            (117, 58, 5, 82), (127, 0, 0), (132, 0, 0)]                       # 1. vites yalnızca 0->9 km/s


def drive(ratios, segs=None, loops=4, seed=5):
    """Ayrı okunan devir/hız (0,15 sn kayma, gürültü, tamsayı hız) ile sürüş; son turda vites başına doğruluk."""
    cyc, g, rnd = DriveCycle(ratios, segs), GearEstimator(), random.Random(seed)
    t, ok, tot = 0.0, [0] * 5, [0] * 5
    while t < cyc.LENGTH * loops:
        d = cyc.at(t)
        g.push_rpm(T0 + t, round((d["rpm"] + rnd.gauss(0, 12)) * 4) / 4)
        d2 = cyc.at(t + 0.15)
        g.push_speed(T0 + t + 0.15, round(d2["speed"]))
        if t >= cyc.LENGTH * (loops - 1) and d2["gear"] and d2["speed"] >= 8:
            tot[d2["gear"] - 1] += 1
            ok[d2["gear"] - 1] += g.gear == d2["gear"]
        t += 0.3 + rnd.uniform(-0.05, 0.05)
    return g, [k / n if n else None for k, n in zip(ok, tot)]


class GearDiscoveryTest(unittest.TestCase):
    """Gerçek oranlar varsayılandan çok farklı araçlar: tepe bulma + şanzıman modeli doğru numaralamalı."""

    def check(self, true, segs=None, from_data=(1, 2, 3, 4, 5), tol=0.03):
        g, acc = drive(true, segs)
        for i in range(5):
            self.assertAlmostEqual(g.ratios[i] / true[i], 1.0, delta=tol if i + 1 in from_data else 0.06,
                                   msg=(i + 1, g.ratios, true))
            self.assertEqual(g.classify(2000, true[i] * 2), i + 1, (i + 1, g.ratios))   # her vites doğru
            self.assertEqual(g.counts[i] > 0, i + 1 in from_data, (i + 1, g.counts))   # veriden mi, şekilden mi
        for i, a in enumerate(acc):
            if a is not None and i + 1 in from_data:
                self.assertGreaterEqual(a, 0.6, (i + 1, acc))
        self.assertTrue(g.confident)
        return g, acc

    def test_scaled_up_125(self):                           # bildirilen C-Elysée durumu: 2. hiç, 3->4, 4->5
        g, acc = self.check([x * 1.25 for x in D])
        self.assertGreaterEqual(acc[1], 0.6)                 # 2. vites artık görünüyor

    def test_scaled_down_08(self):
        self.check([x * 0.8 for x in D])

    def test_non_uniform(self):
        self.check([8.2, 16.8, 24.0, 33.0, 40.0])

    def test_city_without_fifth(self):
        g, _ = self.check([x * 1.25 for x in D], CITY, from_data=(2, 3, 4))
        self.assertEqual(g.counts[4], 0)                     # 5. şekilden çıkarıldı

    def test_no_steady_first(self):
        g, _ = self.check([x * 1.25 for x in D], NO_FIRST, from_data=(2, 3, 4, 5))
        self.assertEqual(g.counts[0], 0)


class ScoringTest(unittest.TestCase):
    def test_cycle_events(self):
        S, _ = build_samples(1)
        r = scoring.analyze(S)
        types = {e["type"] for e in r["events"]}
        for want in ("harsh_accel", "harsh_brake", "over_rev", "lugging", "late_upshift", "unstable_speed",
                     "cold_abuse"):
            self.assertIn(want, types)
        self.assertTrue(all("t" in e and "ts" in e for e in r["events"]))
        self.assertTrue(0 <= r["total"] < 80)
        self.assertEqual(set(r["subs"]), set(scoring.SUBS))

    def test_calm_drive_scores_high(self):
        S, v = [], 0.0
        for i in range(600):                                 # 3 dk, yumuşak
            t = T0 + i * 0.3
            v = min(60.0, i * 0.1)
            S.append(Sample(t, max(850, v / 30.0 * 1000), round(v), 30, 85, None, None, 4 if v > 40 else None))
        r = scoring.analyze(S)
        self.assertGreaterEqual(r["total"], 85, r["subs"])
        self.assertFalse(r["events"], r["events"])

    def test_long_idle_and_traffic_stop_counts_as_idle(self):
        S = [Sample(T0 + i * 0.5, 800, 0, 20, 85, None, None, None) for i in range(300)]   # 150 sn
        r = scoring.analyze(S)
        self.assertIn("long_idle", [e["type"] for e in r["events"]])
        self.assertGreater(r["metrics"]["idle_share"], 0.9)
        self.assertLess(r["subs"]["Rölanti"], 60)

    def test_thresholds_in_config(self):
        cfg = dict(scoring.CONFIG, over_rev_rpm=9000, harsh_accel_ms2=99, harsh_brake_ms2=-99)
        S, _ = build_samples(1)
        types = {e["type"] for e in scoring.analyze(S, cfg)["events"]}
        self.assertFalse({"over_rev", "harsh_accel", "harsh_brake"} & types)

    def test_coach(self):
        S, _ = build_samples(1)
        tips = coach.tips(scoring.analyze(S))
        self.assertTrue(3 <= len(tips) <= 5)
        self.assertEqual(tips[0]["kind"], "warn")
        self.assertIn("d/dk", " ".join(t["text"] for t in tips))


class TripTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def rec(self, **cfg):
        return TripRecorder(cfg, base=self.dir)

    def test_auto_start_and_no_auto_stop(self):
        r = self.rec()
        for i in range(20):
            self.assertIsNone(r.feed(T0 + i, 800, 0))
        self.assertFalse(r.active)
        self.assertEqual(r.feed(T0 + 20, 1500, 12), "started")
        self.assertTrue(r.active)
        self.assertGreaterEqual(len(r.samples), 10)           # ön tampon dahil
        for i in range(1, 700):                                # 11+ dk duruş: sürüş SÜRER
            r.feed(T0 + 20 + i, 800, 0)
        self.assertTrue(r.active and not r.paused)
        self.assertFalse(r.stalled(T0 + 719))

    def test_auto_start_setting_off(self):
        r = self.rec(auto_start=False)
        for i in range(30):
            r.feed(T0 + i, 2000, 50)
        self.assertFalse(r.active)
        r.start(T0 + 31)
        self.assertTrue(r.active)

    def test_pause_resume_same_trip(self):
        r = self.rec(auto_start=False)
        r.start(T0)
        for i in range(30):
            r.feed(T0 + i * 0.3, 2000, 50)
        r.pause(T0 + 10)
        self.assertTrue(r.paused and r.active)
        n = len(r.samples)
        self.assertEqual(r.feed(T0 + 300, 2000, 50), "resumed")      # 5 dk sonra aynı sürüşe devam
        self.assertEqual(len(r.samples), n + 1)
        self.assertEqual(len(r.pauses), 1)
        self.assertTrue(r.active and not r.paused)

    def test_stalled_detection(self):
        r = self.rec(auto_start=False)
        r.start(T0)
        r.feed(T0 + 1, 2000, 50)
        self.assertFalse(r.stalled(T0 + 5))
        self.assertTrue(r.stalled(T0 + 20))

    def test_stop_saves_csv_json(self):
        r = self.rec()
        S = run_cycle(1, feed=lambda t, rpm, sp, d: r.feed(t, rpm, sp, d["load"], d["coolant"]))
        self.assertTrue(r.active)
        sm = r.stop(S[-1][0])
        self.assertIsNotNone(sm)
        self.assertFalse(r.active)
        lst = list_trips(self.dir)
        self.assertEqual(len(lst), 1)
        self.assertEqual(lst[0]["total"], sm["total"])
        self.assertGreater(len(read_samples(os.path.join(self.dir, sm["csv"]))), 100)
        self.assertTrue(sm["tips"])
        self.assertTrue(sm["metrics"]["distance_km"] > 1)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "unfinished.csv")))

    def test_autosave_and_recovery(self):
        r = self.rec(autosave_s=30)
        t = T0
        for i in range(400):                                   # ~2 dk
            t = T0 + i * 0.3
            r.feed(t, 2000, 50 if i > 5 else 0)
        self.assertTrue(os.path.exists(os.path.join(self.dir, "unfinished.csv")))
        r2 = self.rec()                                        # "çökme" sonrası yeni oturum
        data = r2.find_unfinished()
        self.assertIsNotNone(data)
        self.assertGreater(data["n"], 100)
        r2.resume_unfinished(data)
        self.assertTrue(r2.active and r2.paused)
        n = len(r2.samples)
        self.assertEqual(r2.feed(t + 120, 2000, 50), "resumed")
        self.assertEqual(len(r2.samples), n + 1)
        self.assertIsNotNone(r2.stop(t + 130))
        self.assertIsNone(self.rec().find_unfinished())

    def test_too_short_not_saved(self):
        r = self.rec(auto_start=False)
        r.start(T0)
        r.feed(T0 + 1, 800, 0)
        self.assertIsNone(r.stop(T0 + 2))
        self.assertEqual(list_trips(self.dir), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
