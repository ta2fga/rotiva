"""Ortak arayüz bileşenleri: değer kartı, çizgi grafik, sayfa başlığı."""
from __future__ import annotations

import time
from collections import deque

from PySide6.QtCore import Qt, Signal, QRectF, QPointF
from PySide6.QtGui import QPainter, QColor, QPen, QPainterPath
from PySide6.QtWidgets import (QFrame, QLabel, QVBoxLayout, QHBoxLayout, QPushButton, QWidget, QProgressBar,
                               QSizePolicy, QTableWidgetItem)

from ..pids import Item, status_of, is_low_conf
from . import theme


def fmt_value(item: Item, v) -> str:
    if v is None:
        return "—"
    if item.unit in ("d/dk", "km", "sn", "kPa", "°C", "km/s", "%") or item.decimals == 0:
        return f"{v:.0f}"
    return f"{v:.{item.decimals}f}"


def has_thresholds(item: Item) -> bool:
    return any(x is not None for x in (item.warn_hi, item.crit_hi, item.warn_lo, item.crit_lo))


def page_header(title: str, subtitle: str = "") -> QWidget:
    w = QWidget()
    l = QVBoxLayout(w)
    l.setContentsMargins(0, 0, 0, 8)
    l.setSpacing(2)
    t = QLabel(title)
    t.setObjectName("h1")
    l.addWidget(t)
    if subtitle:
        s = QLabel(subtitle)
        s.setObjectName("muted")
        s.setWordWrap(True)
        l.addWidget(s)
    return w


def make_item(text, align=None, data=None, color=None) -> QTableWidgetItem:
    it = QTableWidgetItem(text)
    if align:
        it.setTextAlignment(align)
    if data is not None:
        it.setData(Qt.UserRole, data)
    if color:
        it.setForeground(QColor(color))
    it.setFlags(it.flags() & ~Qt.ItemIsEditable)
    return it


class ValueCard(QFrame):
    """Büyük sayı + birim + kısa Türkçe etiket + durum rengi (yeşil/sarı/kırmızı)."""
    removeRequested = Signal(str)
    moveRequested = Signal(str, int)

    def __init__(self, item: Item, editable: bool = False):
        super().__init__()
        self.item = item
        self._v = None
        self.setObjectName("card")
        self.setMinimumSize(230, 150)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        l = QVBoxLayout(self)
        l.setContentsMargins(18, 14, 14, 14)
        l.setSpacing(2)
        top = QHBoxLayout()
        self.name = QLabel(item.name)
        self.name.setObjectName("cardName")
        self.name.setWordWrap(True)
        top.addWidget(self.name, 1)
        if editable:
            for txt, fn in (("◀", lambda: self.moveRequested.emit(item.key, -1)),
                            ("▶", lambda: self.moveRequested.emit(item.key, 1)),
                            ("✕", lambda: self.removeRequested.emit(item.key))):
                b = QPushButton(txt)
                b.setObjectName("flat")
                b.setFixedSize(26, 26)
                b.setCursor(Qt.PointingHandCursor)
                b.clicked.connect(fn)
                top.addWidget(b)
        l.addLayout(top)
        row = QHBoxLayout()
        row.setSpacing(8)
        self.value = QLabel("—")
        self.value.setObjectName("cardValue")
        self.unit = QLabel(item.unit)
        self.unit.setObjectName("cardUnit")
        self.unit.setAlignment(Qt.AlignBottom)
        row.addWidget(self.value)
        row.addWidget(self.unit, 0, Qt.AlignBottom)
        row.addStretch(1)
        l.addLayout(row)
        self.bar = QProgressBar()
        self.bar.setRange(0, 1000)
        self.bar.setFixedHeight(8)
        l.addWidget(self.bar)
        foot = QHBoxLayout()
        sub = QLabel(item.ecu if item.source == "psa" else item.category)
        sub.setObjectName("muted")
        sub.setStyleSheet("font-size:11px;")
        foot.addWidget(sub)
        foot.addStretch(1)
        if item.source == "psa" and is_low_conf(item.confidence):
            b = QLabel("doğrulanmamış")
            b.setObjectName("badge")
            foot.addWidget(b)
        l.addLayout(foot)
        self.set_value(None)

    def restyle(self):
        self.set_value(self._v)

    def set_value(self, v):
        it = self.item
        self._v = v
        st = status_of(it, v) if has_thresholds(it) else ("ok" if v is not None else "none")
        col = theme.STATUS_COLOR[st] if has_thresholds(it) else (theme.TEXT if v is not None else theme.MUTED)
        self.value.setText(fmt_value(it, v))
        self.value.setStyleSheet(f"color:{col};")
        span = (it.vmax - it.vmin) or 1
        frac = 0 if v is None else max(0.0, min(1.0, (v - it.vmin) / span))
        self.bar.setValue(int(frac * 1000))
        c = col if has_thresholds(it) else theme.ACCENT
        self.bar.setStyleSheet(f"QProgressBar::chunk{{background:{c};border-radius:4px;}}")


class LineChart(QWidget):
    """Seçili değerleri (kendi aralıklarına normalize ederek) çizen basit canlı grafik."""

    def __init__(self, window_s: float = 90.0):
        super().__init__()
        self.window = window_s
        self.series: dict = {}      # key -> (Item, deque[(t, v)])
        self.setMinimumHeight(260)

    def set_keys(self, items: list):
        keep = {i.key for i in items}
        for k in list(self.series):
            if k not in keep:
                del self.series[k]
        for i in items:
            self.series.setdefault(i.key, (i, deque(maxlen=4000)))
        self.update()

    def add(self, key: str, v):
        if key in self.series and v is not None:
            self.series[key][1].append((time.time(), v))
            self.update()

    def clear(self):
        for _, d in self.series.values():
            d.clear()
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(1, 1, -1, -1)
        p.setBrush(QColor(theme.PANEL))
        p.setPen(QPen(QColor(theme.BORDER), 1))
        p.drawRoundedRect(r, 12, 12)
        plot = r.adjusted(14, 34, -14, -18)
        p.setPen(QPen(QColor(theme.BORDER), 1, Qt.DotLine))
        for i in range(5):
            y = plot.top() + plot.height() * i / 4
            p.drawLine(QPointF(plot.left(), y), QPointF(plot.right(), y))
        now = time.time()
        x = plot.left() + 4
        for n, (key, (item, data)) in enumerate(self.series.items()):
            col = QColor(theme.SERIES_COLORS[n % len(theme.SERIES_COLORS)])
            span = (item.vmax - item.vmin) or 1
            path = QPainterPath()
            first = True
            for t, v in data:
                age = now - t
                if age > self.window:
                    continue
                px = plot.right() - plot.width() * age / self.window
                py = plot.bottom() - plot.height() * max(0, min(1, (v - item.vmin) / span))
                if first:
                    path.moveTo(px, py)
                    first = False
                else:
                    path.lineTo(px, py)
            p.setPen(QPen(col, 2))
            p.setBrush(Qt.NoBrush)
            p.drawPath(path)
            last = data[-1][1] if data else None
            p.setPen(col)
            label = f"{item.name}: {fmt_value(item, last)} {item.unit}"
            p.drawText(QPointF(x, r.top() + 22), "● " + label)
            x += p.fontMetrics().horizontalAdvance("● " + label) + 18
        p.end()
