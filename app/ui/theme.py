"""Temalar: palet sözlüğü + QSS şablonu. `apply(ad)` modül düzeyindeki renk sabitlerini günceller."""

DEFAULT_THEME = "Gece Mavisi"

# Her temada tek vurgu rengi; durum renkleri (ok/warn/crit) o temanın zemininde okunaklı seçilmiştir.
PALETTES = {
    "Gece Mavisi": dict(
        bg="#0f1419", panel="#161c24", card="#1b232d", border="#263140", text="#e6ebf2", muted="#8b95a5",
        accent="#3b82f6", accent_hover="#5393f7", on_accent="#ffffff", ok="#22c55e", warn="#f59e0b", crit="#ef4444",
        alt="#19212b", sel="#23406f", scroll="#2e3b4d", disabled="#5b6677", dis_bg="#27344a", dis_fg="#7d8ba0",
        banner_fg="#9cc2ff", bw=1,
        series=["#3b82f6", "#22c55e", "#f59e0b", "#ef4444", "#a855f7", "#14b8a6", "#ec4899", "#eab308"]),
    "Aydınlık": dict(
        bg="#f5f7fa", panel="#eef1f6", card="#ffffff", border="#d5dbe5", text="#0f172a", muted="#526071",
        accent="#2563eb", accent_hover="#1d4ed8", on_accent="#ffffff", ok="#15803d", warn="#b45309", crit="#dc2626",
        alt="#f1f4f9", sel="#dbe7ff", scroll="#c3cbd8", disabled="#94a3b8", dis_bg="#dbe3f0", dis_fg="#7b8798",
        banner_fg="#1e40af", bw=1,
        series=["#2563eb", "#15803d", "#b45309", "#dc2626", "#7e22ce", "#0f766e", "#be185d", "#a16207"]),
    "Grafit Turuncu": dict(
        bg="#18181b", panel="#202024", card="#27272a", border="#3f3f46", text="#fafafa", muted="#a1a1aa",
        accent="#f97316", accent_hover="#fb8a3c", on_accent="#18181b", ok="#4ade80", warn="#fbbf24", crit="#f87171",
        alt="#222226", sel="#5a3412", scroll="#52525b", disabled="#71717a", dis_bg="#33333a", dis_fg="#8a8a93",
        banner_fg="#fdba74", bw=1,
        series=["#f97316", "#4ade80", "#fbbf24", "#f87171", "#c084fc", "#2dd4bf", "#f472b6", "#60a5fa"]),
    "Orman": dict(
        bg="#0f1a14", panel="#14231b", card="#192b21", border="#264236", text="#e5f2ea", muted="#8aa596",
        accent="#10b981", accent_hover="#34d399", on_accent="#06281c", ok="#4ade80", warn="#fbbf24", crit="#f87171",
        alt="#162720", sel="#1c5a43", scroll="#2f5646", disabled="#587567", dis_bg="#1f3a2d", dis_fg="#7c9a8a",
        banner_fg="#6ee7b7", bw=1,
        series=["#10b981", "#fbbf24", "#60a5fa", "#f87171", "#c084fc", "#2dd4bf", "#f472b6", "#fde047"]),
    "Yüksek Kontrast": dict(
        bg="#000000", panel="#000000", card="#0a0a0a", border="#ffffff", text="#ffffff", muted="#d4d4d4",
        accent="#facc15", accent_hover="#fde047", on_accent="#000000", ok="#4ade80", warn="#fb923c", crit="#ff5c5c",
        alt="#141414", sel="#5c4a00", scroll="#ffffff", disabled="#a3a3a3", dis_bg="#262626", dis_fg="#bdbdbd",
        banner_fg="#fde047", bw=2,
        series=["#facc15", "#4ade80", "#38bdf8", "#ff5c5c", "#e879f9", "#2dd4bf", "#fb923c", "#ffffff"]),
}
THEME_NAMES = list(PALETTES)

# Geçerli palet ve kısayol sabitleri (apply() ile güncellenir; STATUS_COLOR yerinde güncellenir)
P: dict = {}
STATUS_COLOR: dict = {}
QSS = ""
CURRENT = DEFAULT_THEME
BG = PANEL = CARD = BORDER = TEXT = MUTED = ACCENT = OK = WARN = CRIT = ""
SERIES_COLORS: list = []


def rgba(color: str, alpha: float) -> str:
    c = color.lstrip("#")
    return f"rgba({int(c[0:2], 16)},{int(c[2:4], 16)},{int(c[4:6], 16)},{alpha})"


def banner_style(kind: str = "accent") -> str:
    """Bilgi/uyarı şeridi stili (kind: 'accent' veya 'warn')."""
    if kind == "warn":
        return f"background:{rgba(WARN, 0.14)};color:{WARN};border-radius:10px;padding:10px 14px;"
    return f"background:{rgba(ACCENT, 0.14)};color:{P['banner_fg']};border-radius:10px;padding:10px 14px;"


