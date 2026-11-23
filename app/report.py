"""HTML / PDF tanı raporu."""
from __future__ import annotations

from datetime import datetime
from html import escape

from . import APP_NAME, APP_VERSION, APP_VEHICLE

CSS = """body{font-family:Segoe UI,Arial,sans-serif;color:#1d2433;margin:24px}
h1{color:#1f6feb;margin-bottom:0}h2{border-bottom:2px solid #1f6feb;padding-bottom:4px;margin-top:26px;color:#1d2433}
table{border-collapse:collapse;width:100%}th,td{border:1px solid #c9d1de;padding:6px 8px;text-align:left;font-size:13px}
th{background:#eaf1fd}.muted{color:#6b7587;font-size:12px}.ok{color:#1a7f37}.crit{color:#cf222e}"""


def _table(headers, rows):
    if not rows:
        return "<p class='muted'>Kayıt yok.</p>"
    h = "".join(f"<th>{escape(x)}</th>" for x in headers)
    b = "".join("<tr>" + "".join(f"<td>{escape(str(c))}</td>" for c in r) + "</tr>" for r in rows)
    return f"<table><tr>{h}</tr>{b}</table>"


def build_html(info: dict, dtcs: list, snapshot: list, meta: dict, notes: str = "") -> str:
    """dtcs: [(kod, tür, ecu, açıklama)], snapshot: [(ad, değer, birim)]"""
    meta_rows = [(k, v) for k, v in meta.items() if v]
    crit = "<p class='crit'><b>%d arıza kodu bulundu.</b></p>" % len(dtcs) if dtcs else \
        "<p class='ok'><b>Kayıtlı arıza kodu bulunamadı.</b></p>"
    return f"""<html><head><meta charset="utf-8"><style>{CSS}</style></head><body>
<h1>{escape(APP_NAME)} - Tanı Raporu</h1>
<p class="muted">{escape(APP_VEHICLE)} &middot; {datetime.now():%d.%m.%Y %H:%M} &middot; Sürüm {APP_VERSION}</p>
<h2>Araç / Müşteri</h2>{_table(["Alan", "Değer"], meta_rows)}
<h2>Araç Bilgisi</h2>{_table(["Alan", "Değer"], list(info.items()))}
<h2>Arıza Kodları</h2>{crit}{_table(["Kod", "Tür", "ECU", "Açıklama"], dtcs)}
<h2>Canlı Veri Anlık Görüntüsü</h2>{_table(["Değer", "Ölçüm", "Birim"], snapshot)}
<h2>Notlar</h2><p>{escape(notes).replace(chr(10), "<br>") or "-"}</p>
<p class="muted">Bu rapor otomatik üretilmiştir; teşhis niyetine yetkili servis değerlendirmesi gerekir.</p>
</body></html>"""


def save_html(path: str, html: str):
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)


def save_pdf(path: str, html: str):
    from PySide6.QtGui import QTextDocument, QPdfWriter, QPageSize
    doc = QTextDocument()
    doc.setHtml(html)
    w = QPdfWriter(path)
    w.setPageSize(QPageSize(QPageSize.A4))
    doc.print_(w)
