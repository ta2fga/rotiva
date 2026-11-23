"""Panom, Tüm Değerler ve Canlı Grafik sayfaları."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QGridLayout, QScrollArea, QPushButton, QLabel,
                               QLineEdit, QComboBox, QTableWidget, QAbstractItemView, QHeaderView, QListWidget,
                               QListWidgetItem, QSplitter)

from ..pids import CATEGORIES, is_low_conf
from ..state import AppState
from . import theme
from .dialogs import ValuePickerDialog
from .widgets import ValueCard, LineChart, page_header, fmt_value, make_item


class DashboardPage(QWidget):
    """Panom: kullanıcının seçtiği değerler büyük kartlar halinde."""

    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        self.cards: dict = {}
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        head = QHBoxLayout()
        head.addWidget(page_header("Panom", "İzlemek istediğiniz değerleri siz seçin. Renkler: yeşil normal, sarı dikkat, kırmızı kritik."), 1)
        self.add_btn = QPushButton("＋  Değer seç / ekle")
        self.add_btn.setObjectName("primary")
        self.add_btn.clicked.connect(self.pick)
        head.addWidget(self.add_btn, 0, Qt.AlignTop)
        l.addLayout(head)
        self.banner = QLabel("Bağlı değil. Önce Bağlantı sayfasından bağlanın (aracınız yoksa Demo / Simülatör).")
        self.banner.setStyleSheet(theme.banner_style("warn"))
        l.addWidget(self.banner)
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.scroll.setFrameShape(QScrollArea.NoFrame)
        self.host = QWidget()
        self.grid = QGridLayout(self.host)
        self.grid.setSpacing(16)
        self.grid.setContentsMargins(0, 0, 8, 0)
        self.scroll.setWidget(self.host)
        l.addWidget(self.scroll, 1)
        self.empty = QLabel("Panonuz boş. “Değer seç / ekle” ile başlayın.")
        self.empty.setObjectName("muted")
        self.empty.setAlignment(Qt.AlignCenter)
        l.addWidget(self.empty)
        state.value.connect(self._on_value)
        state.connection_changed.connect(self._conn)
        state.catalog_changed.connect(self.rebuild)
        state.dashboard_changed.connect(self.rebuild)
        self._cols = 0
        self.rebuild()

    def _conn(self, ok):
        self.banner.setVisible(not ok)
        if not ok:
            for c in self.cards.values():
                c.set_value(None)

    def pick(self):
        d = ValuePickerDialog(self.state.available, set(self.state.settings.dashboard), self)
        if d.exec():
            self.state.settings.set_dashboard(d.selected_keys())
            self.state.dashboard_changed.emit()

    def rebuild(self):
        for c in self.cards.values():
            c.setParent(None)
            c.deleteLater()
        self.cards.clear()
        keys = [k for k in self.state.settings.dashboard if k in self.state.by_key]
        for k in keys:
            c = ValueCard(self.state.by_key[k], editable=True)
            c.removeRequested.connect(self.remove)
            c.moveRequested.connect(self.move)
            if k in self.state.live:
                c.set_value(self.state.live[k])
            self.cards[k] = c
        self.empty.setVisible(not keys)
        self.state.set_poll("dashboard", keys)
        self._cols = 0
        self.relayout()
        self.banner.setVisible(not self.state.connected)

    def relayout(self):
        w = self.scroll.viewport().width()
        cols = max(1, w // 260)
        if cols == self._cols and self.grid.count() == len(self.cards):
            return
        self._cols = cols
        while self.grid.count():
            self.grid.takeAt(0)
        for i, c in enumerate(self.cards.values()):
            self.grid.addWidget(c, i // cols, i % cols)
        for j in range(cols):
            self.grid.setColumnStretch(j, 1)
        self.grid.setRowStretch(len(self.cards) // cols + 1, 1)

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.relayout()

    def remove(self, key):
        self.state.settings.set_dashboard([k for k in self.state.settings.dashboard if k != key])
        self.rebuild()

    def move(self, key, d):
        ks = list(self.state.settings.dashboard)
        if key in ks:
            i = ks.index(key)
            j = i + d
            if 0 <= j < len(ks):
                ks[i], ks[j] = ks[j], ks[i]
                self.state.settings.set_dashboard(ks)
                self.rebuild()

    def _on_value(self, k, v):
        c = self.cards.get(k)
        if c:
            c.set_value(v)


class AllValuesPage(QWidget):
    """Tüm Değerler: kategorilere ayrılmış tablo, arama, ★ ile Panom'a ekleme."""

    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        self.rows: dict = {}
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("Tüm Değerler", "Aracın desteklediği tüm değerler. ☆ simgesine tıklayarak Panom'a ekleyin."))
        bar = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText("🔍  Ara...")
        self.search.textChanged.connect(self.populate)
        bar.addWidget(self.search, 1)
        self.cat = QComboBox()
        self.cat.addItem("Tüm kategoriler", "")
        for c in CATEGORIES:
            self.cat.addItem(c, c)
        self.cat.currentIndexChanged.connect(self.populate)
        bar.addWidget(self.cat)
        self.src = QComboBox()
        self.src.addItem("Tüm kaynaklar", "")
        self.src.addItem("Standart OBD2", "std")
        self.src.addItem("PSA özel", "psa")
        self.src.currentIndexChanged.connect(self.populate)
        bar.addWidget(self.src)
        l.addLayout(bar)
        self.table = QTableWidget(0, 6)
        self.table.setHorizontalHeaderLabels(["★", "Değer", "Okunan", "Birim", "Kategori", "Kaynak"])
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setShowGrid(False)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(1, QHeaderView.Stretch)
        for c, w in ((0, 50), (2, 110), (3, 80), (4, 140), (5, 190)):
            hh.setSectionResizeMode(c, QHeaderView.Fixed)
            self.table.setColumnWidth(c, w)
        self.table.verticalHeader().setDefaultSectionSize(40)
        self.table.cellClicked.connect(self._clicked)
        l.addWidget(self.table, 1)
        self.hint = QLabel("")
        self.hint.setObjectName("muted")
        l.addWidget(self.hint)
        state.value.connect(self._on_value)
        state.catalog_changed.connect(self.populate)
        state.dashboard_changed.connect(self.populate)
        self.populate()

    def populate(self, *_):
        t = self.search.text().strip().lower()
        cat, src = self.cat.currentData(), self.src.currentData()
        items = [i for i in self.state.catalog
                 if (not t or t in i.name.lower() or t in i.category.lower() or t in i.ecu.lower())
                 and (not cat or i.category == cat) and (not src or i.source == src)]
        items.sort(key=lambda i: (CATEGORIES.index(i.category) if i.category in CATEGORIES else 99, i.name))
        self.table.setRowCount(0)
        self.rows.clear()
        pins = set(self.state.settings.dashboard)
        last = None
        bold = QFont()
        bold.setBold(True)
        for it in items:
            if it.category != last:
                last = it.category
                r = self.table.rowCount()
                self.table.insertRow(r)
                hdr = make_item(last.upper(), color=theme.ACCENT)
                hdr.setFont(bold)
                self.table.setItem(r, 0, hdr)
                self.table.setSpan(r, 0, 1, 6)
                self.table.setRowHeight(r, 34)
            r = self.table.rowCount()
            self.table.insertRow(r)
            star = make_item("★" if it.key in pins else "☆", Qt.AlignCenter, it.key,
                             theme.WARN if it.key in pins else theme.MUTED)
            star.setFont(QFont("Segoe UI Symbol", 14))
            self.table.setItem(r, 0, star)
            name = it.name + ("   ⚠ doğrulanmamış" if it.source == "psa" and is_low_conf(it.confidence) else "")
            self.table.setItem(r, 1, make_item(name, color=None if it.supported else theme.P["disabled"]))
            val = make_item(fmt_value(it, self.state.live.get(it.key)) if it.supported else "desteklenmiyor",
                            Qt.AlignRight | Qt.AlignVCenter, color=None if it.supported else theme.P["disabled"])
            val.setFont(QFont("Segoe UI", 13, QFont.Bold))
            self.table.setItem(r, 2, val)
            self.table.setItem(r, 3, make_item(it.unit, color=theme.MUTED))
            self.table.setItem(r, 4, make_item(it.category, color=theme.MUTED))
            self.table.setItem(r, 5, make_item(it.ecu if it.source == "psa" else "Standart OBD2", color=theme.MUTED))
            if it.supported:
                self.rows[it.key] = r
            else:
                star.setText("–")
                star.setData(Qt.UserRole, None)
        self.hint.setText(f"{len(items)} değer listeleniyor." + ("" if self.state.connected else "  Okumak için bağlanın."))
        self._update_poll()

    def _update_poll(self):
        if self.isVisible():
            self.state.set_poll("all", list(self.rows)[:30])

    def showEvent(self, e):
        super().showEvent(e)
        self._update_poll()

    def hideEvent(self, e):
        super().hideEvent(e)
        self.state.set_poll("all", [])

    def _clicked(self, row, col):
        if col != 0:
            return
        it = self.table.item(row, 0)
        key = it.data(Qt.UserRole)
        if not key:
            return
        ks = list(self.state.settings.dashboard)
        ks.remove(key) if key in ks else ks.append(key)
        self.state.settings.set_dashboard(ks)
        on = key in ks
        it.setText("★" if on else "☆")
        it.setForeground(QColor(theme.WARN if on else theme.MUTED))
        self.state.dashboard_changed.emit()

    def _on_value(self, k, v):
        r = self.rows.get(k)
        if r is not None and k in self.state.by_key:
            self.table.item(r, 2).setText(fmt_value(self.state.by_key[k], v))


