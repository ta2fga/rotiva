"""Ana pencere: kenar çubuğu gezinme + sayfalar."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QMainWindow, QWidget, QHBoxLayout, QVBoxLayout, QListWidget, QListWidgetItem,
                               QStackedWidget, QLabel, QPushButton, QMessageBox)

from .. import APP_NAME, APP_VERSION
from ..state import AppState
from .dialogs import show_about
from .icon import app_icon
from .page_connection import ConnectionPage
from .page_dtc import DtcPage
from .page_misc import VehiclePage, LogPage, ReportPage
from .page_psa import PsaPage
from .page_scanner import ScannerPage
from .page_values import DashboardPage, AllValuesPage, LivePage


class MainWindow(QMainWindow):
    def __init__(self, state: AppState = None):
        super().__init__()
        self.state = state or AppState()
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
        sub = QLabel("C-Elysée 1.6 HDi")
        sub.setObjectName("brandSub")
        sl.addWidget(sub)
        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        sl.addWidget(self.nav, 1)
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
                 ("📋", "Tüm Değerler", AllValuesPage), ("📈", "Canlı Grafik", LivePage),
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

    def _conn(self, ok):
        self._conn_refresh()
        if ok:
            self.nav.setCurrentRow(1)

    def _conn_refresh(self):
        s = self.state
        if s.connected:
            mode = "Demo" if s.demo else (s.elm.protocol or "Bağlı")
            self.dot.setText(f"● Bağlı ({mode})")
            self.dot.setStyleSheet("color:#22c55e;")
        else:
            self.dot.setText("● Bağlı değil")
            self.dot.setStyleSheet("color:#8b95a5;")

    def closeEvent(self, e):
        if self.state.connected:
            self.state.disconnect()
        super().closeEvent(e)
