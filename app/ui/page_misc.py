"""Araç Bilgisi, Kayıt (CSV) ve Rapor sayfaları."""
from __future__ import annotations

import os
from datetime import datetime

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QTableWidget, QHeaderView,
                               QAbstractItemView, QLabel, QLineEdit, QListWidget, QListWidgetItem, QDoubleSpinBox,
                               QFileDialog, QTextBrowser, QPlainTextEdit, QFrame, QMessageBox)

from .. import report
from ..state import AppState, run_bg
from .widgets import page_header, make_item, fmt_value


class VehiclePage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("Araç Bilgisi", "VIN, kalibrasyon kimliği, adaptör sürümü ve protokol."))
        row = QHBoxLayout()
        self.btn = QPushButton("Bilgileri oku")
        self.btn.setObjectName("primary")
        self.btn.clicked.connect(self.read)
        row.addWidget(self.btn)
        row.addStretch(1)
        l.addLayout(row)
        self.table = QTableWidget(0, 2)
        self.table.setHorizontalHeaderLabels(["Alan", "Değer"])
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setShowGrid(False)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.table.setColumnWidth(0, 230)
        self.table.verticalHeader().setDefaultSectionSize(40)
        l.addWidget(self.table, 1)
        box = QFrame()
        box.setObjectName("card")
        bl = QHBoxLayout(box)
        bl.setContentsMargins(18, 12, 18, 12)
        bl.addWidget(QLabel("VIN (şasi no) elle girişi:"))
        self.vin = QLineEdit(state.settings.get("manual_vin", ""))
        self.vin.setMaxLength(17)
        self.vin.setPlaceholderText("17 karakter - ECU VIN'i desteklemiyorsa buradan girin")
        bl.addWidget(self.vin, 1)
        sv = QPushButton("Kaydet")
        sv.clicked.connect(self.save_vin)
        bl.addWidget(sv)
        l.addWidget(box)
        self.status = QLabel("")
        self.status.setObjectName("muted")
        l.addWidget(self.status)
        state.connection_changed.connect(lambda ok: self.btn.setEnabled(ok))
        self.btn.setEnabled(False)

    def save_vin(self):
        v = self.vin.text().strip().upper()
        self.state.settings.put("manual_vin", v)
        self.state.vehicle["VIN (elle)"] = v
        self.status.setText("VIN kaydedildi." if v else "VIN temizlendi.")
        self.show_info()

    def read(self):
        elm = self.state.elm
        self.btn.setEnabled(False)
        self.status.setText("Okunuyor...")

        def ok(info):
            self.btn.setEnabled(True)
            self.state.vehicle = info
            self.status.setText("")
            self.show_info()
        run_bg(elm.vehicle_info, ok, lambda e: (self.btn.setEnabled(True), self.status.setText(f"Hata: {e}")))

    def show_info(self):
        info = dict(self.state.vehicle)
        mv = self.state.settings.get("manual_vin", "")
        if mv:
            info["VIN (elle girilen)"] = mv
        info.pop("VIN (elle)", None)
        self.table.setRowCount(0)
        for k, v in info.items():
            r = self.table.rowCount()
            self.table.insertRow(r)
            self.table.setItem(r, 0, make_item(k, color="#8b95a5"))
            self.table.setItem(r, 1, make_item(v or "—"))


