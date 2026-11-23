"""Standart Mod 01 PID tanımları ve birleşik değer kataloğu."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from .safe_eval import compile_formula, FormulaError

CATEGORIES = ["Motor", "Yakıt", "Hava / Turbo", "Sıcaklık", "DPF / Emisyon", "Elektrik", "Araç", "Diğer"]


@dataclass
class Pid:
    pid: int
    key: str
    name: str
    unit: str
    nbytes: int
    formula: str
    vmin: float
    vmax: float
    category: str
    warn_hi: Optional[float] = None   # sarı üst eşik
    crit_hi: Optional[float] = None   # kırmızı üst eşik
    warn_lo: Optional[float] = None
    crit_lo: Optional[float] = None
    decimals: int = 0
    _fn: object = field(default=None, repr=False)

    def decode(self, data: bytes) -> float:
        if self._fn is None:
            self._fn = compile_formula(self.formula)
        return self._fn(data)


PIDS = [
    Pid(0x04, "load", "Motor Yükü", "%", 1, "A*100/255", 0, 100, "Motor", 85, 95),
    Pid(0x05, "coolant", "Soğutma Suyu", "°C", 1, "A-40", -40, 130, "Sıcaklık", 105, 112, 60, 0),
    Pid(0x0B, "map", "Manifold Basıncı (MAP)", "kPa", 1, "A", 0, 255, "Hava / Turbo", 230, 250),
    Pid(0x0C, "rpm", "Motor Devri", "d/dk", 2, "(A*256+B)/4", 0, 5000, "Motor", 4200, 4800),
    Pid(0x0D, "speed", "Araç Hızı", "km/s", 1, "A", 0, 200, "Araç"),
    Pid(0x0F, "iat", "Emme Havası", "°C", 1, "A-40", -40, 100, "Sıcaklık", 60, 80),
    Pid(0x10, "maf", "Hava Debisi (MAF)", "g/s", 2, "(A*256+B)/100", 0, 100, "Hava / Turbo", decimals=1),
    Pid(0x11, "throttle", "Gaz Kelebeği / Pedal", "%", 1, "A*100/255", 0, 100, "Motor"),
    Pid(0x1F, "runtime", "Motor Çalışma Süresi", "sn", 2, "A*256+B", 0, 65535, "Motor"),
    Pid(0x21, "dist_mil", "Arıza Lambası ile Mesafe", "km", 2, "A*256+B", 0, 65535, "Araç"),
    Pid(0x23, "frp", "Yakıt Ray Basıncı", "kPa", 2, "10*(A*256+B)", 0, 250000, "Yakıt", 190000, 210000),
    Pid(0x2F, "fuel_level", "Yakıt Seviyesi", "%", 1, "A*100/255", 0, 100, "Yakıt", None, None, 15, 8),
    Pid(0x33, "baro", "Barometrik Basınç", "kPa", 1, "A", 0, 255, "Hava / Turbo"),
    Pid(0x42, "vmod", "Akü / Modül Voltajı", "V", 2, "(A*256+B)/1000", 0, 16, "Elektrik", 15.0, 15.8, 12.0, 11.4, 2),
    Pid(0x46, "ambient", "Dış Sıcaklık", "°C", 1, "A-40", -40, 60, "Sıcaklık"),
    Pid(0x5C, "oil_temp", "Yağ Sıcaklığı", "°C", 1, "A-40", -40, 150, "Sıcaklık", 120, 135, 50, 0),
]

BY_PID = {p.pid: p for p in PIDS}
BY_KEY = {p.key: p for p in PIDS}
BATTERY_KEY = "atrv"


def supported_from_masks(masks: dict) -> set:
    """masks: {0x00: bytes4, 0x20: bytes4, ...} -> desteklenen PID kümesi."""
    out = set()
    for base, data in masks.items():
        for i in range(32):
            if data[i // 8] & (0x80 >> (i % 8)):
                out.add(base + i + 1)
    return out


@dataclass
class Item:
    """Panom/Tüm Değerler için birleşik değer girdisi (standart PID veya PSA DID)."""
    key: str
    name: str
    unit: str
    category: str
    source: str            # "std" | "psa"
    vmin: float = 0
    vmax: float = 100
    warn_hi: Optional[float] = None
    crit_hi: Optional[float] = None
    warn_lo: Optional[float] = None
    crit_lo: Optional[float] = None
    decimals: int = 1
    ecu: str = ""
    ref: object = None     # Pid veya DID sözlüğü
    note: str = ""
    confidence: str = ""
    supported: bool = True


def status_of(item: Item, value) -> str:
    """'ok' | 'warn' | 'crit' | 'none' (yeşil/sarı/kırmızı/gri)."""
    if value is None:
        return "none"
    if (item.crit_hi is not None and value >= item.crit_hi) or \
       (item.crit_lo is not None and value <= item.crit_lo):
        return "crit"
    if (item.warn_hi is not None and value >= item.warn_hi) or \
       (item.warn_lo is not None and value <= item.warn_lo):
        return "warn"
    return "ok"


def guess_category(name: str, unit: str) -> str:
    n = name.lower()
    table = [("dpf", "DPF / Emisyon"), ("partik", "DPF / Emisyon"), ("egr", "DPF / Emisyon"),
             ("adblue", "DPF / Emisyon"), ("nox", "DPF / Emisyon"), ("rail", "Yakıt"),
             ("yakıt", "Yakıt"), ("enjekt", "Yakıt"), ("turbo", "Hava / Turbo"), ("hava", "Hava / Turbo"),
             ("emme", "Hava / Turbo"), ("sıcak", "Sıcaklık"), ("su ", "Sıcaklık"), ("akü", "Elektrik"),
             ("volt", "Elektrik"), ("devir", "Motor"), ("rölanti", "Motor"), ("hız", "Araç"),
             ("km", "Araç")]
    for k, c in table:
        if k in n:
            return c
    if unit in ("°C", "C"):
        return "Sıcaklık"
    if unit in ("V", "mV"):
        return "Elektrik"
    if unit in ("bar", "kPa", "hPa"):
        return "Hava / Turbo"
    return "Diğer"


def build_catalog(store, supported: Optional[set] = None) -> list:
    """Standart PID'ler + PSA DID'lerinden birleşik katalog üretir."""
    items = []
    for p in PIDS:
        if supported is not None and p.pid not in supported:
            continue
        items.append(Item(p.key, p.name, p.unit, p.category, "std", p.vmin, p.vmax, p.warn_hi, p.crit_hi,
                          p.warn_lo, p.crit_lo, p.decimals, "Motor (OBD2)", p))
    items.append(Item(BATTERY_KEY, "Akü Voltajı (adaptör)", "V", "Elektrik", "std", 8, 16, 15.0, 15.8,
                      12.0, 11.4, 2, "Adaptör", None))
    if store is not None:
        for d in store.dids:
            if not d.get("numeric", True):
                continue
            items.append(Item(d["key"], d["name"], d.get("unit", ""), guess_category(d["name"], d.get("unit", "")),
                              "psa", float(d.get("min", 0)), float(d.get("max", 100)), decimals=1,
                              ecu=d["ecu"], ref=d, note=d.get("source", ""), confidence=str(d.get("confidence", ""))))
    return items


def is_low_conf(confidence: str) -> bool:
    return str(confidence or "").strip().lower() in ("low", "düşük", "dusuk", "unverified", "doğrulanmamış")
