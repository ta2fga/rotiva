"""ELM327 katmanı: taşıma (seri/TCP/simülatör), iş parçacıklı I/O, ISO-TP ayrıştırma, OBD/UDS yardımcıları."""
from __future__ import annotations

import queue
from contextlib import contextmanager
import re
import socket
import threading
import time
from concurrent.futures import Future
from typing import Callable, Optional

from .pids import BY_PID, supported_from_masks, Pid


class ElmError(Exception):
    pass


class ElmTimeout(ElmError):
    pass


class NoData(ElmError):
    pass


NRC_TR = {
    0x10: "Genel ret", 0x11: "Servis desteklenmiyor", 0x12: "Alt fonksiyon/PID desteklenmiyor",
    0x13: "Mesaj uzunluğu veya biçimi hatalı", 0x14: "Yanıt çok uzun", 0x21: "ECU meşgul, tekrar deneyin",
    0x22: "Koşullar uygun değil", 0x24: "İstek sırası hatalı", 0x31: "İstek aralık dışı (kimlik yok)",
    0x33: "Güvenlik erişimi reddedildi", 0x35: "Geçersiz anahtar", 0x78: "Yanıt bekleniyor",
    0x7E: "Alt fonksiyon bu oturumda desteklenmiyor", 0x7F: "Servis bu oturumda desteklenmiyor",
}


def nrc_text(nrc: int) -> str:
    return NRC_TR.get(nrc, "Bilinmeyen neden")


class NegativeResponse(ElmError):
    def __init__(self, service: int, nrc: int):
        self.service, self.nrc = service, nrc
        super().__init__(f"Negatif yanıt: servis {service:02X}, kod 0x{nrc:02X} ({nrc_text(nrc)})")


# --------------------------------------------------------------------------- taşıma
class Transport:
    def open(self): ...
    def close(self): ...
    def write(self, data: bytes): ...
    def read(self, timeout: float) -> bytes: ...


class SerialTransport(Transport):
    def __init__(self, port: str, baud: int = 38400):
        self.port, self.baud, self.ser = port, baud, None

    def open(self):
        import serial
        try:
            self.ser = serial.Serial(self.port, self.baud, timeout=0.05, write_timeout=2)
        except Exception as e:
            raise ElmError(f"{self.port} açılamadı: {e}") from e

    def close(self):
        if self.ser:
            try:
                self.ser.close()
            except Exception:
                pass
            self.ser = None

    def write(self, data):
        self.ser.write(data)
        self.ser.flush()

    def read(self, timeout):
        self.ser.timeout = timeout
        return self.ser.read(self.ser.in_waiting or 1)


class TcpTransport(Transport):
    def __init__(self, host: str = "192.168.0.10", port: int = 35000):
        self.host, self.port, self.sock = host, port, None

    def open(self):
        try:
            self.sock = socket.create_connection((self.host, self.port), timeout=5)
        except OSError as e:
            raise ElmError(f"{self.host}:{self.port} bağlantısı kurulamadı: {e}") from e

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def write(self, data):
        self.sock.sendall(data)

    def read(self, timeout):
        self.sock.settimeout(timeout)
        try:
            d = self.sock.recv(4096)
            if d == b"":
                raise ElmError("Bağlantı kapandı")
            return d
        except socket.timeout:
            return b""


# --------------------------------------------------------------------------- ayrıştırma
NOISE = ("SEARCHING", "BUS INIT", "STOPPED", "OK_")
ERRORS = ("UNABLE TO CONNECT", "BUS BUSY", "CAN ERROR", "BUFFER FULL", "BUS ERROR", "DATA ERROR",
          "FB ERROR", "LV RESET", "ACT ALERT")
_HEX = re.compile(r"^[0-9A-F]+$")


def clean_lines(raw: str, cmd: str = "") -> list:
    out = []
    for ln in re.split(r"[\r\n]+", raw.replace(">", "")):
        ln = ln.strip()
        if not ln or ln.upper() == cmd.upper().strip():
            continue
        out.append(ln)
    return out


def header_len_for(proto: str) -> int:
    """Protokol numarasına göre başlık hane sayısı (ATH1, boşluksuz)."""
    p = proto.upper().lstrip("A")[:1]
    return {"6": 3, "7": 8, "8": 3, "9": 8}.get(p, 6)


