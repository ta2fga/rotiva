"""Kod ile üretilen uygulama simgesi."""
from PySide6.QtCore import Qt, QRectF, QBuffer, QByteArray
from PySide6.QtGui import QPixmap, QPainter, QColor, QPen, QIcon, QBrush, QFont


def make_pixmap(size: int = 256) -> QPixmap:
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    s = size
    p.setBrush(QBrush(QColor("#1b232d")))
    p.setPen(Qt.NoPen)
    p.drawRoundedRect(QRectF(0, 0, s, s), s * 0.22, s * 0.22)
    pen = QPen(QColor("#3b82f6"), s * 0.09, Qt.SolidLine, Qt.RoundCap)
    p.setPen(pen)
    r = QRectF(s * 0.18, s * 0.18, s * 0.64, s * 0.64)
    p.drawArc(r, 210 * 16, -240 * 16)
    p.setPen(QPen(QColor("#22c55e"), s * 0.07, Qt.SolidLine, Qt.RoundCap))
    p.drawLine(int(s * 0.5), int(s * 0.5), int(s * 0.68), int(s * 0.34))
    p.setPen(QColor("#e6ebf2"))
    f = QFont("Segoe UI", max(6, int(s * 0.12)))
    f.setBold(True)
    p.setFont(f)
    p.drawText(QRectF(0, s * 0.58, s, s * 0.25), Qt.AlignCenter, "OBD")
    p.end()
    return pm


def app_icon() -> QIcon:
    ic = QIcon()
    for sz in (16, 32, 48, 64, 128, 256):
        ic.addPixmap(make_pixmap(sz))
    return ic


def write_ico(path: str):
    """PNG içeren çok boyutlu .ico dosyası yazar."""
    import struct
    sizes = (16, 32, 48, 64, 128, 256)
    blobs = []
    for sz in sizes:
        ba = QByteArray()
        buf = QBuffer(ba)
        buf.open(QBuffer.WriteOnly)
        make_pixmap(sz).save(buf, "PNG")
        blobs.append(bytes(ba))
    head = struct.pack("<HHH", 0, 1, len(sizes))
    off = 6 + 16 * len(sizes)
    entries = b""
    for sz, b in zip(sizes, blobs):
        entries += struct.pack("<BBBBHHII", sz % 256, sz % 256, 0, 0, 1, 32, len(b), off)
        off += len(b)
    with open(path, "wb") as f:
        f.write(head + entries + b"".join(blobs))
