"""Kalıcı kullanıcı ayarları (%APPDATA%\\Rotiva\\settings.json): Panom seçimi, bağlantı tercihleri."""
from __future__ import annotations

import json
import os


def settings_path() -> str:
    from .paths import data_dir
    return os.path.join(data_dir(), "settings.json")


DEFAULTS = {"dashboard": ["rpm", "speed", "coolant", "load", "frp", "atrv"], "conn": {}, "theme": "Gece Mavisi"}


class Settings:
    def __init__(self, path: str = None):
        self.path = path or settings_path()
        self.data = json.loads(json.dumps(DEFAULTS))
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass

    def save(self):
        try:
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=2)
        except OSError:
            pass

    @property
    def dashboard(self) -> list:
        return self.data["dashboard"]

    def set_dashboard(self, keys: list):
        self.data["dashboard"] = list(dict.fromkeys(keys))
        self.save()

    @property
    def theme(self) -> str:
        return self.data.get("theme") or "Gece Mavisi"

    def set_theme(self, name: str):
        self.data["theme"] = name
        self.save()

    def get(self, k, default=None):
        return self.data.get(k, default)

    def put(self, k, v):
        self.data[k] = v
        self.save()
