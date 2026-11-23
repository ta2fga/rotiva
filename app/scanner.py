"""Keşif / ECU tarayıcı motoru (arayüzden bağımsız)."""
from __future__ import annotations

from typing import Callable, Optional

from .elm327 import ELM327, nrc_text

COMMON_PAIRS = [
    ("7E0 / 7E8 (OBD motor)", "7E0", "7E8"),
    ("BSI 752 / 652", "752", "652"),
    ("Motor ECU 6A8 / 688", "6A8", "688"),
    ("6A7 / 687", "6A7", "687"),
    ("6AD / 68D", "6AD", "68D"),
    ("6B5 / 695", "6B5", "695"),
]
PROBES = [("10 03", bytes([0x10, 0x03])), ("10 C0", bytes([0x10, 0xC0])), ("3E 00", bytes([0x3E, 0x00]))]


MIN_SAFE_TX = 0x600


def is_safe_tx(tx: str) -> bool:
    """Güvenlik: 0x600 altındaki CAN kimlikleri araçta normal işletim mesajlarıyla
    çakışabilir. Bunlara teşhis paketi göndermek ağdaki gerçek mesajları bozar ve
    BSI/gösterge'de 'motor arızası' gibi uyarılara yol açabilir (gerçek araçta görüldü).
    Bu yüzden yalnızca 0x600-0x7FF teşhis aralığına izin verilir."""
    try:
        return MIN_SAFE_TX <= int(tx, 16) <= 0x7FF
    except (TypeError, ValueError):
        return False


def candidate_pairs(store) -> list:
    """psa_data.json ECU'ları + yaygın PSA çiftleri (yinelenenler elenir, güvensiz kimlikler atlanır)."""
    out, seen = [], set()
    for e in store.ecus:
        k = (e["tx"], e["rx"])
        if not is_safe_tx(e["tx"]):
            continue
        if k not in seen:
            seen.add(k)
            out.append({"name": e["name"], "tx": e["tx"], "rx": e["rx"], "session": e.get("session", ""),
                        "protocol": e.get("protocol", ""), "source": "veri dosyası"})
    for name, tx, rx in COMMON_PAIRS:
        if (tx, rx) not in seen:
            seen.add((tx, rx))
            out.append({"name": name, "tx": tx, "rx": rx, "session": "", "protocol": "", "source": "yaygın çift"})
    return out


def scan_ecus(elm: ELM327, pairs: list, progress: Callable[[int, int, str], None] = None,
              stop: Callable[[], bool] = lambda: False) -> list:
    """Her çifte 10 03 / 10 C0 / 3E 00 gönderir. [{pair, responses:{probe: hex}, alive}]"""
    res = []
    for i, p in enumerate(pairs):
        if stop():
            break
        if progress:
            progress(i, len(pairs), f"{p['tx']} -> {p['rx']}")
        if not is_safe_tx(p["tx"]):
            res.append({"pair": p, "responses": {}, "alive": False, "skipped": "güvenli aralık dışı (<0x600)"})
            continue
        got = {}
        with elm.ecu_context({"tx": p["tx"], "rx": p["rx"]}):
            for label, payload in PROBES:
                r = elm.raw_request(payload, 1.0)
                if r is not None:
                    got[label] = r.hex().upper()
        res.append({"pair": p, "responses": got, "alive": bool(got)})
    if progress:
        progress(len(pairs), len(pairs), "bitti")
    return res


def classify(resp_hex: str) -> str:
    b = bytes.fromhex(resp_hex)
    if b[0] == 0x7F and len(b) >= 3:
        return f"Negatif 0x{b[2]:02X}: {nrc_text(b[2])}"
    return "Olumlu"


def scan_ids(elm: ELM327, ecu: dict, service: int, ids: range,
             progress: Callable[[int, int, str], None] = None,
             stop: Callable[[], bool] = lambda: False,
             on_hit: Optional[Callable[[int, str, bool], None]] = None,
             only_positive: bool = False) -> list:
    """Servis 21 (yerel ID 00-FF) veya 22 (DID) aralığını tarar. [(id, hex, olumlu)]"""
    hits, total = [], len(ids)
    if not is_safe_tx(ecu.get("tx", "")):
        raise ValueError("Güvenlik: yalnızca 0x600-0x7FF teşhis kimliklerine istek gönderilebilir.")
    with elm.ecu_context(ecu, session=True):
        for n, i in enumerate(ids):
            if stop():
                break
            if progress and n % 4 == 0:
                progress(n, total, f"{i:04X}" if service == 0x22 else f"{i:02X}")
            payload = bytes([0x21, i & 0xFF]) if service == 0x21 else bytes([0x22, i >> 8, i & 0xFF])
            r = elm.raw_request(payload, 1.2)
            if r is None:
                continue
            pos = r[0] == service + 0x40
            if only_positive and not pos:
                continue
            hits.append((i, r.hex().upper(), pos))
            if on_hit:
                on_hit(i, r.hex().upper(), pos)
    if progress:
        progress(total, total, "bitti")
    return hits


def parse_range(text: str, service: int) -> range:
    """'F180-F1A0' veya 'D400' -> range. Servis 21 için 00-FF kabul edilir."""
    text = text.strip().replace(" ", "")
    lo, _, hi = text.partition("-")
    a = int(lo, 16)
    b = int(hi, 16) if hi else a
    if b < a:
        a, b = b, a
    lim = 0xFF if service == 0x21 else 0xFFFF
    if b > lim:
        raise ValueError(f"Aralık en fazla {lim:X} olabilir")
    if b - a > 0x1FFF:
        raise ValueError("Aralık çok geniş (en çok 8192 kimlik)")
    return range(a, b + 1)


def probe_manufacturer(elm: ELM327, store, stop: Callable[[], bool] = lambda: False) -> bool:
    """Standart OBD adresi (7E0-7E7) dışındaki herhangi bir ECU herhangi bir yanıt verirse True."""
    for p in candidate_pairs(store):
        if stop():
            break
        if p["tx"].startswith("7E"):
            continue
        with elm.ecu_context({"tx": p["tx"], "rx": p["rx"]}):
            if elm.raw_request(bytes([0x3E, 0x00]), 0.8) is not None:
                return True
    return False
