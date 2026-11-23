"""Ana pencere: kenar çubuğu gezinme + sayfalar."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QPixmap, QPainter, QColor, QIcon
from PySide6.QtWidgets import (QMainWindow, QWidget, QHBoxLayout, QVBoxLayout, QListWidget, QListWidgetItem,
                               QStackedWidget, QLabel, QPushButton, QMessageBox, QComboBox, QApplication)

from .. import APP_NAME, APP_SUBTITLE, APP_VERSION
from ..state import AppState
from . import theme
from .dialogs import show_about
from .widgets import ValueCard
from .icon import app_icon
from .page_connection import ConnectionPage
from .page_dtc import DtcPage
from .page_drive import DrivePage
from .page_misc import VehiclePage, LogPage, ReportPage
from .page_psa import PsaPage
from .page_scanner import ScannerPage
from .page_values import DashboardPage, AllValuesPage, LivePage


class MainWindow(QMainWindow):
    def __init__(self, state: AppState = None):
        super().__init__()
        self.state = state or AppState()
        theme.apply(self.state.settings.theme)
        if QApplication.instance():
            QApplication.instance().setStyleSheet(theme.QSS)
        self.setWindowTitle(f"{APP_NAME} {APP_VERSION}")
        self.setWindowIcon(app_icon())
        self.resize(1280, 800)
        self.setMinimumSize(980, 640)
        root = QWidget()
        h = QHBoxLayout(root)
        h.setContentsMargins(0, 0, 0, 0)
        h.setSpacing(0)
        side = QWidget()
        side.setObjectName("sidebar")
        side.setFixedWidth(230)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(0, 0, 0, 12)
        sl.setSpacing(0)
        b = QLabel(APP_NAME)
        b.setObjectName("brand")
        sl.addWidget(b)
        sub = QLabel(APP_SUBTITLE)
        sub.setObjectName("brandSub")
        sl.addWidget(sub)
        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        sl.addWidget(self.nav, 1)
        tl = QLabel("Tema")
        tl.setObjectName("muted")
        tl.setContentsMargins(16, 0, 16, 2)
        sl.addWidget(tl)
        self.theme_box = QComboBox()
        self.theme_box.setObjectName("themeBox")
        self.theme_box.setContentsMargins(0, 0, 0, 0)
        for n in theme.THEME_NAMES:
            self.theme_box.addItem(self._swatch(theme.PALETTES[n]), n)
        self.theme_box.setCurrentText(theme.CURRENT)
        self.theme_box.currentTextChanged.connect(self.set_theme)
        tw = QWidget()
        twl = QHBoxLayout(tw)
        twl.setContentsMargins(10, 0, 10, 6)
        twl.addWidget(self.theme_box)
        sl.addWidget(tw)
        ab = QPushButton("Hakkında")
        ab.setObjectName("flat")
        ab.clicked.connect(lambda: show_about(self))
        sl.addWidget(ab)
        h.addWidget(side)
        self.stack = QStackedWidget()
        h.addWidget(self.stack, 1)
        self.setCentralWidget(root)

        s = self.state
        pages = [("🔌", "Bağlantı", ConnectionPage), ("📊", "Panom", DashboardPage),
                 ("📋", "Tüm Değerler", AllValuesPage), ("📈", "Canlı Grafik", LivePage), ("🛣", "Sürüş", DrivePage),
                 ("⚠", "Arıza Kodları", DtcPage), ("🔧", "PSA Özel", PsaPage),
                 ("🧭", "Keşif / ECU", ScannerPage), ("🚗", "Araç Bilgisi", VehiclePage),
                 ("💾", "Kayıt", LogPage), ("📄", "Rapor", ReportPage)]
        self.pages = {}
        for ic, name, cls in pages:
            w = cls(s)
            self.pages[name] = w
            self.stack.addWidget(w)
            it = QListWidgetItem(f"{ic}   {name}")
            self.nav.addItem(it)
        self.nav.currentRowChanged.connect(self.stack.setCurrentIndex)
        self.nav.setCurrentRow(0)
        self.dot = QLabel("● Bağlı değil")
        self.statusBar().addPermanentWidget(self.dot)
        s.connection_changed.connect(self._conn)
        s.mfr_changed.connect(self._conn_refresh)

    @staticmethod
    def _swatch(pal) -> QIcon:
        """Temanın zemin + vurgu renklerini gösteren küçük renk örneği."""
        pm = QPixmap(28, 16)
        pm.fill(Qt.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        p.setPen(QColor(128, 128, 128))
        p.setBrush(QColor(pal["bg"]))
        p.drawRoundedRect(0, 0, 27, 15, 4, 4)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(pal["accent"]))
        p.drawRoundedRect(14, 2, 11, 11, 3, 3)
        p.end()
        return QIcon(pm)

    def set_theme(self, name: str):
        """Temayı anında uygular (yeniden başlatma gerekmez) ve ayarlara kaydeder."""
        name = theme.apply(name)
        app = QApplication.instance()
        if app:
            app.setStyleSheet(theme.QSS)
        st = getattr(self.state, "settings", None)
        if st is not None and st.theme != name:
            st.set_theme(name)
        if self.theme_box.currentText() != name:
            self.theme_box.blockSignals(True)
            self.theme_box.setCurrentText(name)
            self.theme_box.blockSignals(False)
        for pg in self.pages.values():
            for attr in ("mfr_banner",):
                if hasattr(pg, attr):
                    getattr(pg, attr).setStyleSheet(theme.banner_style())
            if hasattr(pg, "banner"):
                pg.banner.setStyleSheet(theme.banner_style("warn"))
            for c in pg.findChildren(ValueCard):
                c.restyle()
            if hasattr(pg, "restyle"):
                pg.restyle()
            pg.update()
            for ch in pg.findChildren(QWidget):
                if ch.__class__.__name__ == "LineChart":
                    ch.update()
        self._conn_refresh()

    def _conn(self, ok):
        self._conn_refresh()
        if ok and not self.state.reconnecting:
            self.nav.setCurrentRow(1)

    def _conn_refresh(self):
        s = self.state
        if s.connected:
            mode = "Demo" if s.demo else (s.elm.protocol or "Bağlı")
            self.dot.setText(f"● Bağlı ({mode})")
            self.dot.setStyleSheet(f"color:{theme.OK};")
        else:
            self.dot.setText("● Bağlı değil")
            self.dot.setStyleSheet(f"color:{theme.MUTED};")

    def closeEvent(self, e):
        self.state.trip.shutdown()
        if self.state.connected:
            self.state.disconnect()
        super().closeEvent(e)
