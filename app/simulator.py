"""ELM327 + PSA dizel ECU simülatörü (Demo modu). Gerçek bir adaptör gibi komut/yanıt işler."""
from __future__ import annotations

import math
import random
import re
import time
import zlib

from .elm327 import Transport

ABSENT = {"6AD", "6A7"}   # simülatörde bulunmayan ECU adresleri (tarayıcı testi için)
VIN = "VF7NC9HJC12345678"
CAL_ID = "9H06PSA0123456789"
ECU_NAME = "EDC17C60-PSA"


def _frames(header: str, data: bytes, spaces: bool) -> list:
    """ISO-TP çerçeveleri (ELM'in ATH1 çıktısı gibi)."""
    sep = " " if spaces else ""
    if len(data) <= 7:
        parts = [bytes([len(data)]) + data]
    else:
        parts = [bytes([0x10 | (len(data) >> 8), len(data) & 0xFF]) + data[:6]]
        rest, n = data[6:], 1
        while rest:
            parts.append(bytes([0x20 | (n & 0xF)]) + rest[:7])
            rest, n = rest[7:], n + 1
    return [header + sep + sep.join(f"{b:02X}" for b in p) for p in parts]


class DriveCycle:
    """Demo sürüş döngüsü: şehir + otoyol, vites geçişleri (gerçek oranlarla tutarlı), birkaç sert olay.
    Segment: (bitiş_sn, bitiş_hızı km/s, vites 0=boşta, ek: yük, dalgalanma genliği)."""
    TRUE_RATIOS = [7.8, 14.6, 21.4, 29.8, 38.6]       # km/s / 1000 d/dk (simülatörün gerçeği)
    IDLE = 800.0
    SHIFT_S = 0.7
    SEGS = [
        (4, 0, 0), (10, 26, 1), (15, 34, 2), (21, 46, 3), (27, 52, 4), (45, 52, 4), (49, 25, 4), (53, 0, 0),
        (58, 0, 0),
        (60, 20, 1), (62, 40, 2), (65, 58, 3),                    # sert kalkış
        (70, 75, 4), (80, 106, 4),                                # geç vites (yüksek devir)
        (84, 106, 5), (92, 112, 5, None, 9.0), (100, 112, 5, None, 9.0),   # dengesiz otoyol hızı
        (104, 60, 5),                                             # sert frenleme
        (110, 46, 5, 82), (116, 58, 5, 82),                       # düşük devirde zorlanma (yüksek yük)
        (126, 0, 0), (131, 0, 0),
    ]
    LENGTH = 131.0

    def at(self, t: float) -> dict:
        tc = t % self.LENGTH
        prev_t, prev_v, prev_g = 0.0, 0.0, 0
        seg = None
        for sg in self.SEGS:
            if tc <= sg[0]:
                seg = sg
                break
            prev_t, prev_v, prev_g = sg[0], sg[1], sg[2]
        seg = seg or self.SEGS[-1]
        te, ve, g = seg[0], seg[1], seg[2]
        load_o = seg[3] if len(seg) > 3 else None
        wob = seg[4] if len(seg) > 4 else 0.0
        f = (tc - prev_t) / max(te - prev_t, 1e-6)
        v = prev_v + (ve - prev_v) * f
        acc = (ve - prev_v) / max(te - prev_t, 1e-6) / 3.6
        if wob:
            v += wob * math.sin(tc * 0.9)
        v = max(0.0, v)
        shifting = g != prev_g and prev_g >= 1 and g >= 1 and (tc - prev_t) < self.SHIFT_S
        gear = g
        if g >= 1:
            rpm = max(self.IDLE, v / self.TRUE_RATIOS[g - 1] * 1000)
            if shifting:
                gear = None
                old = max(self.IDLE, v / self.TRUE_RATIOS[prev_g - 1] * 1000)
                rpm = max(self.IDLE, old * (1 - 0.35 * (tc - prev_t) / self.SHIFT_S))
        else:
            rpm = self.IDLE
            gear = None
        rpm += 10 * math.sin(t * 4.1) + 6 * math.sin(t * 9.7)
        load = load_o if load_o is not None else max(5.0, min(95.0, 18 + 14 * acc + 0.0006 * v * v))
        if v < 1:
            load = 20 + 2 * math.sin(t / 3)
        coolant = min(88.0, 38 + 1.2 * t)
        return dict(speed=v, rpm=rpm, gear=gear, load=load, coolant=coolant)


