"""Koyu tema (tek vurgu rengi, yuvarlak kartlar, geniş boşluklar)."""

BG = "#0f1419"
PANEL = "#161c24"
CARD = "#1b232d"
BORDER = "#263140"
TEXT = "#e6ebf2"
MUTED = "#8b95a5"
ACCENT = "#3b82f6"
OK = "#22c55e"
WARN = "#f59e0b"
CRIT = "#ef4444"
STATUS_COLOR = {"ok": OK, "warn": WARN, "crit": CRIT, "none": MUTED}

QSS = f"""
* {{ font-family: 'Segoe UI', 'Noto Sans', Arial; font-size: 14px; color: {TEXT}; }}
QMainWindow, QDialog, QWidget#page {{ background: {BG}; }}
QWidget {{ background: transparent; }}
QToolTip {{ background: {CARD}; color: {TEXT}; border: 1px solid {BORDER}; padding: 6px; }}
#sidebar {{ background: {PANEL}; border-right: 1px solid {BORDER}; }}
#brand {{ font-size: 17px; font-weight: 700; padding: 18px 16px 4px 16px; }}
#brandSub {{ color: {MUTED}; font-size: 12px; padding: 0 16px 14px 16px; }}
#nav {{ background: transparent; border: none; outline: none; }}
#nav::item {{ padding: 12px 16px; margin: 3px 10px; border-radius: 10px; color: {MUTED}; }}
#nav::item:hover {{ background: {CARD}; color: {TEXT}; }}
#nav::item:selected {{ background: {ACCENT}; color: white; }}
#h1 {{ font-size: 24px; font-weight: 700; }}
#h2 {{ font-size: 16px; font-weight: 600; }}
#muted {{ color: {MUTED}; }}
QFrame#card, QFrame#panel {{ background: {CARD}; border: 1px solid {BORDER}; border-radius: 16px; }}
QLabel#cardName {{ color: {MUTED}; font-size: 13px; font-weight: 600; }}
QLabel#cardValue {{ font-size: 38px; font-weight: 700; }}
QLabel#cardUnit {{ color: {MUTED}; font-size: 15px; }}
QLabel#badge {{ background: rgba(245,158,11,0.15); color: {WARN}; border-radius: 8px; padding: 1px 8px; font-size: 11px; }}
QPushButton {{ background: {CARD}; border: 1px solid {BORDER}; border-radius: 10px; padding: 9px 18px; }}
QPushButton:hover {{ border-color: {ACCENT}; }}
QPushButton:disabled {{ color: #5b6677; border-color: {BORDER}; }}
QPushButton#primary {{ background: {ACCENT}; border: none; color: white; font-weight: 600; }}
QPushButton#primary:hover {{ background: #5393f7; }}
QPushButton#primary:disabled {{ background: #27344a; color: #7d8ba0; }}
QPushButton#danger {{ background: {CRIT}; border: none; color: white; font-weight: 600; }}
QPushButton#flat {{ background: transparent; border: none; padding: 4px 8px; color: {MUTED}; }}
QPushButton#flat:hover {{ color: {TEXT}; }}
QLineEdit, QComboBox, QSpinBox, QPlainTextEdit, QTextBrowser, QTextEdit {{
  background: {PANEL}; border: 1px solid {BORDER}; border-radius: 10px; padding: 8px 10px;
  selection-background-color: {ACCENT}; }}
QLineEdit:focus, QComboBox:focus {{ border-color: {ACCENT}; }}
QComboBox::drop-down {{ border: none; width: 26px; }}
QComboBox QAbstractItemView {{ background: {PANEL}; border: 1px solid {BORDER}; selection-background-color: {ACCENT}; }}
QTableWidget, QListWidget {{ background: {PANEL}; border: 1px solid {BORDER}; border-radius: 12px;
  gridline-color: {BORDER}; alternate-background-color: #19212b; outline: none; }}
QTableWidget::item {{ padding: 6px; }}
QTableWidget::item:selected, QListWidget::item:selected {{ background: #23406f; color: white; }}
QListWidget::item {{ padding: 7px 8px; }}
QHeaderView::section {{ background: {PANEL}; color: {MUTED}; border: none; border-bottom: 1px solid {BORDER};
  padding: 9px 8px; font-weight: 600; }}
QProgressBar {{ background: {PANEL}; border: none; border-radius: 6px; height: 10px; text-align: center; color: transparent; }}
QProgressBar::chunk {{ background: {ACCENT}; border-radius: 6px; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: #2e3b4d; border-radius: 4px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; width: 0; }}
QScrollBar:horizontal {{ background: transparent; height: 10px; }}
QScrollBar::handle:horizontal {{ background: #2e3b4d; border-radius: 4px; }}
QCheckBox::indicator, QRadioButton::indicator {{ width: 18px; height: 18px; }}
QStatusBar {{ background: {PANEL}; color: {MUTED}; border-top: 1px solid {BORDER}; }}
QMessageBox {{ background: {BG}; }}
"""
