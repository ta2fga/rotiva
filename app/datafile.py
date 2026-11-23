"""Veri dosyaları (psa_data.json, dtc_generic.json) yükleme ve birleştirme."""
from __future__ import annotations

import json
import os
import sys
from typing import Optional

from .safe_eval import compile_formula, FormulaError


def base_dir() -> str:
    if getattr(sys, "frozen", False):
        return getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def data_path(name: str) -> str:
    return os.path.join(base_dir(), "data", name)


def _load(name: str):
    try:
        with open(data_path(name), "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def user_file() -> str:
    d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "OBDTani")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "user_dids.json")


def _load_user() -> dict:
    try:
        with open(user_file(), "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_user_did(did: dict, ecu: Optional[dict] = None):
    """Keşfedilen bir DID'i (ve gerekirse ECU'yu) kullanıcı veri dosyasına ekler."""
    u = _load_user()
    u.setdefault("dids", []).append(did)
    if ecu:
        ecus = u.setdefault("ecus", [])
        if not any(e.get("name") == ecu["name"] for e in ecus):
            ecus.append(ecu)
    with open(user_file(), "w", encoding="utf-8") as f:
        json.dump(u, f, ensure_ascii=False, indent=2)


def _hex(v, default=0) -> int:
    if isinstance(v, int):
        return v
    try:
        return int(str(v).strip().lower().replace("0x", ""), 16)
    except ValueError:
        return default


FALLBACK = {
    "ecus": [
        {"name": "Motor ECU", "tx": "7E0", "rx": "7E8", "protocol": "CAN 11/500", "session": "10 03"},
        {"name": "BSI", "tx": "752", "rx": "652", "protocol": "CAN 11/500", "session": "10 03"},
    ],
    "dids": [
        {"ecu": "Motor ECU", "name": "Örnek: Motor devri (doğrulanmamış)", "service": "22", "did": "F40C",
         "formula": "(A*256+B)/4", "unit": "d/dk", "source": "yerleşik örnek", "confidence": "düşük"},
    ],
    "dtcs": {},
}


class DataStore:
    def __init__(self):
        self.ecus: list = []
        self.dids: list = []
        self.dtcs: dict = {}
        self.psa_loaded = False
        self.warnings: list = []
        self._load_all()
        self._merge_user()

    def _load_all(self):
        generic = _load("dtc_generic.json") or {}
        psa = _load("psa_data.json")
        if psa is None:
            psa = FALLBACK
            self.warnings.append("psa_data.json bulunamadı; yerleşik örnek veri kullanılıyor.")
        else:
            self.psa_loaded = True
        self.dtcs = {**{k.upper(): v for k, v in generic.items()},
                     **{k.upper(): v for k, v in (psa.get("dtcs") or {}).items()}}
        self.ecus = []
        for e in psa.get("ecus", []):
            self.ecus.append({"name": e.get("name", "?"), "tx": str(e.get("tx", "7E0")).upper(),
                              "rx": str(e.get("rx", "")).upper(), "protocol": e.get("protocol", ""),
                              "session": e.get("session", "") or ""})
        names = {e["name"] for e in self.ecus}
        self.dids = []
        for i, d in enumerate(psa.get("dids", [])):
            try:
                fn = compile_formula(d.get("formula", ""))
            except FormulaError as e:
                self.warnings.append(f"DID '{d.get('name')}' formülü reddedildi: {e}")
                continue
            if d.get("ecu") not in names:
                self.warnings.append(f"DID '{d.get('name')}' bilinmeyen ECU: {d.get('ecu')}")
                continue
            did = _hex(d.get("did"), -1)
            if did < 0:
                continue
            self.dids.append({
                "key": f"psa{i}", "ecu": d["ecu"], "name": d.get("name", f"DID {did:04X}"),
                "service": _hex(d.get("service", "22"), 0x22), "did": did, "formula": d.get("formula"),
                "unit": d.get("unit", ""), "source": d.get("source", ""),
                "confidence": d.get("confidence", ""), "fn": fn,
                "min": d.get("min", 0), "max": d.get("max", 100), "numeric": True})

    def _merge_user(self):
        u = _load_user()
        names = {e["name"] for e in self.ecus}
        for e in u.get("ecus", []):
            if e.get("name") and e["name"] not in names:
                self.ecus.append({"name": e["name"], "tx": str(e.get("tx", "")).upper(),
                                  "rx": str(e.get("rx", "")).upper(), "protocol": e.get("protocol", ""),
                                  "session": e.get("session", "") or ""})
                names.add(e["name"])
        n0 = len(self.dids)
        for j, d in enumerate(u.get("dids", [])):
            try:
                fn = compile_formula(d.get("formula", "A"))
            except FormulaError:
                continue
            if d.get("ecu") not in names:
                continue
            self.dids.append({
                "key": f"usr{j}", "ecu": d["ecu"], "name": d.get("name", "Kullanıcı DID"),
                "service": _hex(d.get("service", "22"), 0x22), "did": _hex(d.get("did"), 0),
                "formula": d.get("formula", "A"), "unit": d.get("unit", ""),
                "source": "kullanıcı (keşif)", "confidence": d.get("confidence", "düşük"),
                "fn": fn, "min": d.get("min", 0), "max": d.get("max", 100), "numeric": True})

    def ecu(self, name: str) -> Optional[dict]:
        return next((e for e in self.ecus if e["name"] == name), None)

    def describe(self, code: str) -> str:
        c = code.upper()
        return self.dtcs.get(c) or self.dtcs.get(c.split("-")[0]) or "Açıklama bulunamadı"
