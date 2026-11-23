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
from app.gear import GearEstimator                          # noqa: E402
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
        self.assertEqual(g.classify(2000, 60), 4)            # 30 km/s per 1000
        self.assertIsNone(g.classify(800, 10))               # rölanti + hareket = kavrama
        self.assertIsNone(g.classify(2000, 2))
        self.assertIsNone(g.classify(2000, 53))              # hiçbir kümeye uymuyor (26.5)
        self.assertEqual(g.classify(2500, 20), 1)

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
