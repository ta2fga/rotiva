"""Rotiva kullanıcı veri klasörü: %APPDATA%/Rotiva (eski OBDTani klasörü ilk açılışta taşınır)."""
from __future__ import annotations

import os
import shutil

FOLDER = "Rotiva"
OLD_FOLDER = "OBDTani"


def data_dir() -> str:
    root = os.environ.get("APPDATA") or os.path.expanduser("~")
    new, old = os.path.join(root, FOLDER), os.path.join(root, OLD_FOLDER)
    if not os.path.exists(new) and os.path.isdir(old):
        try:
            os.rename(old, new)
        except OSError:
            try:
                shutil.copytree(old, new)
            except OSError:
                pass
    os.makedirs(new, exist_ok=True)
    return new