def parse_frames(lines: list, header_len: int = 3) -> list:
    """OBD satırlarını ISO-TP birleştirerek [(başlık, bytes)] döndürür (PCI baytı atılmış)."""
    msgs: dict = {}
    order = []
    for ln in lines:
        s = ln.replace(" ", "").upper()
        if not _HEX.match(s) or len(s) < header_len + 2:
            continue
        if header_len == 6:  # CAN dışı: 3 bayt başlık, son bayt sağlama
            hdr, data = s[:6], bytes.fromhex(s[6:-2])
            msgs.setdefault(hdr, bytearray()).extend(data)
            if hdr not in order:
                order.append(hdr)
            continue
        hdr, body = s[:header_len], s[header_len:]
        if len(body) % 2:  # 3 haneli başlıkta hizalama
            continue
        b = bytes.fromhex(body)
        if not b:
            continue
        t = b[0] >> 4
        if hdr not in order:
            order.append(hdr)
        st = msgs.setdefault(hdr, bytearray())
        if t == 0:
            st[:] = b[1:1 + (b[0] & 0xF)]
            msgs[hdr + "#len"] = None
        elif t == 1:
            total = ((b[0] & 0xF) << 8) | b[1]
            st[:] = b[2:]
            msgs[hdr + "#len"] = total
        elif t == 2:
            st.extend(b[1:])
    res = []
    for h in order:
        data = bytes(msgs[h])
        total = msgs.get(h + "#len")
        if total:
            data = data[:total]
        res.append((h, data))
    return res


def decode_dtc(b1: int, b2: int) -> str:
    return "PCBU"[b1 >> 6] + str((b1 >> 4) & 3) + f"{b1 & 0xF:X}{b2:02X}"


def decode_dtc_list(data: bytes, has_count: bool) -> list:
    if has_count and data:
        data = data[1:]
    codes = []
    for i in range(0, len(data) - 1, 2):
        if data[i] == 0 and data[i + 1] == 0:
            continue
        codes.append(decode_dtc(data[i], data[i + 1]))
    return codes