class LogPage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("Kayıt", "Seçtiğiniz değerleri belirli aralıkla kaydedin ve CSV (Excel) olarak dışa aktarın."))
        top = QHBoxLayout()
        self.list = QListWidget()
        self.list.setMaximumWidth(340)
        top.addWidget(self.list)
        right = QVBoxLayout()
        row = QHBoxLayout()
        row.addWidget(QLabel("Aralık (sn):"))
        self.interval = QDoubleSpinBox()
        self.interval.setRange(0.2, 60)
        self.interval.setValue(1.0)
        self.interval.setSingleStep(0.5)
        row.addWidget(self.interval)
        row.addStretch(1)
        right.addLayout(row)
        row2 = QHBoxLayout()
        self.start = QPushButton("● Kaydı başlat")
        self.start.setObjectName("primary")
        self.start.clicked.connect(self.toggle)
        self.export = QPushButton("CSV olarak kaydet")
        self.export.clicked.connect(self.export_csv)
        row2.addWidget(self.start)
        row2.addWidget(self.export)
        row2.addStretch(1)
        right.addLayout(row2)
        self.count = QLabel("0 satır")
        self.count.setObjectName("h2")
        right.addWidget(self.count)
        self.preview = QTableWidget(0, 0)
        self.preview.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.preview.verticalHeader().setVisible(False)
        right.addWidget(self.preview, 1)
        top.addLayout(right, 1)
        l.addLayout(top, 1)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        state.catalog_changed.connect(self.fill)
        self.fill()

    def fill(self):
        sel = set(self._sel()) or {"rpm", "coolant", "speed", "load"}
        self.list.clear()
        for it in sorted(self.state.available, key=lambda i: (i.source, i.category, i.name)):
            w = QListWidgetItem(f"{it.name} ({it.unit})" if it.unit else it.name)
            w.setFlags(w.flags() | Qt.ItemIsUserCheckable)
            w.setCheckState(Qt.Checked if it.key in sel else Qt.Unchecked)
            w.setData(Qt.UserRole, it.key)
            self.list.addItem(w)

    def _sel(self):
        return [self.list.item(i).data(Qt.UserRole) for i in range(self.list.count())
                if self.list.item(i).checkState() == Qt.Checked]

    def toggle(self):
        lg = self.state.logger
        if lg.running:
            lg.stop()
            self.timer.stop()
            self.state.set_poll("log", [])
            self.start.setText("● Kaydı başlat")
            return
        if not self.state.connected:
            QMessageBox.information(self, "Kayıt", "Önce bağlanın.")
            return
        keys = self._sel()
        if not keys:
            return
        cols = [(k, f"{self.state.by_key[k].name} ({self.state.by_key[k].unit})") for k in keys]
        lg.start(cols)
        self.preview.setColumnCount(len(lg.header()))
        self.preview.setHorizontalHeaderLabels(lg.header())
        self.preview.setRowCount(0)
        self.state.set_poll("log", keys)
        self.timer.start(int(self.interval.value() * 1000))
        self.start.setText("■ Kaydı durdur")

    def tick(self):
        lg = self.state.logger
        lg.sample(self.state.live)
        row = lg.rows[-1]
        r = self.preview.rowCount()
        self.preview.insertRow(r)
        for c, v in enumerate(row):
            self.preview.setItem(r, c, make_item("" if v is None else (f"{v:.2f}" if isinstance(v, float) else str(v))))
        self.preview.scrollToBottom()
        self.count.setText(f"{len(lg.rows)} satır")

    def export_csv(self):
        lg = self.state.logger
        if not lg.rows:
            QMessageBox.information(self, "Kayıt", "Dışa aktarılacak kayıt yok.")
            return
        p, _ = QFileDialog.getSaveFileName(self, "CSV kaydet", f"obd_kayit_{datetime.now():%Y%m%d_%H%M}.csv",
                                           "CSV (*.csv)")
        if p:
            lg.export_csv(p)
            self.count.setText(f"{len(lg.rows)} satır - kaydedildi")


class ReportPage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("Rapor", "Arıza kodları ve anlık değerlerden HTML / PDF tanı raporu üretin."))
        row = QHBoxLayout()
        self.plate = QLineEdit()
        self.plate.setPlaceholderText("Plaka")
        self.owner = QLineEdit()
        self.owner.setPlaceholderText("Müşteri / araç sahibi")
        self.km = QLineEdit()
        self.km.setPlaceholderText("Kilometre")
        for w in (self.plate, self.owner, self.km):
            row.addWidget(w)
        l.addLayout(row)
        self.notes = QPlainTextEdit()
        self.notes.setPlaceholderText("Notlar...")
        self.notes.setMaximumHeight(80)
        l.addWidget(self.notes)
        btns = QHBoxLayout()
        for txt, fn, prim in (("Önizlemeyi yenile", self.refresh, True), ("HTML kaydet", self.save_html, False),
                              ("PDF kaydet", self.save_pdf, False)):
            b = QPushButton(txt)
            if prim:
                b.setObjectName("primary")
            b.clicked.connect(fn)
            btns.addWidget(b)
        btns.addStretch(1)
        l.addLayout(btns)
        self.view = QTextBrowser()
        l.addWidget(self.view, 1)
        self.refresh()

    def html(self) -> str:
        s = self.state
        info = dict(s.vehicle)
        mv = s.settings.get("manual_vin", "")
        if mv:
            info["VIN (elle girilen)"] = mv
        snap = []
        for k, v in s.live.items():
            it = s.by_key.get(k)
            if it and v is not None:
                snap.append((it.name, fmt_value(it, v), it.unit))
        meta = {"Plaka": self.plate.text(), "Müşteri": self.owner.text(), "Kilometre": self.km.text(),
                "Bağlantı": "Demo / Simülatör" if s.demo else "Gerçek araç"}
        return report.build_html(info, s.dtcs, snap, meta, self.notes.toPlainText())

    def refresh(self):
        self.view.setHtml(self.html())

    def save_html(self):
        p, _ = QFileDialog.getSaveFileName(self, "HTML kaydet", f"tani_raporu_{datetime.now():%Y%m%d_%H%M}.html",
                                           "HTML (*.html)")
        if p:
            report.save_html(p, self.html())

    def save_pdf(self):
        p, _ = QFileDialog.getSaveFileName(self, "PDF kaydet", f"tani_raporu_{datetime.now():%Y%m%d_%H%M}.pdf",
                                           "PDF (*.pdf)")
        if p:
            report.save_pdf(p, self.html())

    def showEvent(self, e):
        super().showEvent(e)
        self.refresh()
