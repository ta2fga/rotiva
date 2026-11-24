"""Sürüş sayfası: sürüş modu (büyük, etkileşimsiz görünüm), sürüş listesi, ayrıntı + grafik + rapor."""
from __future__ import annotations

import os
from datetime import datetime

from PySide6.QtCore import Qt, QTimer, QPointF, QRectF
from PySide6.QtGui import QPainter, QColor, QPen, QPainterPath
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QGridLayout, QLabel, QPushButton, QCheckBox,
                               QTableWidget, QAbstractItemView, QHeaderView, QFrame, QProgressBar, QStackedWidget,
                               QScrollArea, QFileDialog, QMessageBox, QComboBox)

from .. import report
from ..gear import GEAR_COUNTS
from ..state import AppState
from ..trip import list_trips, read_samples
from . import theme
from .widgets import page_header, make_item

CRIT_EVENTS = {"harsh_accel", "harsh_brake", "over_rev", "cold_abuse"}


def score_color(v) -> str:
    if v is None:
        return theme.MUTED
    return theme.OK if v >= 75 else theme.WARN if v >= 50 else theme.CRIT


def fmt_dur(s: float) -> str:
    s = int(s)
    h, m = divmod(s // 60, 60)
    return f"{h} sa {m:02d} dk" if h else f"{m} dk {s % 60:02d} sn"


STATUS_TEXT = {"lost": "Bağlantı koptu – yeniden bağlanılıyor (kayıt duraklatıldı)",
               "offline": "Bağlantı yok – kayıt duraklatıldı; bağlanınca aynı sürüşe devam edilir"}


class TripChart(QWidget):
    """Hız / devir / vites zaman grafiği + olay işaretleri."""

    def __init__(self):
        super().__init__()
        self.setMinimumHeight(240)
        self.S: list = []
        self.events: list = []

    def set_data(self, samples, events):
        self.S, self.events = samples, events
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(1, 1, -1, -1)
        p.setBrush(QColor(theme.PANEL))
        p.setPen(QPen(QColor(theme.BORDER), 1))
        p.drawRoundedRect(r, 12, 12)
        plot = r.adjusted(14, 34, -14, -22)
        p.setPen(QPen(QColor(theme.BORDER), 1, Qt.DotLine))
        for i in range(5):
            y = plot.top() + plot.height() * i / 4
            p.drawLine(QPointF(plot.left(), y), QPointF(plot.right(), y))
        S = self.S
        gmax = max([5] + [s.gear for s in S if s.gear]) + 0.5          # 4..7 vites
        if len(S) < 2:
            p.setPen(QColor(theme.MUTED))
            p.drawText(r, Qt.AlignCenter, "Veri yok")
            p.end()
            return
        t0, t1 = S[0].t, S[-1].t
        span = max(t1 - t0, 1.0)
        X = lambda t: plot.left() + plot.width() * (t - t0) / span          # noqa: E731
        series = [("Hız (km/s)", lambda s: s.speed, 160.0, 0), ("Devir (d/dk)", lambda s: s.rpm, 4500.0, 1),
                  ("Vites", lambda s: s.gear, gmax, 2)]
        x = plot.left() + 4
        for name, fn, vmax, ci in series:
            col = QColor(theme.SERIES_COLORS[ci])
            path, last = QPainterPath(), None
            for s in S:
                v = fn(s)
                if v is None:
                    last = None
                    continue
                pt = QPointF(X(s.t), plot.bottom() - plot.height() * max(0.0, min(1.0, v / vmax)))
                if last is None:
                    path.moveTo(pt)
                elif name == "Vites":
                    path.lineTo(QPointF(pt.x(), last.y()))
                    path.lineTo(pt)
                else:
                    path.lineTo(pt)
                last = pt
            p.setPen(QPen(col, 2 if name != "Vites" else 1.5))
            p.setBrush(Qt.NoBrush)
            p.drawPath(path)
            p.setPen(col)
            p.drawText(QPointF(x, r.top() + 22), "● " + name)
            x += p.fontMetrics().horizontalAdvance("● " + name) + 18
        for e in self.events:
            col = QColor(theme.CRIT if e["type"] in CRIT_EVENTS else theme.WARN)
            ex = X(S[0].t + e["t"])
            p.setPen(QPen(col, 1, Qt.DashLine))
            p.drawLine(QPointF(ex, plot.top()), QPointF(ex, plot.bottom()))
            p.setPen(Qt.NoPen)
            p.setBrush(col)
            p.drawEllipse(QPointF(ex, plot.top() + 4), 5, 5)
        p.setPen(QColor(theme.MUTED))
        p.drawText(QPointF(plot.left(), r.bottom() - 6), "0")
        p.drawText(QPointF(plot.right() - 60, r.bottom() - 6), fmt_dur(span))
        p.end()


class DriveModeView(QWidget):
    """Sürüş modu: büyük hız / devir / vites / puan. Etkileşim gerektirmez."""

    def __init__(self, ctl, on_exit, on_finish):
        super().__init__()
        self.ctl = ctl
        g = QGridLayout(self)
        g.setContentsMargins(32, 20, 32, 20)
        g.setSpacing(10)
        self.status = QLabel("")
        self.status.setAlignment(Qt.AlignCenter)
        self.status.setWordWrap(True)
        g.addWidget(self.status, 0, 0, 1, 3)
        self.cap = []
        self.speed, self.rpm, self.gear, self.score = (QLabel("—") for _ in range(4))
        for col, (cap, w) in enumerate((("HIZ (km/s)", self.speed), ("VİTES (tahmini)", self.gear),
                                        ("DEVİR (d/dk)", self.rpm))):
            c = QLabel(cap)
            c.setAlignment(Qt.AlignCenter)
            c.setObjectName("muted")
            self.cap.append(c)
            w.setAlignment(Qt.AlignCenter)
            g.addWidget(c, 1, col)
            g.addWidget(w, 2, col)
        self.hint = QLabel("")
        self.hint.setAlignment(Qt.AlignCenter)
        g.addWidget(self.hint, 3, 0, 1, 3)
        self.score.setAlignment(Qt.AlignCenter)
        g.addWidget(self.score, 4, 0, 1, 3)
        row = QHBoxLayout()
        self.btn_exit = QPushButton("← Çık")
        self.btn_finish = QPushButton("Sürüşü bitir")
        self.btn_finish.setObjectName("danger")
        self.btn_exit.clicked.connect(on_exit)
        self.btn_finish.clicked.connect(on_finish)
        row.addWidget(self.btn_exit)
        row.addStretch(1)
        row.addWidget(self.btn_finish)
        g.addLayout(row, 5, 0, 1, 3)
        g.setRowStretch(2, 1)
        ctl.updated.connect(self.refresh)
        ctl.status_changed.connect(lambda _s: self.refresh())
        self.restyle()

    def restyle(self):
        self.speed.setStyleSheet(f"font-size:150px;font-weight:700;color:{theme.TEXT};")
        self.gear.setStyleSheet(f"font-size:190px;font-weight:700;color:{theme.ACCENT};")
        self.refresh()

    def refresh(self):
        if not self.isVisible() and self.ctl.live.get("speed") is None:
            return
        L = self.ctl.live
        sp, rpm, gear, sc = L.get("speed"), L.get("rpm"), L.get("gear"), L.get("score")
        self.speed.setText("—" if sp is None else f"{sp:.0f}")
        self.gear.setText(L.get("gear_label") or "—")     # 1..n, N (boşta/debriyaj) ya da — (bilinmiyor)
        col = theme.TEXT if rpm is None or rpm < 3000 else theme.WARN if rpm < 3500 else theme.CRIT
        self.rpm.setStyleSheet(f"font-size:84px;font-weight:700;color:{col};")
        self.rpm.setText("—" if rpm is None else f"{rpm:.0f}")
        up = gear is not None and gear < self.ctl.gear_count and rpm is not None and rpm >= 2500
        self.hint.setStyleSheet(f"font-size:34px;font-weight:600;color:{theme.WARN};")
        self.hint.setText("▲ Vitesi büyütün" if up else "")
        self.score.setStyleSheet(f"font-size:56px;font-weight:700;color:{score_color(sc)};")
        self.score.setText("Puan: —" if sc is None else f"Puan: {sc}")
        st = self.ctl.status
        if st:
            self.status.setStyleSheet(f"font-size:22px;font-weight:600;{theme.banner_style('warn')}")
            self.status.setText(STATUS_TEXT[st])
        else:
            self.status.setStyleSheet(f"font-size:18px;color:{theme.MUTED};")
            self.status.setText("● Kayıt sürüyor" if self.ctl.active else "Kayıt yok – hız 5 km/s'yi aşınca başlar"
                                if self.ctl.auto_start else "Kayıt yok")


class DrivePage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        self.ctl = state.trip
        self.rows: list = []
        self.cur: dict = {}
        self.cur_samples: list = []
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        self.views = QStackedWidget()
        outer.addWidget(self.views)
        main = QWidget()
        self.views.addWidget(main)
        self.mode = DriveModeView(self.ctl, self.exit_drive_mode, self.finish_trip)
        self.views.addWidget(self.mode)

        l = QVBoxLayout(main)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(12)
        l.addWidget(page_header("Sürüş", "Sürüşler kaydedilir, puanlanır ve koçluk ipuçları üretilir. "
                                          "Sürüş sırasında uygulamayı elle kullanmayın; yalnızca sürüş modunu izleyin."))
        self.banner = QLabel("")
        self.banner.setWordWrap(True)
        self.banner.setStyleSheet(theme.banner_style("warn"))
        self.banner.hide()
        l.addWidget(self.banner)
        row = QHBoxLayout()
        self.btn_mode = QPushButton("Sürüş modu")
        self.btn_mode.setObjectName("primary")
        self.btn_mode.clicked.connect(self.enter_drive_mode)
        self.btn_trip = QPushButton("Sürüşü başlat")
        self.btn_trip.clicked.connect(self.toggle_trip)
        self.auto = QCheckBox("Hız 5 km/s'yi aşınca otomatik başlat")
        self.auto.setChecked(self.ctl.auto_start)
        self.auto.toggled.connect(self.ctl.set_auto_start)
        self.gears = QComboBox()
        for k in GEAR_COUNTS:
            self.gears.addItem(f"{k} ileri", k)
        self.gears.setCurrentIndex(max(0, self.gears.findData(self.ctl.gear_count)))
        self.gears.setToolTip("Vites sayısı (ileri). Değiştirince vites öğrenmesi sıfırlanır.")
        self.gears.currentIndexChanged.connect(self._on_gear_count)
        gl = QLabel("Vites sayısı:")
        self.info = QLabel("")
        self.info.setObjectName("muted")
        for w in (self.btn_mode, self.btn_trip, self.auto, gl, self.gears):
            row.addWidget(w)
        row.addStretch(1)
        row.addWidget(self.info)
        l.addLayout(row)

        body = QHBoxLayout()
        body.setSpacing(14)
        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["Tarih", "Süre", "Mesafe", "Ort. hız", "Puan"])
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.verticalHeader().hide()
        self.table.setAlternatingRowColors(True)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.table.setFixedWidth(430)
        self.table.itemSelectionChanged.connect(self._select)
        body.addWidget(self.table)

        sc = QScrollArea()
        sc.setWidgetResizable(True)
        sc.setFrameShape(QFrame.NoFrame)
        det = QWidget()
        dl = QVBoxLayout(det)
        dl.setContentsMargins(0, 0, 8, 0)
        dl.setSpacing(10)
        top = QHBoxLayout()
        self.score_lbl = QLabel("—")
        self.title_lbl = QLabel("Bir sürüş seçin")
        self.title_lbl.setObjectName("h2")
        top.addWidget(self.score_lbl)
        top.addWidget(self.title_lbl, 1)
        self.btn_html = QPushButton("HTML rapor")
        self.btn_html.clicked.connect(self.export_html)
        self.btn_html.setEnabled(False)
        top.addWidget(self.btn_html)
        dl.addLayout(top)
        self.sub_box = QVBoxLayout()
        dl.addLayout(self.sub_box)
        self.subs: dict = {}
        from ..scoring import SUBS
        for name in SUBS:
            r = QHBoxLayout()
            lb = QLabel(name)
            lb.setMinimumWidth(190)
            bar = QProgressBar()
            bar.setRange(0, 100)
            bar.setFixedHeight(10)
            val = QLabel("—")
            val.setMinimumWidth(36)
            r.addWidget(lb)
            r.addWidget(bar, 1)
            r.addWidget(val)
            self.sub_box.addLayout(r)
            self.subs[name] = (bar, val)
        h = QLabel("Koçluk ipuçları")
        h.setObjectName("h2")
        dl.addWidget(h)
        self.tips = QLabel("")
        self.tips.setWordWrap(True)
        self.tips.setTextFormat(Qt.RichText)
        dl.addWidget(self.tips)
        self.chart = TripChart()
        dl.addWidget(self.chart)
        self.ev_lbl = QLabel("")
        self.ev_lbl.setObjectName("muted")
        self.ev_lbl.setWordWrap(True)
        dl.addWidget(self.ev_lbl)
        self.ratio_lbl = QLabel("")
        self.ratio_lbl.setObjectName("muted")
        self.ratio_lbl.setWordWrap(True)
        dl.addWidget(self.ratio_lbl)
        dl.addStretch(1)
        sc.setWidget(det)
        body.addWidget(sc, 1)
        l.addLayout(body, 1)

        self.ctl.started.connect(self._sync)
        self.ctl.finished.connect(self._on_finished)
        self.ctl.status_changed.connect(lambda _s: self._sync())
        self.ctl.updated.connect(self._sync_light)
        self.reload()
        self._sync()
        QTimer.singleShot(800, self.check_unfinished)

    # --- tema
    def restyle(self):
        self.banner.setStyleSheet(theme.banner_style("warn"))
        self.mode.restyle()
        self._show(self.cur, self.cur_samples)

    # --- ayar: vites sayısı
    def _on_gear_count(self, _idx: int):
        n = self.gears.currentData()
        if n is None or n == self.ctl.gear_count:
            return
        ans = QMessageBox.question(self, "Vites sayısı",
                                   f"Vites sayısı {n} olarak değiştirilsin mi? Vites öğrenmesi sıfırlanır.")
        if ans == QMessageBox.Yes:
            self.ctl.set_gear_count(n)
        else:
            self.gears.blockSignals(True)
            self.gears.setCurrentIndex(max(0, self.gears.findData(self.ctl.gear_count)))
            self.gears.blockSignals(False)

    # --- sürüş modu
    def enter_drive_mode(self):
        self.views.setCurrentIndex(1)
        self.mode.refresh()

    def exit_drive_mode(self):
        self.views.setCurrentIndex(0)

    # --- başlat / bitir
    def toggle_trip(self):
        if self.ctl.active:
            self.finish_trip()
        else:
            self.ctl.start()

    def finish_trip(self, confirm: bool = True):
        if not self.ctl.active:
            return
        if confirm and QMessageBox.question(self, "Sürüşü bitir", "Sürüş bitirilsin ve kaydedilsin mi?") \
                != QMessageBox.Yes:
            return
        self.ctl.finish()

    def _on_finished(self, summary):
        self._sync()
        if summary is None:
            self.info.setText("Sürüş çok kısa olduğu için kaydedilmedi.")
            return
        self.reload(select=summary["id"])

    def check_unfinished(self):
        data = self.ctl.unfinished()
        if not data:
            return
        when = datetime.fromtimestamp(data["start_ts"]).strftime("%d.%m.%Y %H:%M")
        box = QMessageBox(self)
        box.setWindowTitle("Yarım kalan sürüş")
        box.setText(f"{when} tarihli bitmemiş bir sürüş kaydı bulundu ({data['n']} örnek).\n"
                    "Devam etmek, bitirip kaydetmek veya silmek ister misiniz?")
        b_res = box.addButton("Devam et", QMessageBox.AcceptRole)
        b_fin = box.addButton("Bitir ve kaydet", QMessageBox.ActionRole)
        b_del = box.addButton("Sil", QMessageBox.DestructiveRole)
        box.exec()
        if box.clickedButton() is b_res:
            self.ctl.resume(data)
        elif box.clickedButton() is b_fin:
            self.ctl.resume(data)
            self.ctl.finish()
        elif box.clickedButton() is b_del:
            self.ctl.discard_unfinished()

    def _sync(self):
        act = self.ctl.active
        self.btn_trip.setText("Sürüşü bitir" if act else "Sürüşü başlat")
        self.btn_trip.setObjectName("danger" if act else "")
        self.btn_trip.setStyleSheet("")
        self.btn_trip.style().unpolish(self.btn_trip)
        self.btn_trip.style().polish(self.btn_trip)
        st = self.ctl.status
        self.banner.setVisible(bool(st) and act)
        if st:
            self.banner.setText(STATUS_TEXT[st])
        self._sync_light()

    def _sync_light(self):
        L = self.ctl.live
        if self.ctl.active:
            n = len(self.ctl.rec.samples)
            self.info.setText(f"● Kayıt: {n} örnek · hız {L.get('speed') or 0:.0f} km/s · "
                              f"puan {L['score'] if L.get('score') is not None else '—'}")
        elif not self.info.text().startswith("Sürüş çok"):
            self.info.setText("")

    # --- liste / ayrıntı
    def reload(self, select: str = None):
        self.rows = list_trips()
        t = self.table
        t.blockSignals(True)
        t.setRowCount(len(self.rows))
        for i, j in enumerate(self.rows):
            m = j.get("metrics", {})
            vals = [datetime.fromtimestamp(j["start_ts"]).strftime("%d.%m.%Y %H:%M"), fmt_dur(m.get("duration_s", 0)),
                    f"{m.get('distance_km', 0):.1f} km", f"{m.get('avg_speed', 0):.0f} km/s",
                    "—" if j.get("total") is None else str(j["total"])]
            for c, v in enumerate(vals):
                t.setItem(i, c, make_item(v, color=score_color(j.get("total")) if c == 4 else None))
        t.blockSignals(False)
        idx = next((i for i, j in enumerate(self.rows) if j["id"] == select), 0 if self.rows else -1)
        if idx >= 0:
            t.selectRow(idx)
            self._select()

    def _select(self):
        r = self.table.currentRow()
        if not 0 <= r < len(self.rows):
            return
        j = self.rows[r]
        S = []
        try:
            S = read_samples(os.path.join(os.path.dirname(j["_json"]), j["csv"]))
        except (OSError, ValueError, KeyError):
            pass
        self.cur, self.cur_samples = j, S
        self._show(j, S)

    def _show(self, j, S):
        if not j:
            return
        sc = j.get("total")
        self.score_lbl.setText("—" if sc is None else str(sc))
        self.score_lbl.setStyleSheet(f"font-size:54px;font-weight:700;color:{score_color(sc)};")
        m = j.get("metrics", {})
        self.title_lbl.setText(f"{datetime.fromtimestamp(j['start_ts']):%d.%m.%Y %H:%M} · "
                               f"{m.get('distance_km', 0):.1f} km · {fmt_dur(m.get('duration_s', 0))}"
                               + (" · kısa sürüş (puan yaklaşık)" if j.get("short") else ""))
        for name, (bar, val) in self.subs.items():
            v = j.get("subs", {}).get(name)
            bar.setValue(v or 0)
            bar.setStyleSheet(f"QProgressBar::chunk{{background:{score_color(v)};border-radius:4px;}}")
            val.setText("—" if v is None else str(v))
        tips = j.get("tips", [])
        self.tips.setText("".join(
            f"<p style='margin:2px 0'><span style='color:{theme.OK if t['kind'] == 'good' else theme.WARN}'>"
            f"{'✔' if t['kind'] == 'good' else '●'}</span> {t['text']}</p>" for t in tips) or "—")
        self.chart.set_data(S, j.get("events", []))
        ev = j.get("events", [])
        self.ev_lbl.setText("Olaylar: " + (" · ".join(f"{e['t']:.0f}. sn {e['label']} ({e['text']})" for e in ev)
                                           if ev else "yok"))
        rt = j.get("ratios")
        if rt:
            cnt = j.get("gear_counts") or [0] * len(rt)
            self.ratio_lbl.setText("Vites oranları (km/s / 1000 d/dk): " + ", ".join(
                f"{i + 1}. {r:.1f}{'' if cnt[i] >= 40 else ' (tahmin)'}" for i, r in enumerate(rt)))
        self.btn_html.setEnabled(True)

    def export_html(self):
        if not self.cur:
            return
        p, _ = QFileDialog.getSaveFileName(self, "HTML rapor kaydet",
                                           f"surus_raporu_{self.cur['id']}.html", "HTML (*.html)")
        if p:
            report.save_html(p, report.build_trip_html(self.cur, self.cur_samples))

    def showEvent(self, e):
        super().showEvent(e)
        self.reload(select=self.cur.get("id") if self.cur else None)