QSS_TEMPLATE = """
* {{ font-family: 'Segoe UI', 'Noto Sans', Arial; font-size: 14px; color: {text}; }}
QMainWindow, QDialog, QWidget#page {{ background: {bg}; }}
QWidget {{ background: transparent; }}
QToolTip {{ background: {card}; color: {text}; border: {bw}px solid {border}; padding: 6px; }}
#sidebar {{ background: {panel}; border-right: {bw}px solid {border}; }}
#brand {{ font-size: 17px; font-weight: 700; padding: 18px 16px 4px 16px; }}
#brandSub {{ color: {muted}; font-size: 12px; padding: 0 16px 14px 16px; }}
#nav {{ background: transparent; border: none; outline: none; }}
#nav::item {{ padding: 12px 16px; margin: 3px 10px; border-radius: 10px; color: {muted}; }}
#nav::item:hover {{ background: {card}; color: {text}; }}
#nav::item:selected {{ background: {accent}; color: {on_accent}; }}
#h1 {{ font-size: 24px; font-weight: 700; }}
#h2 {{ font-size: 16px; font-weight: 600; }}
#muted {{ color: {muted}; }}
QFrame#card, QFrame#panel {{ background: {card}; border: {bw}px solid {border}; border-radius: 16px; }}
QLabel#cardName {{ color: {muted}; font-size: 13px; font-weight: 600; }}
QLabel#cardValue {{ font-size: 38px; font-weight: 700; }}
QLabel#cardUnit {{ color: {muted}; font-size: 15px; }}
QLabel#badge {{ background: {warn_bg}; color: {warn}; border-radius: 8px; padding: 1px 8px; font-size: 11px; }}
QPushButton {{ background: {card}; border: {bw}px solid {border}; border-radius: 10px; padding: 9px 18px; }}
QPushButton:hover {{ border-color: {accent}; }}
QPushButton:disabled {{ color: {disabled}; border-color: {border}; }}
QPushButton#primary {{ background: {accent}; border: none; color: {on_accent}; font-weight: 600; }}
QPushButton#primary:hover {{ background: {accent_hover}; }}
QPushButton#primary:disabled {{ background: {dis_bg}; color: {dis_fg}; }}
QPushButton#danger {{ background: {crit}; border: none; color: #000000; font-weight: 600; }}
QPushButton#flat {{ background: transparent; border: none; padding: 4px 8px; color: {muted}; }}
QPushButton#flat:hover {{ color: {text}; }}
QLineEdit, QComboBox, QSpinBox, QPlainTextEdit, QTextBrowser, QTextEdit {{
  background: {panel}; border: {bw}px solid {border}; border-radius: 10px; padding: 8px 10px;
  selection-background-color: {accent}; selection-color: {on_accent}; }}
QLineEdit:focus, QComboBox:focus {{ border-color: {accent}; }}
QComboBox::drop-down {{ border: none; width: 26px; }}
QComboBox QAbstractItemView {{ background: {panel}; border: {bw}px solid {border}; selection-background-color: {accent};
  selection-color: {on_accent}; }}
QTableWidget, QListWidget {{ background: {panel}; border: {bw}px solid {border}; border-radius: 12px;
  gridline-color: {border}; alternate-background-color: {alt}; outline: none; }}
QTableWidget::item {{ padding: 6px; }}
QTableWidget::item:selected, QListWidget::item:selected {{ background: {sel}; color: {text}; }}
QListWidget::item {{ padding: 7px 8px; }}
QHeaderView::section {{ background: {panel}; color: {muted}; border: none; border-bottom: {bw}px solid {border};
  padding: 9px 8px; font-weight: 600; }}
QProgressBar {{ background: {panel}; border: none; border-radius: 6px; height: 10px; text-align: center; color: transparent; }}
QProgressBar::chunk {{ background: {accent}; border-radius: 6px; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: {scroll}; border-radius: 4px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; width: 0; }}
QScrollBar:horizontal {{ background: transparent; height: 10px; }}
QScrollBar::handle:horizontal {{ background: {scroll}; border-radius: 4px; }}
QCheckBox::indicator, QRadioButton::indicator {{ width: 18px; height: 18px; }}
QStatusBar {{ background: {panel}; color: {muted}; border-top: {bw}px solid {border}; }}
QMessageBox {{ background: {bg}; }}
"""


def build_qss(pal: dict) -> str:
    return QSS_TEMPLATE.format(warn_bg=rgba(pal["warn"], 0.18), **pal)


def apply(name: str) -> str:
    """Temayı etkinleştirir (modül sabitlerini ve QSS'i günceller); geçerli tema adını döndürür."""
    global QSS, CURRENT, BG, PANEL, CARD, BORDER, TEXT, MUTED, ACCENT, OK, WARN, CRIT, SERIES_COLORS
    if name not in PALETTES:
        name = DEFAULT_THEME
    pal = PALETTES[name]
    P.clear()
    P.update(pal)
    CURRENT = name
    BG, PANEL, CARD, BORDER = pal["bg"], pal["panel"], pal["card"], pal["border"]
    TEXT, MUTED, ACCENT = pal["text"], pal["muted"], pal["accent"]
    OK, WARN, CRIT = pal["ok"], pal["warn"], pal["crit"]
    SERIES_COLORS = list(pal["series"])
    STATUS_COLOR.clear()
    STATUS_COLOR.update({"ok": OK, "warn": WARN, "crit": CRIT, "none": MUTED})
    QSS = build_qss(pal)
    return name


apply(DEFAULT_THEME)
