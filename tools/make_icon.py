"""assets/app.ico dosyasını kodla üretir (PyInstaller simgesi için)."""
import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, root)
from PySide6.QtGui import QGuiApplication  # noqa: E402
from app.ui.icon import write_ico  # noqa: E402

app = QGuiApplication([])
os.makedirs(os.path.join(root, "assets"), exist_ok=True)
write_ico(os.path.join(root, "assets", "app.ico"))
print("assets/app.ico yazıldı")
