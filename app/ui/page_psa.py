"""PSA Özel: veri dosyasındaki üretici DID'lerini okur (servis 22 / KWP 21)."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QTableWidget, QHeaderView,
                               QAbstractItemView, QLabel, QComboBox, QLineEdit, QTableWidgetItem)

from ..elm327 import ElmError
from ..pids import is_low_conf
from ..state import AppState, run_bg
from . import theme
from .widgets import page_header, make_item


class PsaPage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("PSA Özel", "Üretici ECU'larından veri dosyasına göre okunan değerler. "
                                            "“Doğrulanmamış” etiketli kayıtların doğruluğu garanti edilmez."))
        self.mfr_banner = QLabel("Bu adaptör/araç kombinasyonunda üretici ECU'larına erişilemedi; standart OBD verileri kullanılıyor.")
        self.mfr_banner.setWordWrap(True)
        self.mfr_banner.setStyleSheet("background:rgba(59,130,246,.14);color:#9cc2ff;border-radius:10px;padding:10px 14px;")
        self.mfr_banner.setVisible(False)
        l.addWidget(self.mfr_banner)
        state.mfr_changed.connect(self._mfr)
        state.connection_changed.connect(lambda ok: self._mfr())
        self.warn = QLabel("")
        self.warn.setObjectName("muted")
        self.warn.setWordWrap(True)
        l.addWidget(self.warn)
        bar = QHBoxLayout()
        self.ecu = QComboBox()
        self.ecu.currentIndexChanged.connect(self.populate)
        bar.addWidget(self.ecu)
        self.search = QLineEdit()
        self.search.setPlaceholderText("🔍  Ara...")
        self.search.textChanged.connect(self.populate)
        bar.addWidget(self.search, 1)
        self.read_btn = QPushButton("Seçilenleri oku")
        self.read_btn.setObjectName("primary")
        self.read_btn.clicked.connect(self.read_selected)
        bar.addWidget(self.read_btn)
        pin = QPushButton("★ Panom'a ekle")
        pin.clicked.connect(self.pin_selected)
        bar.addWidget(pin)
        l.addLayout(bar)
        self.table = QTableWidget(0, 8)
        self.table.setHorizontalHeaderLabels(["", "ECU", "Ad", "Servis/DID", "Değer", "Birim", "Ham yanıt", "Güven"])
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setShowGrid(False)
        self.table.setAlternatingRowColors(True)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(2, QHeaderView.Stretch)
        for c, w in ((0, 36), (1, 170), (3, 100), (4, 100), (5, 70), (6, 150), (7, 120)):
            self.table.setColumnWidth(c, w)
        self.table.verticalHeader().setDefaultSectionSize(38)
        l.addWidget(self.table, 1)
        self.status = QLabel("")
        self.status.setObjectName("muted")
        l.addWidget(self.status)
        state.catalog_changed.connect(self.refill)
        state.connection_changed.connect(lambda ok: self.read_btn.setEnabled(ok))
        self.read_btn.setEnabled(False)
        self.refill()

    def _mfr(self):
        self.mfr_banner.setVisible(self.state.connected and self.state.mfr_reachable is False)

    def refill(self):
        s = self.state.store
        self.ecu.blockSignals(True)
        self.ecu.clear()
        self.ecu.addItem("Tüm ECU'lar", "")
        for e in s.ecus:
            self.ecu.addItem(f"{e['name']} ({e['tx']}/{e['rx']})", e["name"])
        self.ecu.blockSignals(False)
        self.warn.setText("; ".join(s.warnings[:3]) if s.warnings else "")
        self.populate()

    def populate(self, *_):
        t = self.search.text().strip().lower()
        ecu = self.ecu.currentData()
        dids = [d for d in self.state.store.dids if (not ecu or d["ecu"] == ecu) and (not t or t in d["name"].lower())]
        self.table.setRowCount(0)
        for d in dids:
            r = self.table.rowCount()
            self.table.insertRow(r)
            chk = QTableWidgetItem()
            chk.setFlags(Qt.ItemIsUserCheckable | Qt.ItemIsEnabled)
            chk.setCheckState(Qt.Unchecked)
            chk.setData(Qt.UserRole, d["key"])
            self.table.setItem(r, 0, chk)
            self.table.setItem(r, 1, make_item(d["ecu"], color=theme.MUTED))
            self.table.setItem(r, 2, make_item(d["name"]))
            idt = f"{d['service']:02X}/{d['did']:04X}" if d["service"] != 0x21 else f"21/{d['did'] & 0xFF:02X}"
            self.table.setItem(r, 3, make_item(idt))
            self.table.setItem(r, 4, make_item("—", Qt.AlignRight | Qt.AlignVCenter))
            self.table.setItem(r, 5, make_item(d["unit"], color=theme.MUTED))
            self.table.setItem(r, 6, make_item("", color=theme.MUTED))
            low = is_low_conf(d["confidence"])
            self.table.setItem(r, 7, make_item("⚠ doğrulanmamış" if low else (d["confidence"] or "-"),
                                               color=theme.WARN if low else theme.MUTED))
            self.table.item(r, 7).setToolTip(d.get("source", ""))
        self.status.setText(f"{len(dids)} kayıt.")

    def _checked(self):
        return [self.table.item(r, 0).data(Qt.UserRole) for r in range(self.table.rowCount())
                if self.table.item(r, 0).checkState() == Qt.Checked]

    def pin_selected(self):
        keys = self._checked()
        if keys:
            self.state.settings.set_dashboard(list(self.state.settings.dashboard) + keys)
            self.state.dashboard_changed.emit()
            self.status.setText(f"{len(keys)} değer Panom'a eklendi.")

    def read_selected(self):
        keys = self._checked()
        if not keys:
            self.status.setText("Önce okunacak satırları işaretleyin.")
            return
        s, elm = self.state.store, self.state.elm
        by = {d["key"]: d for d in s.dids}
        self.read_btn.setEnabled(False)
        self.status.setText("Okunuyor...")

        def job():
            out = {}
            for k in keys:
                d = by[k]
                try:
                    raw = elm.read_did(s.ecu(d["ecu"]), d["did"], d["service"])
                    out[k] = (raw.hex().upper(), d["fn"](raw), "")
                except (ElmError, Exception) as e:  # noqa: BLE001
                    out[k] = ("", None, str(e))
            return out

        def ok(res):
            self.read_btn.setEnabled(True)
            n = 0
            for r in range(self.table.rowCount()):
                k = self.table.item(r, 0).data(Qt.UserRole)
                if k in res:
                    raw, v, err = res[k]
                    self.table.item(r, 4).setText("—" if v is None else f"{v:.2f}".rstrip("0").rstrip("."))
                    self.table.item(r, 6).setText(raw or err[:40])
                    n += v is not None
            self.status.setText(f"{n}/{len(res)} değer okundu.")
        run_bg(job, ok, lambda e: (self.read_btn.setEnabled(True), self.status.setText(f"Hata: {e}")))
