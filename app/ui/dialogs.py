"""Diyaloglar: değer seçici, hakkında, keşfedilen ID kaydı."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QDialog, QVBoxLayout, QHBoxLayout, QLineEdit, QListWidget, QListWidgetItem,
                               QLabel, QPushButton, QFormLayout, QComboBox, QMessageBox)

from .. import APP_NAME, APP_VERSION, APP_VEHICLE
from ..pids import is_low_conf
from ..safe_eval import compile_formula, FormulaError
from . import theme


class ValuePickerDialog(QDialog):
    """Tüm değerlerden (standart PID + PSA DID) aranabilir çoklu seçim."""

    def __init__(self, items, selected, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Panoya değer ekle")
        self.resize(520, 620)
        l = QVBoxLayout(self)
        l.setContentsMargins(20, 20, 20, 20)
        l.setSpacing(12)
        h = QLabel("Panoda görmek istediğiniz değerleri işaretleyin")
        h.setObjectName("h2")
        l.addWidget(h)
        self.search = QLineEdit()
        self.search.setPlaceholderText("Ara: devir, yakıt, sıcaklık...")
        self.search.textChanged.connect(self._filter)
        l.addWidget(self.search)
        self.list = QListWidget()
        for it in sorted(items, key=lambda i: (i.category, i.name)):
            tag = f"  ⚠ doğrulanmamış" if it.source == "psa" and is_low_conf(it.confidence) else ""
            w = QListWidgetItem(f"{it.name}   ·   {it.category}{tag}")
            w.setFlags(w.flags() | Qt.ItemIsUserCheckable)
            w.setCheckState(Qt.Checked if it.key in selected else Qt.Unchecked)
            w.setData(Qt.UserRole, it.key)
            self.list.addItem(w)
        l.addWidget(self.list, 1)
        row = QHBoxLayout()
        row.addStretch(1)
        c = QPushButton("İptal")
        c.clicked.connect(self.reject)
        ok = QPushButton("Kaydet")
        ok.setObjectName("primary")
        ok.clicked.connect(self.accept)
        row.addWidget(c)
        row.addWidget(ok)
        l.addLayout(row)

    def _filter(self, text):
        t = text.lower()
        for i in range(self.list.count()):
            self.list.item(i).setHidden(t not in self.list.item(i).text().lower())

    def selected_keys(self) -> list:
        return [self.list.item(i).data(Qt.UserRole) for i in range(self.list.count())
                if self.list.item(i).checkState() == Qt.Checked]



def show_about(parent):
    QMessageBox.about(parent, "Hakkında",
                      f"<h2>{APP_NAME}</h2><p>Sürüm {APP_VERSION}</p><p>{APP_VEHICLE} için ELM327 tabanlı "
                      "OBD2 tanı yazılımı.</p><p>Yasal uyarı: Bu yazılım bilgilendirme amaçlıdır; "
                      "araç üzerinde yapılan işlemlerin sorumluluğu kullanıcıya aittir. Motor çalışırken "
                      "veya araç hareket halindeyken tanı işlemi yapmayın.</p>")


class SaveDidDialog(QDialog):
    """Keşfedilen kimliği ad/formül/birim ile kaydetmek için."""

    def __init__(self, ecu_name: str, service: int, ident: int, hex_resp: str, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Keşfedilen değeri kaydet")
        self.setMinimumWidth(460)
        f = QFormLayout(self)
        f.setContentsMargins(24, 24, 24, 24)
        f.setSpacing(12)
        idtxt = f"{ident:04X}" if service == 0x22 else f"{ident:02X}"
        f.addRow(QLabel(f"<b>{ecu_name}</b> · servis {service:02X} · ID {idtxt}<br>"
                        f"<span style='color:{theme.MUTED}'>Yanıt: {hex_resp}</span>"))
        self.name = QLineEdit(f"Keşif {idtxt}")
        self.formula = QLineEdit("A")
        self.unit = QLineEdit()
        self.formula.setToolTip("A, B, C, D = yanıt verisi baytları. Örn: (A*256+B)/10-40")
        f.addRow("Ad", self.name)
        f.addRow("Formül (A,B,C,D)", self.formula)
        f.addRow("Birim", self.unit)
        row = QHBoxLayout()
        c = QPushButton("İptal")
        c.clicked.connect(self.reject)
        ok = QPushButton("Kaydet")
        ok.setObjectName("primary")
        ok.clicked.connect(self._ok)
        row.addStretch(1)
        row.addWidget(c)
        row.addWidget(ok)
        f.addRow(row)

    def _ok(self):
        try:
            compile_formula(self.formula.text())
        except FormulaError as e:
            QMessageBox.warning(self, "Formül geçersiz", str(e))
            return
        if not self.name.text().strip():
            return
        self.accept()
