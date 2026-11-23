"""Rotiva simgesi: tek geometriden (aşağıdaki sabitler) üretilir - masaüstü .ico/.png, SVG ve Android vektörleri.

Işaret: kalın bir "R"; bacağı kıvrımlı bir yola dönüşür ve konum noktasında biter; arkada belli belirsiz bir yükseklik profili.
"""
from PySide6.QtCore import Qt, QRectF, QBuffer, QByteArray
from PySide6.QtGui import QPixmap, QPainter, QIcon

# 108x108 uzayı (Android adaptif simge ızgarası); işaret 66x66 güvenli bölgede (21..87)
STEM = "M36,29 V81"
BOWL = "M36,29 H54 A12.5,12.5 0 0 1 54,54 H36"
LEG = "M47,54 C60,59 62,67 52,72 C44,77 55,84 71,80"
RIDGE = "M21,87 L21,77 C33,70 40,81 52,75 S74,66 87,72 L87,87 Z"
DOT = (79.0, 79.0, 5.5)
STROKE_W = 9
BG1, BG2 = "#0B1220", "#111C33"
ACC1, ACC2 = "#3B82F6", "#22D3EE"
WARM = "#F97316"


def icon_svg(rounded: bool = True, scale: float = 1.3) -> str:
    """Tam simge SVG'si (masaüstü/README). scale: işaretin büyütülmesi (Android'de 1.0 = güvenli bölge)."""
    rx = 24 if rounded else 0
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">
  <defs>
    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="108" y2="108">
      <stop offset="0" stop-color="{BG1}"/><stop offset="1" stop-color="{BG2}"/></linearGradient>
    <linearGradient id="m" gradientUnits="userSpaceOnUse" x1="21" y1="21" x2="87" y2="87">
      <stop offset="0" stop-color="{ACC1}"/><stop offset="1" stop-color="{ACC2}"/></linearGradient>
    <clipPath id="c"><rect width="108" height="108" rx="{rx}"/></clipPath>
  </defs>
  <rect width="108" height="108" rx="{rx}" fill="url(#bg)"/>
  <g clip-path="url(#c)"><path transform="translate(54 54) scale({scale}) translate(-54 -54)" d="{RIDGE}" fill="url(#m)" fill-opacity="0.22"/></g>
  <g transform="translate(54 54) scale({scale}) translate(-54 -54)">
    <path d="{STEM} {BOWL} {LEG}" fill="none" stroke="url(#m)" stroke-width="{STROKE_W}" stroke-linecap="round" stroke-linejoin="round"/>
    <circle cx="{DOT[0]}" cy="{DOT[1]}" r="{DOT[2]}" fill="{WARM}"/>
  </g>
</svg>
"""


def make_pixmap(size: int = 256) -> QPixmap:
    from PySide6.QtSvg import QSvgRenderer
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    QSvgRenderer(QByteArray(icon_svg().encode("utf-8"))).render(p, QRectF(0, 0, size, size))
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
