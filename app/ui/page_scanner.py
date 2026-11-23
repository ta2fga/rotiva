"""Keşif / ECU Tarayıcı: ECU adres taraması, servis 21/22 kimlik taraması, bulunanı kaydetme."""
from __future__ import annotations

import threading

from PySide6.QtCore import Qt, Signal, QObject
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QPushButton, QTableWidget, QHeaderView,
                               QAbstractItemView, QLabel, QComboBox, QLineEdit, QProgressBar, QMessageBox, QFrame)

from .. import scanner
from ..datafile import save_user_did
from ..state import AppState
from . import theme
from .dialogs import SaveDidDialog
from .widgets import page_header, make_item


class _Sig(QObject):
    progress = Signal(int, int, str)
    ecu_done = Signal(object)
    hit = Signal(int, str, bool)
    finished = Signal(str)


class ScannerPage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        self.sig = _Sig()
        self.sig.progress.connect(self._progress, Qt.QueuedConnection)
        self.sig.ecu_done.connect(self._ecu_done, Qt.QueuedConnection)
        self.sig.hit.connect(self._hit, Qt.QueuedConnection)
        self.sig.finished.connect(self._finished, Qt.QueuedConnection)
        self._stop = False
        self._running = False
        self.alive: list = []
        self.cur_service = 0x22
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(12)
        l.addWidget(page_header("Keşif / ECU Tarayıcı", "Hangi ECU'ların yanıt verdiğini bulun, ardından 21 / 22 servis kimliklerini tarayın. "
                                                         "Yalnızca okuma servisleri kullanılır; kontak açık, motor kapalı olmalıdır."))
        self.mfr_banner = QLabel("Bu adaptör/araç kombinasyonunda üretici ECU'larına erişilemedi; standart OBD verileri kullanılıyor.")
        self.mfr_banner.setWordWrap(True)
        self.mfr_banner.setStyleSheet("background:rgba(59,130,246,.14);color:#9cc2ff;border-radius:10px;padding:10px 14px;")
        self.mfr_banner.setVisible(False)
        l.addWidget(self.mfr_banner)
        state.mfr_changed.connect(self._mfr)
        state.connection_changed.connect(lambda ok: self._mfr())
        # (a) ECU taraması
        a = QFrame()
        a.setObjectName("card")
        al = QVBoxLayout(a)
        al.setContentsMargins(18, 14, 18, 14)
        row = QHBoxLayout()
        t = QLabel("1) ECU adres taraması (10 03 / 10 C0 / 3E 00)")
        t.setObjectName("h2")
        row.addWidget(t, 1)
        self.scan_btn = QPushButton("ECU'ları tara")
        self.scan_btn.setObjectName("primary")
        self.scan_btn.clicked.connect(self.scan_ecus)
        row.addWidget(self.scan_btn)
        al.addLayout(row)
        self.ecu_table = QTableWidget(0, 4)
        self.ecu_table.setHorizontalHeaderLabels(["ECU", "TX → RX", "Durum", "Yanıtlar"])
        self._style(self.ecu_table, 3)
        self.ecu_table.setMaximumHeight(190)
        al.addWidget(self.ecu_table)
        l.addWidget(a)
        # (b) kimlik taraması
        b = QFrame()
        b.setObjectName("card")
        bl = QVBoxLayout(b)
        bl.setContentsMargins(18, 14, 18, 14)
        t2 = QLabel("2) Kimlik taraması")
        t2.setObjectName("h2")
        bl.addWidget(t2)
        r2 = QHBoxLayout()
        self.ecu_combo = QComboBox()
        self.ecu_combo.setMinimumWidth(260)
        r2.addWidget(self.ecu_combo)
        self.service = QComboBox()
        self.service.addItem("Servis 22 (DID, 2 bayt)", 0x22)
        self.service.addItem("Servis 21 (KWP yerel ID, 1 bayt)", 0x21)
        self.service.currentIndexChanged.connect(self._svc_changed)
        r2.addWidget(self.service)
        self.range = QLineEdit("F180-F1A0")
        self.range.setMaximumWidth(170)
        self.range.setToolTip("Onaltılık aralık, örn. F180-F1A0 veya D400-D4FF (servis 21 için 00-FF)")
        r2.addWidget(self.range)
        self.only_pos = QComboBox()
        self.only_pos.addItem("Tüm yanıtlar", False)
        self.only_pos.addItem("Yalnız olumlu", True)
        r2.addWidget(self.only_pos)
        self.start_btn = QPushButton("Taramayı başlat")
        self.start_btn.setObjectName("primary")
        self.start_btn.clicked.connect(self.scan_ids)
        r2.addWidget(self.start_btn)
        self.stop_btn = QPushButton("Durdur")
        self.stop_btn.setObjectName("danger")
        self.stop_btn.setEnabled(False)
        self.stop_btn.clicked.connect(lambda: setattr(self, "_stop", True))
        r2.addWidget(self.stop_btn)
        bl.addLayout(r2)
        self.bar = QProgressBar()
        self.bar.setFixedHeight(10)
        bl.addWidget(self.bar)
        self.res_table = QTableWidget(0, 5)
        self.res_table.setHorizontalHeaderLabels(["ID", "Sonuç", "Ham yanıt (hex)", "Bayt", ""])
        self._style(self.res_table, 2)
        self.res_table.setColumnWidth(4, 110)
        bl.addWidget(self.res_table, 1)
        l.addWidget(b, 1)
        self.status = QLabel("")
        self.status.setObjectName("muted")
        l.addWidget(self.status)
        state.connection_changed.connect(self._conn)
        state.catalog_changed.connect(self.fill_ecus)
        self._conn(False)
        self.fill_ecus()

    def _mfr(self):
        self.mfr_banner.setVisible(self.state.connected and self.state.mfr_reachable is False)

    def _style(self, t: QTableWidget, stretch: int):
        t.verticalHeader().setVisible(False)
        t.setEditTriggers(QAbstractItemView.NoEditTriggers)
        t.setSelectionBehavior(QAbstractItemView.SelectRows)
        t.setShowGrid(False)
        t.setAlternatingRowColors(True)
        t.horizontalHeader().setSectionResizeMode(stretch, QHeaderView.Stretch)
        t.verticalHeader().setDefaultSectionSize(34)

    def _conn(self, ok):
        self.scan_btn.setEnabled(ok and not self._running)
        self.start_btn.setEnabled(ok and not self._running)
        if not ok:
            self.status.setText("Bağlı değil.")

    def fill_ecus(self):
        cur = self.ecu_combo.currentData()
        self.ecu_combo.clear()
        for e in scanner.candidate_pairs(self.state.store):
            self.ecu_combo.addItem(f"{e['name']}  ({e['tx']}→{e['rx']})", e)
        for i in range(self.ecu_combo.count()):
            if cur and self.ecu_combo.itemData(i)["tx"] == cur["tx"]:
                self.ecu_combo.setCurrentIndex(i)

    def _svc_changed(self):
        self.range.setText("00-FF" if self.service.currentData() == 0x21 else "F180-F1A0")

    def _lock(self, running):
        self._running = running
        self.scan_btn.setEnabled(not running and self.state.connected)
        self.start_btn.setEnabled(not running and self.state.connected)
        self.stop_btn.setEnabled(running)

    def _start(self, fn):
        self._stop = False
        self._lock(True)
        threading.Thread(target=fn, daemon=True).start()

    # --- ECU taraması
    def scan_ecus(self):
        pairs = scanner.candidate_pairs(self.state.store)
        self.ecu_table.setRowCount(0)
        self.alive = []
        elm = self.state.elm

        def job():
            try:
                res = scanner.scan_ecus(elm, pairs, self.sig.progress.emit, lambda: self._stop)
                for r in res:
                    self.sig.ecu_done.emit(r)
                self.sig.finished.emit(f"ECU taraması bitti: {sum(r['alive'] for r in res)}/{len(res)} yanıt verdi.")
            except Exception as e:  # noqa: BLE001
                self.sig.finished.emit(f"Hata: {e}")
        self._start(job)

    def _ecu_done(self, r):
        p = r["pair"]
        i = self.ecu_table.rowCount()
        self.ecu_table.insertRow(i)
        self.ecu_table.setItem(i, 0, make_item(p["name"]))
        self.ecu_table.setItem(i, 1, make_item(f"{p['tx']} → {p['rx']}"))
        self.ecu_table.setItem(i, 2, make_item("● Yanıt verdi" if r["alive"] else "Yanıt yok",
                                               color=theme.OK if r["alive"] else theme.MUTED))
        txt = "  |  ".join(f"{k}: {v} ({scanner.classify(v)})" for k, v in r["responses"].items())
        self.ecu_table.setItem(i, 3, make_item(txt))
        if r["alive"]:
            self.alive.append(p)
            for j in range(self.ecu_combo.count()):
                if self.ecu_combo.itemData(j)["tx"] == p["tx"]:
                    self.ecu_combo.setCurrentIndex(j)

    # --- kimlik taraması
    def scan_ids(self):
        ecu = dict(self.ecu_combo.currentData() or {})
        svc = self.service.currentData()
        try:
            ids = scanner.parse_range(self.range.text(), svc)
        except ValueError as e:
            QMessageBox.warning(self, "Aralık", f"Geçersiz aralık: {e}")
            return
        if not ecu.get("session"):
            ecu["session"] = "10 03" if svc == 0x22 else "10 C0"
        self.cur_service, self.cur_ecu = svc, ecu
        self.res_table.setRowCount(0)
        only = self.only_pos.currentData()
        elm = self.state.elm

        def job():
            try:
                hits = scanner.scan_ids(elm, ecu, svc, ids, self.sig.progress.emit, lambda: self._stop,
                                        self.sig.hit.emit, only)
                self.sig.finished.emit(f"Tarama {'durduruldu' if self._stop else 'bitti'}: {len(hits)} yanıt.")
            except Exception as e:  # noqa: BLE001
                self.sig.finished.emit(f"Hata: {e}")
        self._start(job)

    def _hit(self, ident, hexs, pos):
        r = self.res_table.rowCount()
        self.res_table.insertRow(r)
        w = 4 if self.cur_service == 0x22 else 2
        it = make_item(f"{ident:0{w}X}", data=ident)
        self.res_table.setItem(r, 0, it)
        self.res_table.setItem(r, 1, make_item(scanner.classify(hexs), color=theme.OK if pos else theme.MUTED))
        self.res_table.setItem(r, 2, make_item(hexs))
        self.res_table.setItem(r, 3, make_item(str(len(hexs) // 2)))
        if pos:
            b = QPushButton("Kaydet…")
            b.setObjectName("flat")
            b.clicked.connect(lambda _=False, i=ident, h=hexs: self.save(i, h))
            self.res_table.setCellWidget(r, 4, b)
        self.res_table.scrollToBottom()

    def save(self, ident, hexs):
        ecu = self.cur_ecu
        d = SaveDidDialog(ecu["name"], self.cur_service, ident, hexs, self)
        if not d.exec():
            return
        entry = {"ecu": ecu["name"], "name": d.name.text().strip(), "service": f"{self.cur_service:02X}",
                 "did": f"{ident:04X}" if self.cur_service == 0x22 else f"{ident:02X}",
                 "formula": d.formula.text().strip(), "unit": d.unit.text().strip(),
                 "confidence": "low", "source": "kullanıcı keşfi"}
        save_user_did(entry, {"name": ecu["name"], "tx": ecu["tx"], "rx": ecu["rx"], "session": ecu.get("session", ""),
                              "protocol": "CAN 11/500"})
        self.state.reload_store()
        self.status.setText(f"Kaydedildi: {entry['name']} (PSA Özel ve Tüm Değerler'de görünür, doğrulanmamış).")

    def _progress(self, i, n, text):
        self.bar.setMaximum(max(1, n))
        self.bar.setValue(i)
        self.status.setText(f"{i}/{n}  {text}")

    def _finished(self, msg):
        self._lock(False)
        self.status.setText(msg)
