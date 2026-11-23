"""GUI'nin başsız (offscreen) oluşturulup demo modunda çalıştığını doğrular."""
import os
import sys
import tempfile
import time

os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["APPDATA"] = tempfile.mkdtemp()
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.ui.theme import QSS  # noqa: E402
from app.ui.main_window import MainWindow  # noqa: E402


def pump(app, sec):
    end = time.time() + sec
    while time.time() < end:
        app.processEvents()
        time.sleep(0.02)


def main():
    app = QApplication([])
    app.setStyleSheet(QSS)
    w = MainWindow()
    w.show()
    conn = w.pages["Bağlantı"]
    conn.radios["demo"].setChecked(True)
    conn.toggle()
    pump(app, 4)
    assert w.state.connected, "demo bağlantısı kurulamadı"
    dash = w.pages["Panom"]
    vals = [c.value.text() for c in dash.cards.values()]
    print("Panom kartları:", vals)
    assert any(v != "—" for v in vals), "kartlara değer gelmedi"
    assert w.state.mfr_reachable is False, "gerçek araç profilinde üretici ECU erişilemez olmalı"
    # sayfaları gez
    for i in range(w.stack.count()):
        w.nav.setCurrentRow(i)
        pump(app, 0.3)
    w.pages["Rapor"].refresh()
    from app import report
    report.save_pdf(os.path.join(tempfile.mkdtemp(), "t.pdf"), w.pages["Rapor"].html())
    w.close()
    print("GUI OK")


main()