# --------------------------------------------------------------------------- ELM327
class ELM327:
    def __init__(self, transport: Transport, log: Optional[Callable[[str], None]] = None):
        self.t = transport
        self.log = log or (lambda s: None)
        self.q: queue.Queue = queue.Queue()
        self.lock = threading.RLock()       # çok komutlu dizileri (ATSH ... ATAR) atomik yapar
        self._thread: Optional[threading.Thread] = None
        self._run = False
        self.connected = False
        self.version = ""
        self.protocol = ""
        self.protocol_num = ""
        self.header_len = 3
        self.supported: set = set()
        self.default_timeout = 3.0

    @property
    def is_can(self) -> bool:
        return self.protocol_num[:1] in ("6", "7", "8", "9")

    # -- I/O iş parçacığı
    def _io_loop(self):
        while self._run:
            try:
                cmd, timeout, fut = self.q.get(timeout=0.2)
            except queue.Empty:
                continue
            if fut is None:
                continue
            try:
                fut.set_result(self._exchange(cmd, timeout))
            except Exception as e:  # noqa: BLE001
                fut.set_exception(e)
        # kuyrukta kalanları iptal et
        while not self.q.empty():
            try:
                _, _, f = self.q.get_nowait()
                if f:
                    f.set_exception(ElmError("Bağlantı kapatıldı"))
            except queue.Empty:
                break

    def _exchange(self, cmd: str, timeout: float) -> list:
        self.log(f"> {cmd}")
        self.t.write((cmd + "\r").encode("ascii"))
        buf = b""
        end = time.time() + timeout
        while time.time() < end:
            chunk = self.t.read(0.05)
            if chunk:
                buf += chunk
                if b">" in buf:
                    break
        else:
            raise ElmTimeout(f"Zaman aşımı: {cmd}")
        text = buf.decode("ascii", "ignore")
        lines = clean_lines(text, cmd)
        for ln in lines:
            self.log(f"< {ln}")
        return lines

    def send_async(self, cmd: str, timeout: Optional[float] = None) -> Future:
        if not self._run:
            raise ElmError("Bağlı değil")
        fut: Future = Future()
        self.q.put((cmd, timeout or self.default_timeout, fut))
        return fut

    def send(self, cmd: str, timeout: Optional[float] = None) -> list:
        """Engelleyici komut gönderimi (yalnızca arka plan iş parçacıklarından çağırın)."""
        t = timeout or self.default_timeout
        return self.send_async(cmd, t).result(timeout=t + 5)

    # -- bağlantı
    def connect(self, protocol: str = "0") -> dict:
        self.t.open()
        self._run = True
        self._thread = threading.Thread(target=self._io_loop, daemon=True, name="elm-io")
        self._thread.start()
        try:
            r = self.send("ATZ", 6)
            self.version = next((l for l in r if "ELM" in l.upper() or "OBD" in l.upper()), "") or "ELM327"
            for c in ("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1"):
                self.send(c)
            self.send(f"ATSP{protocol}")
            self.connected = True
            ecu_ok = True
            try:
                self.supported = self._scan_supported()
            except NoData:
                ecu_ok = False
            except ElmError as e:
                self.log(f"! {e}")
                ecu_ok = False
            self._read_protocol()
            return {"version": self.version, "protocol": self.protocol, "ecu_ok": ecu_ok,
                    "voltage": self.voltage()}
        except Exception:
            self.disconnect()
            raise

    def _read_protocol(self):
        try:
            n = self.send("ATDPN")
            pn = (n[0] if n else "").strip().upper()
            self.protocol_num = pn[1:] if len(pn) == 2 and pn[0] == "A" else pn
            d = self.send("ATDP")
            self.protocol = (d[0] if d else "").replace("AUTO, ", "").strip()
        except ElmError:
            pass
        self.header_len = header_len_for(self.protocol_num or "6")

    def _scan_supported(self) -> set:
        masks = {}
        # protokol henüz bilinmiyor: önce 0100 ile tespit
        lines = self.send("0100", 12)
        self._raise_if_error(lines)
        self._read_protocol()
        base = 0
        while True:
            try:
                data = self._service(0x01, base, lines if base == 0 else None)
            except NegativeResponse:
                break
            except NoData:
                if base == 0:
                    raise
                break
            masks[base] = data[:4].ljust(4, b"\0")
            if not (masks[base][3] & 1):
                break
            base += 0x20
            if base > 0xC0:
                break
        return supported_from_masks(masks)

    def disconnect(self):
        self._run = False
        self.connected = False
        if self._thread:
            self._thread.join(timeout=2)
            self._thread = None
        self.t.close()

    # -- yardımcılar
    @staticmethod
    def _raise_if_error(lines):
        joined = " | ".join(lines).upper()
        if "NO DATA" in joined:
            raise NoData("Veri yok")
        for e in ERRORS:
            if e in joined:
                raise ElmError(e)
        if any(l.strip() == "?" for l in lines):
            raise ElmError("Komut anlaşılmadı (?)")

    def _service(self, mode: int, pid: Optional[int], lines=None, timeout=None) -> bytes:
        """Mod/PID isteği; pozitif yanıt verisini (mod+PID'den sonrasını) döndürür."""
        if lines is None:
            cmd = f"{mode:02X}" + (f"{pid:02X}" if pid is not None else "")
            lines = self.send(cmd, timeout)
        self._raise_if_error(lines)
        for hdr, data in parse_frames(lines, self.header_len):
            if data and data[0] == 0x40 + mode:
                if pid is None:
                    return data[1:]
                if len(data) > 1 and data[1] == pid:
                    return data[2:]
            if len(data) >= 3 and data[0] == 0x7F and data[1] == mode:
                raise NegativeResponse(mode, data[2])
        raise NoData("Beklenen yanıt yok")

    # -- yüksek seviye
    def voltage(self) -> Optional[float]:
        with self.lock:
            try:
                r = self.send("ATRV")
                return float(re.sub(r"[^0-9.]", "", r[0])) if r else None
            except (ElmError, ValueError):
                return None

    def read_pid(self, pid: Pid) -> Optional[float]:
        with self.lock:
            try:
                data = self._service(0x01, pid.pid)
            except NoData:
                return None
            if len(data) < pid.nbytes:
                return None
            return pid.decode(data)

    def read_dtcs(self, mode: int) -> list:
        """mode: 0x03 kayıtlı, 0x07 bekleyen, 0x0A kalıcı."""
        with self.lock:
            try:
                lines = self.send(f"{mode:02X}", 6)
                self._raise_if_error(lines)
            except NoData:
                return []
            codes = []
            for hdr, data in parse_frames(lines, self.header_len):
                if data and data[0] == 0x40 + mode:
                    codes += decode_dtc_list(data[1:], self.is_can)
            return list(dict.fromkeys(codes))

    def clear_dtcs(self) -> bool:
        with self.lock:
            lines = self.send("04", 6)
            return any("44" in l.replace(" ", "") for l in lines)

    def vehicle_info(self) -> dict:
        info = {}
        with self.lock:
            def text(data: bytes) -> str:
                return data.replace(b"\0", b"").decode("ascii", "ignore").strip()
            try:
                d = self._service(0x09, 0x02)
                info["VIN"] = text(d[1:] if d and d[0] == 1 else d)
            except NegativeResponse:
                info["VIN"] = "ECU desteklemiyor"
            except ElmError:
                info["VIN"] = ""
            for pid, name in ((0x04, "Kalibrasyon ID"), (0x06, "CVN"), (0x0A, "ECU adı")):
                try:
                    d = self._service(0x09, pid)
                    info[name] = d[1:].hex().upper() if pid == 0x06 else text(d[1:])
                except ElmError:
                    info[name] = ""
        info["ELM sürümü"] = self.version
        info["Protokol"] = self.protocol
        v = self.voltage()
        info["Akü voltajı"] = f"{v:.1f} V" if v else ""
        info["Desteklenen PID sayısı"] = str(len(self.supported))
        return info

    # -- UDS / üretici
    def _set_ecu(self, tx: str, rx: str):
        self.send(f"ATSH{tx}")
        self.send(f"ATCRA{rx}" if rx else "ATAR")

    def _restore(self):
        try:
            self.send("ATSH7DF")
            self.send("ATAR")
        except ElmError:
            pass

    def uds_request(self, ecu: dict, payload: bytes, timeout: float = 4.0) -> bytes:
        """ECU'ya UDS isteği gönderir; olumlu yanıt verisini döndürür (başlık/PCI yok)."""
        with self.lock:
            try:
                self._set_ecu(ecu["tx"], ecu["rx"])
                sess = (ecu.get("session") or "").replace(" ", "")
                if sess:
                    try:
                        self.send(sess, timeout)
                    except ElmError:
                        pass
                lines = self.send(payload.hex().upper(), timeout)
                self._raise_if_error(lines)
                for hdr, data in parse_frames(lines, self.header_len):
                    if not data:
                        continue
                    if data[0] == 0x7F and len(data) >= 3:
                        if data[2] == 0x78:
                            continue
                        raise NegativeResponse(data[1], data[2])
                    if data[0] == payload[0] + 0x40:
                        return data
                raise NoData("ECU yanıt vermedi")
            finally:
                self._restore()

    def read_did(self, ecu: dict, did: int, service: int = 0x22) -> bytes:
        """Servis 22 (2 baytlık DID) veya KWP 21 (1 baytlık yerel ID) okur."""
        if service == 0x21:
            return self.uds_request(ecu, bytes([0x21, did & 0xFF]))[2:]
        d = self.uds_request(ecu, bytes([service, did >> 8, did & 0xFF]))
        return d[3:]

    # -- keşif araçları
    @contextmanager
    def ecu_context(self, ecu: dict, session: bool = False):
        """ATSH/ATCRA'yı kurar, bloğun sonunda varsayılana döndürür."""
        with self.lock:
            try:
                self._set_ecu(ecu["tx"], ecu.get("rx", ""))
                sess = (ecu.get("session") or "").replace(" ", "")
                if session and sess:
                    try:
                        self.send(sess, 3)
                    except ElmError:
                        pass
                yield self
            finally:
                self._restore()

    def raw_request(self, payload: bytes, timeout: float = 1.5) -> Optional[bytes]:
        """ecu_context içinde ham istek; pozitif/negatif çerçeveyi döndürür, yanıt yoksa None."""
        try:
            lines = self.send(payload.hex().upper(), timeout)
        except ElmTimeout:
            return None
        try:
            self._raise_if_error(lines)
        except ElmError:
            return None
        for hdr, data in parse_frames(lines, self.header_len):
            if data and not (data[0] == 0x7F and len(data) >= 3 and data[2] == 0x78):
                return data
        return None

    def read_uds_dtcs(self, ecu: dict) -> list:
        """UDS 19 02 FF: [(kod, durum_baytı)]. 3 baytlık DTC -> P0100-1A biçimi."""
        d = self.uds_request(ecu, bytes([0x19, 0x02, 0xFF]))
        rec = d[3:]
        out = []
        for i in range(0, len(rec) - 3, 4):
            b1, b2, ft, st = rec[i:i + 4]
            out.append((f"{decode_dtc(b1, b2)}-{ft:02X}", st))
        return out
