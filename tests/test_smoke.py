"""Simülatörü ELM katmanından geçiren duman testi (GUI yok).  Çalıştır: python tests/test_smoke.py"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["APPDATA"] = tempfile.mkdtemp()   # kullanıcı dosyalarını izole et

from app.datafile import DataStore, save_user_did          # noqa: E402
from app.elm327 import ELM327, ElmError, parse_frames      # noqa: E402
from app.pids import BY_KEY, build_catalog, status_of      # noqa: E402
from app.safe_eval import compile_formula, FormulaError    # noqa: E402
from app import scanner                                    # noqa: E402
from app.simulator import SimTransport, VIN                # noqa: E402


class SmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.elm = ELM327(SimTransport(latency=0, full_psa=True))
        cls.info = cls.elm.connect("0")
        cls.store = DataStore()

    @classmethod
    def tearDownClass(cls):
        cls.elm.disconnect()

    def test_init(self):
        self.assertTrue(self.info["ecu_ok"])
        self.assertIn("CAN", self.elm.protocol)
        self.assertIn(0x0C, self.elm.supported)
        self.assertTrue(self.elm.is_can)

    def test_live_pids(self):
        rpm = self.elm.read_pid(BY_KEY["rpm"])
        self.assertTrue(500 < rpm < 3000, rpm)
        self.assertTrue(-40 <= self.elm.read_pid(BY_KEY["coolant"]) <= 130)
        frp = self.elm.read_pid(BY_KEY["frp"])  # bar
        self.assertTrue(150 < frp < 2500, frp)

    def test_dtc_and_clear(self):
        self.assertIn("P0087", self.elm.read_dtcs(0x03))
        self.assertEqual(self.elm.read_dtcs(0x07), ["P0299"])
        self.assertEqual(self.elm.read_dtcs(0x0A), [])
        self.assertTrue(self.elm.clear_dtcs())
        self.assertEqual(self.elm.read_dtcs(0x03), [])

    def test_vin_multiframe(self):
        self.assertEqual(self.elm.vehicle_info()["VIN"], VIN)

    def test_isotp_parser(self):
        lines = ["7E810144902015756", "7E82157573241", "7E82258"]
        (h, d), = parse_frames(lines, 3)
        self.assertEqual(h, "7E8")
        self.assertEqual(d[:3], bytes([0x49, 2, 1]))

    def test_uds_did_and_dtc(self):
        ecu = {"name": "T", "tx": "752", "rx": "652", "session": "10 03"}
        self.assertEqual(len(self.elm.read_did(ecu, 0xD810)), 4)
        self.assertEqual(len(self.elm.read_did(ecu, 0x05, 0x21)), 4)
        dt = self.elm.read_uds_dtcs(ecu)
        self.assertTrue(dt and "-" in dt[0][0])
        self.assertEqual(self.elm.send("ATSH7DF")[0], "OK")   # başlık geri yüklendi mi

    def test_scanner(self):
        pairs = [{"name": "ok", "tx": "752", "rx": "652"}, {"name": "yok", "tx": "6AD", "rx": "68D"}]
        res = scanner.scan_ecus(self.elm, pairs)
        self.assertTrue(res[0]["alive"] and not res[1]["alive"])
        ecu = {"name": "T", "tx": "752", "rx": "652", "session": ""}
        hits = scanner.scan_ids(self.elm, ecu, 0x22, scanner.parse_range("F180-F18F", 0x22))
        self.assertTrue(hits)
        hits = scanner.scan_ids(self.elm, ecu, 0x21, scanner.parse_range("00-0F", 0x21), only_positive=True)
        self.assertTrue(all(p for _, _, p in hits))

    def test_catalog_and_user_dids(self):
        e = self.store.ecus[0]
        save_user_did({"ecu": e["name"], "name": "Test", "service": "22", "did": "F190",
                       "formula": "A*256+B", "unit": "x"})
        st = DataStore()
        items = build_catalog(st)
        self.assertTrue(any(i.source == "psa" for i in items) or not st.dids)
        self.assertTrue(any(d["name"] == "Test" for d in st.dids))
        self.assertEqual(status_of(items[0], None), "none")

    def test_dtc_dictionary(self):
        self.assertGreaterEqual(len(self.store.dtcs), 150)
        self.assertNotEqual(self.store.describe("P0299"), "Açıklama bulunamadı")


class RealCarProfileTest(unittest.TestCase):
    """Gerçek araç davranışı: VIN yok, üretici ECU'ları yanıtsız, 7E0 'servis desteklenmiyor'."""

    @classmethod
    def setUpClass(cls):
        cls.elm = ELM327(SimTransport(latency=0))
        cls.info = cls.elm.connect("0")

    @classmethod
    def tearDownClass(cls):
        cls.elm.disconnect()

    def test_supported_and_values(self):
        self.assertEqual(self.elm.supported >= {0x04, 0x05, 0x0C, 0x0D, 0x23, 0x33}, True)
        self.assertNotIn(0x42, self.elm.supported)
        self.assertTrue(60 < self.elm.read_pid(BY_KEY["coolant"]) < 75)

    def test_vehicle_info(self):
        vi = self.elm.vehicle_info()
        self.assertEqual(vi["VIN"], "ECU desteklemiyor")
        self.assertEqual(vi["Kalibrasyon ID"], "9691396199")

    def test_negative_and_no_data(self):
        from app.elm327 import NegativeResponse
        with self.assertRaises(NegativeResponse):
            self.elm.uds_request({"tx": "7E0", "rx": "7E8", "session": ""}, bytes([0x22, 0xF1, 0x90]))
        with self.assertRaises(ElmError):
            self.elm.uds_request({"tx": "752", "rx": "652", "session": ""}, bytes([0x22, 0xF1, 0x90]))
        self.assertFalse(scanner.probe_manufacturer(self.elm, DataStore()))
        self.assertEqual(self.elm.read_dtcs(0x03), [])


class SafeEvalTest(unittest.TestCase):
    def test_ok(self):
        self.assertEqual(compile_formula("(A*256+B)/4-40")(bytes([1, 0])), 24.0)
        self.assertEqual(compile_formula("A<<8|B")(bytes([1, 2])), 258.0)

    def test_rejects(self):
        for bad in ["__import__('os').system('x')", "A.real", "[1]", "open('f')", "A**99999",
                    "lambda: 1", "'a'", "A if", "x+1", "(1).__class__"]:
            with self.assertRaises(FormulaError, msg=bad):
                compile_formula(bad)(bytes([2]))



from test_trip import GearTest, ScoringTest, TripTest      # noqa: E402,F401  (sürüş kaydı birim testleri)


if __name__ == "__main__":
    unittest.main(verbosity=2)
