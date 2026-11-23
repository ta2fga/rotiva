"""Arıza Kodları sayfası (Mod 03/07/0A, Mod 04 silme, UDS 19 02 FF)."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QTableWidget, QHeaderView,
                               QAbstractItemView, QLabel, QMessageBox)

from ..elm327 import ElmError
from ..state import AppState, run_bg
from . import theme
from .widgets import page_header, make_item

TYPE_COLOR = {"Kayıtlı": theme.CRIT, "Bekleyen": theme.WARN, "Kalıcı": theme.CRIT}


class DtcPage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("Arıza Kodları", "Kayıtlı (03), bekleyen (07) ve kalıcı (0A) kodlar ile üretici ECU kodları (UDS 19 02 FF)."))
        row = QHBoxLayout()
        self.read_btn = QPushButton("Standart kodları oku")
        self.read_btn.setObjectName("primary")
        self.read_btn.clicked.connect(self.read_std)
        self.uds_btn = QPushButton("Üretici ECU kodlarını oku")
        self.uds_btn.clicked.connect(self.read_uds)
        self.clear_btn = QPushButton("Arıza kodlarını sil")
        self.clear_btn.setObjectName("danger")
        self.clear_btn.clicked.connect(self.clear)
        for b in (self.read_btn, self.uds_btn):
            row.addWidget(b)
        row.addStretch(1)
        row.addWidget(self.clear_btn)
        l.addLayout(row)
        self.table = QTableWidget(0, 4)
        self.table.setHorizontalHeaderLabels(["Kod", "Tür", "ECU", "Açıklama"])
        self.table.verticalHeader().setVisible(False)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setAlternatingRowColors(True)
        self.table.setShowGrid(False)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(3, QHeaderView.Stretch)
        self.table.setColumnWidth(0, 120)
        self.table.setColumnWidth(1, 110)
        self.table.setColumnWidth(2, 200)
        self.table.verticalHeader().setDefaultSectionSize(38)
        l.addWidget(self.table, 1)
        self.status = QLabel("")
        self.status.setObjectName("muted")
        l.addWidget(self.status)
        state.connection_changed.connect(self._conn)
        self._conn(False)

    def _conn(self, ok):
        for b in (self.read_btn, self.uds_btn, self.clear_btn):
            b.setEnabled(ok)
        if not ok:
            self.status.setText("Bağlı değil.")

    def _busy(self, on, msg=""):
        for b in (self.read_btn, self.uds_btn, self.clear_btn):
            b.setEnabled(not on and self.state.connected)
        self.status.setText(msg)

    def _fill(self):
        self.table.setRowCount(0)
        for code, kind, ecu, desc in self.state.dtcs:
            r = self.table.rowCount()
            self.table.insertRow(r)
            self.table.setItem(r, 0, make_item(code, color=TYPE_COLOR.get(kind, theme.WARN)))
            self.table.setItem(r, 1, make_item(kind))
            self.table.setItem(r, 2, make_item(ecu))
            self.table.setItem(r, 3, make_item(desc))

    def _desc(self, code):
        return self.state.store.describe(code)

    def read_std(self):
        elm = self.state.elm
        self._busy(True, "Okunuyor...")

        def job():
            out = []
            for mode, kind in ((0x03, "Kayıtlı"), (0x07, "Bekleyen"), (0x0A, "Kalıcı")):
                try:
                    out += [(c, kind, "Motor (OBD2)") for c in elm.read_dtcs(mode)]
                except ElmError:
                    pass
            return out

        def ok(res):
            old = [d for d in self.state.dtcs if d[2] != "Motor (OBD2)"]
            self.state.dtcs = [(c, k, e, self._desc(c)) for c, k, e in res] + old
            self._fill()
            self._busy(False, f"{len(res)} standart kod bulundu." if res else "Standart arıza kodu yok.")
        run_bg(job, ok, lambda e: self._busy(False, f"Hata: {e}"))

    def read_uds(self):
        elm, store = self.state.elm, self.state.store
        self._busy(True, "ECU'lar taranıyor (UDS 19 02 FF)...")

        def job():
            out, notes = [], []
            for ecu in store.ecus:
                try:
                    for code, st in elm.read_uds_dtcs(ecu):
                        out.append((code, f"Üretici (durum {st:02X})", ecu["name"]))
                except ElmError as e:
                    notes.append(f"{ecu['name']}: {e}")
            return out, notes

        def ok(r):
            res, notes = r
            keep = [d for d in self.state.dtcs if not d[1].startswith("Üretici")]
            self.state.dtcs = keep + [(c, k, e, self._desc(c)) for c, k, e in res]
            self._fill()
            self._busy(False, f"{len(res)} üretici kodu bulundu; {len(notes)} ECU yanıt vermedi.")
        run_bg(job, ok, lambda e: self._busy(False, f"Hata: {e}"))

    def clear(self):
        m = QMessageBox(self)
        m.setIcon(QMessageBox.Warning)
        m.setWindowTitle("Arıza kodlarını sil")
        m.setText("Tüm kayıtlı arıza kodları ve donmuş veri silinecek.")
        m.setInformativeText("Arıza giderilmeden silmek lambayı geçici söndürür; arıza devam ederse tekrar yanar. "
                             "Kontak açık, motor kapalı olmalıdır. Devam edilsin mi?")
        yes = m.addButton("Evet, sil", QMessageBox.DestructiveRole)
        m.addButton("Vazgeç", QMessageBox.RejectRole)
        m.exec()
        if m.clickedButton() is not yes:
            return
        elm = self.state.elm
        self._busy(True, "Siliniyor...")

        def ok(res):
            self.state.dtcs = []
            self._fill()
            self._busy(False, "Silme komutu gönderildi (Mod 04)." if res else "ECU silmeyi onaylamadı.")
        run_bg(elm.clear_dtcs, ok, lambda e: self._busy(False, f"Hata: {e}"))
