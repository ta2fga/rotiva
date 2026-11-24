"""GUI'nin başsız (offscreen) oluşturulup demo modunda çalıştığını doğrular."""
import os
import sys
import tempfile
import time

os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["APPDATA"] = tempfile.mkdtemp()
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.ui import theme  # noqa: E402
from app.ui.theme import QSS  # noqa: E402
from app.ui.main_window import MainWindow  # noqa: E402


def pump(app, sec):
    end = time.time() + sec
    while time.time() < end:
        app.processEvents()
        time.sleep(0.02)


def wait_for(app, cond, sec, msg):
    end = time.time() + sec
    while time.time() < end and not cond():
        app.processEvents()
        time.sleep(0.02)
    assert cond(), msg


def drive_test(app, w):
    """Kısa simüle sürüş: otomatik başlat -> sürüş modu -> bağlantı kopması/devam -> bitir -> liste/rapor."""
    from app import report
    from app.trip import read_samples
    ctl, page = w.state.trip, w.pages["Sürüş"]
    w.nav.setCurrentRow(list(w.pages).index("Sürüş"))
    assert ctl.auto_start
    wait_for(app, lambda: ctl.active, 25, "hız > 5 km/s olunca sürüş otomatik başlamadı")
    start_ts = ctl.rec.start_ts
    pump(app, 4)
    n = len(ctl.rec.samples)
    assert n >= 15, n
    assert ctl.live["speed"] is not None and ctl.live["rpm"] is not None
    page.enter_drive_mode()
    pump(app, 1)
    assert page.mode.speed.text() != "—" and page.mode.gear.text() in "N—12345"
    w.grab()
    # bağlantı kopması: kayıt duraklar, otomatik yeniden bağlanır, aynı sürüşe devam eder
    ctl.retry_s = 1.0
    w.state.mark_lost()
    assert ctl.rec.paused and ctl.status == "lost" and ctl.active
    assert "yeniden bağlanılıyor" in page.mode.status.text(), page.mode.status.text()
    wait_for(app, lambda: w.state.connected and not w.state.link_lost and not ctl.rec.paused, 25,
             "otomatik yeniden bağlanıp kayda devam edemedi")
    assert ctl.active and ctl.rec.start_ts == start_ts and len(ctl.rec.pauses) >= 1
    assert ctl.status == ""
    pump(app, 3)
    assert len(ctl.rec.samples) > n
    page.exit_drive_mode()
    page.finish_trip(confirm=False)
    assert not ctl.active
    assert page.table.rowCount() == 1, page.table.rowCount()
    j = page.rows[0]
    assert j["total"] is not None and j["tips"] and j["subs"], j
    assert page.score_lbl.text() == str(j["total"])
    S = read_samples(os.path.join(os.path.dirname(j["_json"]), j["csv"]))
    assert len(S) >= 15
    html = report.build_trip_html(j, S)
    assert "Sürüş Raporu" in html and "<svg" in html
    # oran ve sayaç uzunlukları farklı (eski/yarışlı kayıt): rapor çökmez, eksik sayaç "tahmin"
    j2 = dict(j, ratios=[9.0, 15.6, 23.4, 31.2, 39.0, 46.8], gear_counts=[50, 50, 50, 50, 50])
    assert "6. vites 46.8 (tahmin)" in report.build_trip_html(j2, S)
    w.grab()
    print("sürüş testi OK:", j["total"], len(S), "örnek")


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
    # tüm temaları gez
    from app.settings import Settings
    for name in theme.THEME_NAMES:
        w.theme_box.setCurrentText(name)
        assert theme.CURRENT == name and w.state.settings.theme == name
        assert theme.STATUS_COLOR["ok"] == theme.PALETTES[name]["ok"]
        for i in range(w.stack.count()):
            w.nav.setCurrentRow(i)
            pump(app, 0.1)
        w.grab()
        print("tema:", name)
    assert Settings(w.state.settings.path).theme == theme.THEME_NAMES[-1], "tema kalıcı değil"
    w.theme_box.setCurrentText(theme.DEFAULT_THEME)
    drive_test(app, w)
    w.pages["Rapor"].refresh()
    from app import report
    report.save_pdf(os.path.join(tempfile.mkdtemp(), "t.pdf"), w.pages["Rapor"].html())
    w.close()
    print("GUI OK")


main()