class LivePage(QWidget):
    """Canlı Grafik: seçilen değerlerin zaman grafiği."""

    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(14)
        l.addWidget(page_header("Canlı Grafik", "Grafikte görmek istediğiniz değerleri işaretleyin (her değer kendi ölçeğinde çizilir)."))
        sp = QSplitter(Qt.Horizontal)
        self.list = QListWidget()
        self.list.setMaximumWidth(330)
        self.list.itemChanged.connect(self._changed)
        sp.addWidget(self.list)
        self.chart = LineChart()
        sp.addWidget(self.chart)
        sp.setStretchFactor(1, 1)
        l.addWidget(sp, 1)
        row = QHBoxLayout()
        clear = QPushButton("Grafiği temizle")
        clear.clicked.connect(self.chart.clear)
        row.addWidget(clear)
        row.addStretch(1)
        l.addLayout(row)
        state.value.connect(self.chart.add)
        state.catalog_changed.connect(self.fill)
        self.fill()

    def fill(self):
        sel = set(self._selected()) or {"rpm", "speed", "coolant"}
        self.list.blockSignals(True)
        self.list.clear()
        for it in sorted(self.state.available, key=lambda i: (i.source, i.category, i.name)):
            w = QListWidgetItem(it.name)
            w.setFlags(w.flags() | Qt.ItemIsUserCheckable)
            w.setCheckState(Qt.Checked if it.key in sel else Qt.Unchecked)
            w.setData(Qt.UserRole, it.key)
            self.list.addItem(w)
        self.list.blockSignals(False)
        self._changed()

    def showEvent(self, e):
        super().showEvent(e)
        self._changed()

    def hideEvent(self, e):
        super().hideEvent(e)
        self.state.set_poll("chart", [])

    def _selected(self) -> list:
        return [self.list.item(i).data(Qt.UserRole) for i in range(self.list.count())
                if self.list.item(i).checkState() == Qt.Checked]

    def _changed(self, *_):
        keys = self._selected()[:8]
        self.chart.set_keys([self.state.by_key[k] for k in keys if k in self.state.by_key])
        if self.isVisible():
            self.state.set_poll("chart", keys)
