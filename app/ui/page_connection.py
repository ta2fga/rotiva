"""Sayfa 1: Bağlantı (Seri/Bluetooth COM, Bluetooth LE, WiFi, Demo)."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QWidget, QVBoxLayout, QHBoxLayout, QFrame, QRadioButton, QComboBox, QPushButton,
                               QLabel, QLineEdit, QSpinBox, QPlainTextEdit, QStackedWidget, QListWidget,
                               QListWidgetItem, QButtonGroup, QMessageBox, QCheckBox)

from ..elm327 import ELM327, SerialTransport, TcpTransport, ElmError
from ..simulator import SimTransport
from ..state import AppState, run_bg
from . import theme
from .widgets import page_header

BAUDS = ["38400", "9600", "115200", "57600", "19200", "230400", "500000"]
PROTOCOLS = [("Otomatik (ATSP0)", "0"), ("ISO 15765-4 CAN 11/500 (6)", "6"), ("ISO 15765-4 CAN 11/250 (8)", "8"),
             ("ISO 15765-4 CAN 29/500 (7)", "7"), ("ISO 14230-4 KWP fast (5)", "5"), ("ISO 9141-2 (3)", "3")]


class ConnectionPage(QWidget):
    def __init__(self, state: AppState):
        super().__init__()
        self.setObjectName("page")
        self.state = state
        self.ble_devices = []
        l = QVBoxLayout(self)
        l.setContentsMargins(32, 28, 32, 24)
        l.setSpacing(16)
        l.addWidget(page_header("Bağlantı", "Adaptör türünü seçin ve bağlanın. Aracınız yoksa Demo / Simülatör modunu deneyin."))

        card = QFrame()
        card.setObjectName("card")
        cl = QVBoxLayout(card)
        cl.setContentsMargins(22, 20, 22, 20)
        cl.setSpacing(14)
        modes = QHBoxLayout()
        self.group = QButtonGroup(self)
        self.radios = {}
        for i, (key, txt) in enumerate([("serial", "Seri / Bluetooth (COM)"), ("ble", "Bluetooth LE"),
                                        ("wifi", "WiFi (TCP)"), ("demo", "Demo / Simülatör")]):
            r = QRadioButton(txt)
            self.group.addButton(r, i)
            self.radios[key] = r
            modes.addWidget(r)
        modes.addStretch(1)
        cl.addLayout(modes)
        self.stack = QStackedWidget()
        cl.addWidget(self.stack)
        self.stack.addWidget(self._serial_panel())
        self.stack.addWidget(self._ble_panel())
        self.stack.addWidget(self._wifi_panel())
        self.stack.addWidget(self._demo_panel())
        self.group.idClicked.connect(self.stack.setCurrentIndex)
        prow = QHBoxLayout()
        prow.addWidget(QLabel("Protokol:"))
        self.proto = QComboBox()
        for n, v in PROTOCOLS:
            self.proto.addItem(n, v)
        prow.addWidget(self.proto)
        prow.addStretch(1)
        self.status = QLabel("Bağlı değil")
        self.status.setObjectName("muted")
        prow.addWidget(self.status)
        self.btn = QPushButton("Bağlan")
        self.btn.setObjectName("primary")
        self.btn.setMinimumWidth(140)
        self.btn.clicked.connect(self.toggle)
        prow.addWidget(self.btn)
        cl.addLayout(prow)
        l.addWidget(card)

        lh = QLabel("Haberleşme günlüğü")
        lh.setObjectName("h2")
        l.addWidget(lh)
        self.console = QPlainTextEdit()
        self.console.setReadOnly(True)
        self.console.setMaximumBlockCount(600)
        self.console.setStyleSheet("font-family:Consolas,monospace;font-size:12px;")
        l.addWidget(self.console, 1)
        state.log.connect(self.console.appendPlainText)
        state.connection_changed.connect(self._on_conn)
        self._load_conn()
        self.refresh_ports()

    # --- paneller
    def _serial_panel(self):
        w = QWidget()
        h = QHBoxLayout(w)
        h.setContentsMargins(0, 0, 0, 0)
        h.addWidget(QLabel("Port:"))
        self.port = QComboBox()
        self.port.setMinimumWidth(280)
        h.addWidget(self.port)
        rb = QPushButton("↻ Yenile")
        rb.clicked.connect(self.refresh_ports)
        h.addWidget(rb)
        h.addSpacing(16)
        h.addWidget(QLabel("Baud:"))
        self.baud = QComboBox()
        self.baud.addItems(BAUDS)
        self.baud.setEditable(True)
        h.addWidget(self.baud)
        h.addStretch(1)
        return w

    def _ble_panel(self):
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(0, 0, 0, 0)
        h = QHBoxLayout()
        self.scan_btn = QPushButton("🔍 Tara")
        self.scan_btn.clicked.connect(self.scan_ble)
        h.addWidget(self.scan_btn)
        self.scan_lbl = QLabel("Adaptörü takıp kontağı açın, sonra Tara'ya basın. (OBD/ELM/V-LINK adları vurgulanır)")
        self.scan_lbl.setObjectName("muted")
        h.addWidget(self.scan_lbl, 1)
        v.addLayout(h)
        self.ble_list = QListWidget()
        self.ble_list.setMaximumHeight(150)
        v.addWidget(self.ble_list)
        return w

    def _wifi_panel(self):
        w = QWidget()
        h = QHBoxLayout(w)
        h.setContentsMargins(0, 0, 0, 0)
        h.addWidget(QLabel("IP adresi:"))
        self.host = QLineEdit("192.168.0.10")
        h.addWidget(self.host)
        h.addWidget(QLabel("Port:"))
        self.tcp_port = QSpinBox()
        self.tcp_port.setRange(1, 65535)
        self.tcp_port.setValue(35000)
        h.addWidget(self.tcp_port)
        h.addStretch(1)
        return w

    def _demo_panel(self):
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(0, 0, 0, 0)
        l = QLabel("Simülatör gerçek bir C-Elysée 1.6 HDi'nin ölçülmüş yanıtlarını taklit eder (araç gerekmez). "
                   "VIN yok, üretici ECU'ları yanıtsız, yalnızca standart OBD2.")
        l.setObjectName("muted")
        l.setWordWrap(True)
        v.addWidget(l)
        self.full_psa = QCheckBox("PSA tam erişim (üretici ECU'larını da simüle et)")
        v.addWidget(self.full_psa)
        return w

    # --- ayar kalıcılığı
    def _load_conn(self):
        c = self.state.settings.get("conn", {})
        mode = c.get("mode", "demo")
        self.radios.get(mode, self.radios["demo"]).setChecked(True)
        self.stack.setCurrentIndex(self.group.checkedId())
        self.baud.setCurrentText(str(c.get("baud", "38400")))
        self.host.setText(c.get("host", "192.168.0.10"))
        self.tcp_port.setValue(int(c.get("tcp_port", 35000)))
        self._saved_port = c.get("port", "")
        i = self.proto.findData(c.get("proto", "0"))
        self.proto.setCurrentIndex(max(0, i))

    def _save_conn(self, mode):
        self.state.settings.put("conn", {"mode": mode, "baud": self.baud.currentText(), "host": self.host.text(),
                                         "tcp_port": self.tcp_port.value(), "port": self.port.currentData() or "",
                                         "proto": self.proto.currentData()})

    def refresh_ports(self):
        self.port.clear()
        try:
            from serial.tools import list_ports
            for p in sorted(list_ports.comports(), key=lambda x: x.device):
                self.port.addItem(f"{p.device} - {p.description}", p.device)
        except Exception as e:  # noqa: BLE001
            self.console.appendPlainText(f"! Port listesi alınamadı: {e}")
        if self.port.count() == 0:
            self.port.addItem("(port bulunamadı)", "")
        i = self.port.findData(getattr(self, "_saved_port", ""))
        if i >= 0:
            self.port.setCurrentIndex(i)

    def scan_ble(self):
        from .. import ble
        self.scan_btn.setEnabled(False)
        self.scan_lbl.setText("Taranıyor (6 sn)...")

        def ok(res):
            self.scan_btn.setEnabled(True)
            self.ble_list.clear()
            for name, addr, rssi in res:
                it = QListWidgetItem(("★ " if ble.is_obd_name(name) else "   ") + f"{name}   [{addr}]   {rssi} dBm")
                it.setData(Qt.UserRole, addr)
                if ble.is_obd_name(name):
                    it.setForeground(Qt.green)
                self.ble_list.addItem(it)
            if self.ble_list.count():
                self.ble_list.setCurrentRow(0)
            self.scan_lbl.setText(f"{len(res)} cihaz bulundu.")

        def err(e):
            self.scan_btn.setEnabled(True)
            self.scan_lbl.setText(f"Tarama başarısız: {e}")
        run_bg(lambda: ble.scan(6.0), ok, err)

    # --- bağlan / kes
    def current_mode(self) -> str:
        return next(k for k, r in self.radios.items() if r.isChecked())

    def toggle(self):
        if self.state.connected:
            self.state.disconnect()
            return
        mode = self.current_mode()
        try:
            transport = self._make_transport(mode)
        except ElmError as e:
            QMessageBox.warning(self, "Bağlantı", str(e))
            return
        self._save_conn(mode)
        self.btn.setEnabled(False)
        self.status.setText("Bağlanıyor...")
        self.console.clear()
        elm = ELM327(transport, log=self.state.log.emit)
        proto = self.proto.currentData()
        run_bg(lambda: (elm, elm.connect(proto)), lambda r: self._connected(r, mode == "demo"), self._failed)

    def _make_transport(self, mode):
        if mode == "demo":
            return SimTransport(full_psa=self.full_psa.isChecked(), drive=True)
        if mode == "serial":
            port = self.port.currentData()
            if not port:
                raise ElmError("Bir COM portu seçin (Bluetooth adaptörü önce Windows'ta eşleştirilmelidir).")
            return SerialTransport(port, int(self.baud.currentText() or 38400))
        if mode == "wifi":
            return TcpTransport(self.host.text().strip(), self.tcp_port.value())
        from ..ble import BleTransport
        it = self.ble_list.currentItem()
        if not it:
            raise ElmError("Önce Tara ile BLE adaptörü bulup listeden seçin.")
        return BleTransport(it.data(Qt.UserRole))

    def _connected(self, res, demo):
        elm, info = res
        self.btn.setEnabled(True)
        if not info.get("ecu_ok"):
            QMessageBox.information(self, "Uyarı", "Adaptöre bağlanıldı ancak araç ECU'su yanıt vermedi.\n"
                                    "Kontağı açın (motor kapalı) ve tekrar deneyin. Adaptör bilgileri kullanılabilir.")
        self.state.info = info
        self.state.set_elm(elm, demo)
        mode, proto = self.current_mode(), self.proto.currentData()
        self.state.reconnect_factory = lambda: (ELM327(self._make_transport(mode), log=self.state.log.emit),
                                                proto, demo)

    def _failed(self, e):
        self.btn.setEnabled(True)
        self.status.setText("Bağlantı başarısız")
        QMessageBox.warning(self, "Bağlantı hatası", str(e))

    def _on_conn(self, ok):
        self.btn.setEnabled(True)
        self.btn.setText("Bağlantıyı Kes" if ok else "Bağlan")
        if ok:
            i = self.state.info
            self.status.setText(f"● Bağlı  ·  {i.get('version', '')}  ·  {self.state.elm.protocol}")
            self.status.setStyleSheet(f"color:{theme.OK};")
        else:
            self.status.setText("Bağlı değil")
            self.status.setStyleSheet("")