class SimulatedELM:
    """Komut işleyici. ``handle(cmd) -> str`` yanıtı ('>' istemi hariç) döndürür."""

    def __init__(self, full_psa=None, drive=None):
        if full_psa is not None:
            self.full_psa = full_psa
        elif not hasattr(self, "full_psa"):
            self.full_psa = False
        if drive is not None:
            self.drive = drive
        elif not hasattr(self, "drive"):
            self.drive = False
        self.cycle = DriveCycle()
        self.t0 = time.time()
        self.echo = True
        self.spaces = True
        self.headers = False
        self.tx = "7DF"
        self.cra = ""
        self.dtcs = {0x03: [0x0087, 0x2002], 0x07: [0x0299], 0x0A: []}
        self.uds_dtcs = {"default": [(0x01, 0x00, 0x1A, 0x09), (0xC1, 0x40, 0x00, 0x2F)]}
        self.cleared = False
        self.pids = {}
        self._build()

    # --- değer üretimi -------------------------------------------------
    def _build(self):
        def u8(v): return bytes([max(0, min(255, int(v)))])
        def u16(v): v = max(0, min(65535, int(v))); return bytes([v >> 8, v & 0xFF])
        self.pids = {
            0x01: lambda t: bytes([0x00, 0x07, 0xA1, 0x00]),
            0x13: lambda t: u8(3),
            0x1C: lambda t: u8(6),
            0x24: lambda t: bytes([0x7F, 0xFF, 0x7F, 0xFF]),
            0x04: lambda t: u8(self.load(t) * 255 / 100),
            0x05: lambda t: u8(self.coolant(t) + 40),
            0x0B: lambda t: u8(100 + self.load(t) * 1.2),
            0x0C: lambda t: u16(self.rpm(t) * 4),
            0x0D: lambda t: u8(self.speed(t)),
            0x0F: lambda t: u8(32 + 40 + 3 * math.sin(t / 9)),
            0x10: lambda t: u16(self.rpm(t) / 40 * 100 * (0.6 + self.load(t) / 100)),
            0x11: lambda t: u8(self.load(t) * 0.8 * 255 / 100),
            0x1F: lambda t: u16(t),
            0x21: lambda t: u16(0),
            0x23: lambda t: u16((22000 + self.load(t) * 100) / 10),
            0x2F: lambda t: u8(62 * 255 / 100),
            0x33: lambda t: u8(101),
            0x42: lambda t: u16((13.9 + 0.1 * math.sin(t / 5)) * 1000),
            0x46: lambda t: u8(18 + 40),
            0x5C: lambda t: u8(min(95, 25 + t * 0.6) + 40),
        }

    # Gerçek araç modu: rölanti (motor ısınmış, ~67 °C) / PSA tam erişim modu: değişken sürüş profili
    def rpm(self, t):
        if self.drive:
            return self.cycle.at(t)["rpm"]
        if not self.full_psa:
            return 800 + 15 * math.sin(t * 1.7) + 8 * math.sin(t * 5.3)
        return 850 + 1300 * max(0, math.sin(t / 7)) + 80 * math.sin(t * 3)

    def speed(self, t):
        if self.drive:
            return self.cycle.at(t)["speed"]
        return 0 if not self.full_psa else max(0, 45 + 40 * math.sin(t / 11))

    def load(self, t):
        if self.drive:
            return self.cycle.at(t)["load"]
        if not self.full_psa:
            return 24 + 2 * math.sin(t / 3)
        return 25 + 20 * max(0, math.sin(t / 7)) + 3 * math.sin(t)

    def coolant(self, t):
        if self.drive:
            return self.cycle.at(t)["coolant"]
        if not self.full_psa:
            return 67 + 0.4 * math.sin(t / 20)
        return min(90, 20 + t * 1.2)

    def did_value(self, did: int, t: float) -> bytes:
        seed = zlib.crc32(did.to_bytes(2, "big"))
        base = 0x2000 + (seed % 0x4000)
        v = int(base + 0x100 * math.sin(t / 6 + seed % 7))
        return bytes([v >> 8, v & 0xFF, (seed >> 8) & 0xFF, seed & 0xFF])

    # --- komutlar ----------------------------------------------------------
    def handle(self, line: str) -> str:
        cmd = line.strip().upper().replace(" ", "")
        out = self._dispatch(cmd)
        text = ("\r".join(out) + "\r") if out else ""
        return (line.strip() + "\r" if self.echo else "") + text + "\r"

    def _ok(self): return ["OK"]

    def _dispatch(self, c: str) -> list:
        if not c:
            return []
        if c.startswith("AT"):
            return self._at(c[2:])
        if not re.fullmatch(r"[0-9A-F]+", c) or len(c) % 2:
            return ["?"]
        data = bytes.fromhex(c)
        t = time.time() - self.t0
        if self.tx in ABSENT:
            return ["NO DATA"]
        if not self.full_psa:
            # Gerçek araç: yalnızca 7E0 yanıtlar; üretici servislerine "servis desteklenmiyor"
            if self.tx.startswith("7E"):
                if data[0] in (0x10, 0x3E, 0x22, 0x21, 0x19):
                    return self._reply(bytes([0x7F, data[0], 0x11]))
            elif self.tx != "7DF":
                return ["NO DATA"]
        if self.tx == "7DF" or self.tx.startswith("7E"):
            return self._obd(data, t)
        return self._uds(data, t)

    def _at(self, a: str) -> list:
        if a.startswith("Z") or a == "WS":
            self.__init__()
            return ["", "ELM327 v2.1" if not self.full_psa else "ELM327 v1.5"]
        if a == "I":
            return ["ELM327 v2.1" if not self.full_psa else "ELM327 v1.5"]
        if a.startswith("E"): self.echo = a[1:] != "0"; return self._ok()
        if a.startswith("S") and not a.startswith("SP") and not a.startswith("ST") and not a.startswith("SH"):
            self.spaces = a[1:] != "0"; return self._ok()
        if a.startswith("H"): self.headers = a[1:] != "0"; return self._ok()
        if a.startswith("SH"): self.tx = a[2:].zfill(3); return self._ok()
        if a.startswith("CRA"): self.cra = a[3:]; return self._ok()
        if a == "AR": self.cra = ""; return self._ok()
        if a == "DPN": return ["A6"]
        if a == "DP": return ["AUTO, ISO 15765-4 (CAN 11/500)"]
        if a == "RV": return [f"{13.8 + random.uniform(-0.1, 0.1):.1f}V"]
        if a == "@1": return ["OBDII to RS232 Interpreter"]
        return self._ok()  # L0, AT1, SP0, ST, CAF, D ...

    def _hdr(self) -> str:
        if self.tx == "7DF":
            return "7E8"
        if self.cra:
            return self.cra
        if self.tx.startswith("7E"):
            return f"{int(self.tx, 16) + 8:03X}"
        return f"{int(self.tx, 16) - 0x100:03X}"

    def _reply(self, payload: bytes) -> list:
        return _frames(self._hdr(), payload, self.spaces)

    CAR_MASKS = {0x00: "983BA011", 0x20: "B0002001"}   # gerçek araçtan ölçülen bit eşlemleri

    def _supported(self, base: int):
        if not self.full_psa:
            m = self.CAR_MASKS.get(base)
            return bytes.fromhex(m) if m else None
        m = 0
        for p in self.pids:
            if base < p <= base + 32:
                m |= 1 << (32 - (p - base))
        if any(p > base + 32 for p in self.pids):
            m |= 1
        return m.to_bytes(4, "big")

    CAR_PIDS = {0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x13, 0x1C, 0x21, 0x23, 0x24, 0x33}

    def _obd(self, d: bytes, t: float) -> list:
        mode = d[0]
        if mode == 0x01 and len(d) >= 2:
            pid = d[1]
            if pid % 0x20 == 0:
                m = self._supported(pid)
                return self._reply(bytes([0x41, pid]) + m) if m else ["NO DATA"]
            if not self.full_psa and pid not in self.CAR_PIDS:
                return ["NO DATA"]
            f = self.pids.get(pid)
            return self._reply(bytes([0x41, pid]) + f(t)) if f else ["NO DATA"]
        if mode in (0x03, 0x07, 0x0A):
            codes = [] if (self.cleared or not self.full_psa) else self.dtcs[mode]
            body = bytes([len(codes)]) + b"".join(c.to_bytes(2, "big") for c in codes)
            return self._reply(bytes([mode + 0x40]) + body)
        if mode == 0x04:
            self.cleared = True
            return self._reply(bytes([0x44]))
        if mode == 0x09 and len(d) >= 2:
            pid = d[1]
            if not self.full_psa:
                if pid == 0x04:
                    return self._reply(bytes([0x49, 4, 1]) + b"9691396199")
                if pid == 0x00:
                    return self._reply(bytes([0x49, 0, 0x40, 0, 0, 0]))
                return self._reply(bytes([0x7F, 0x09, 0x12]))
            if pid == 0x00:
                return self._reply(bytes([0x49, 0, 0x55, 0x40, 0, 0]))
            if pid == 0x02:
                return self._reply(bytes([0x49, 2, 1]) + VIN.encode())
            if pid == 0x04:
                return self._reply(bytes([0x49, 4, 1]) + CAL_ID.encode().ljust(16, b"\0")[:16])
            if pid == 0x06:
                return self._reply(bytes([0x49, 6, 1, 0xDE, 0xAD, 0xBE, 0xEF]))
            if pid == 0x0A:
                return self._reply(bytes([0x49, 0x0A, 1]) + ECU_NAME.encode().ljust(20, b"\0")[:20])
        return ["NO DATA"]

    def _uds(self, d: bytes, t: float) -> list:
        sv = d[0]
        if sv == 0x10:
            return self._reply(bytes([0x50, d[1], 0, 0x32, 1, 0xF4]))
        if sv == 0x3E:
            return self._reply(bytes([0x7E, 0]))
        if sv == 0x21 and len(d) >= 2:
            if zlib.crc32(bytes([d[1]])) % 4 == 0:
                return self._reply(bytes([0x7F, 0x21, 0x12]))
            return self._reply(bytes([0x61, d[1]]) + self.did_value(0x1000 + d[1], t))
        if sv == 0x22 and len(d) >= 3:
            did = (d[1] << 8) | d[2]
            if d[1] in (0xF1, 0xD4) and zlib.crc32(did.to_bytes(2, "big")) % 3 == 0:
                return self._reply(bytes([0x7F, 0x22, 0x31]))
            return self._reply(bytes([0x62, d[1], d[2]]) + self.did_value(did, t))
        if sv == 0x19 and len(d) >= 3 and d[1] == 0x02:
            recs = self.uds_dtcs["default"] if self.tx in ("7E0", "752") else []
            return self._reply(bytes([0x59, 0x02, 0xFF]) + b"".join(bytes(r) for r in recs))
        return self._reply(bytes([0x7F, sv, 0x11]))


class SimTransport(Transport):
    """Seri port gibi davranan bellek içi taşıma."""

    def __init__(self, latency: float = 0.02, full_psa: bool = False, drive: bool = False):
        self.sim = SimulatedELM(full_psa, drive)
        self.buf = b""
        self.latency = latency
        self._open = False

    def open(self): self._open = True
    def close(self): self._open = False

    def write(self, data: bytes):
        if not self._open:
            raise OSError("kapalı")
        time.sleep(self.latency)
        for line in data.decode("ascii", "ignore").split("\r"):
            if line.strip():
                self.buf += (self.sim.handle(line) + ">").encode("ascii")

    def read(self, timeout: float) -> bytes:
        if not self.buf:
            time.sleep(min(timeout, 0.01))
        out, self.buf = self.buf, b""
        return out
