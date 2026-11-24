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


def _svg_chart(samples, events, w=860, h=260) -> str:
    """Hız (mavi), devir (turuncu), vites (yeşil, basamaklı) + olay çizgileri; bağımsız SVG."""
    if len(samples) < 2:
        return "<p class='muted'>Grafik verisi yok.</p>"
    t0, span = samples[0].t, max(samples[-1].t - samples[0].t, 1.0)
    L, T, R, B = 40, 14, 12, 24
    pw, ph = w - L - R, h - T - B
    X = lambda t: L + pw * (t - t0) / span                       # noqa: E731
    Y = lambda v, m: T + ph - ph * max(0.0, min(1.0, v / m))     # noqa: E731

    def line(fn, vmax, col, step=False):
        pts, last = [], None
        for s in samples:
            v = fn(s)
            if v is None:
                last = None
                continue
            x, y = X(s.t), Y(v, vmax)
            if step and last is not None:
                pts.append(f"{x:.1f},{last:.1f}")
            pts.append(f"{x:.1f},{y:.1f}")
            last = y
        return f"<polyline fill='none' stroke='{col}' stroke-width='1.6' points='{' '.join(pts)}'/>"
    ev = "".join(
        f"<line x1='{X(t0 + e['t']):.1f}' x2='{X(t0 + e['t']):.1f}' y1='{T}' y2='{T + ph}' stroke='"
        f"{'#cf222e' if e['type'] in ('harsh_accel', 'harsh_brake', 'over_rev', 'cold_abuse') else '#b45309'}' "
        f"stroke-dasharray='3 3'><title>{escape(e['label'])}: {escape(e['text'])}</title></line>" for e in events)
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{w}' height='{h}' style='background:#f5f7fa'>"
            f"<rect x='{L}' y='{T}' width='{pw}' height='{ph}' fill='none' stroke='#c9d1de'/>"
            + line(lambda s: s.speed, 160.0, "#1f6feb") + line(lambda s: s.rpm, 4500.0, "#e8710a")
            + line(lambda s: s.gear, max([5] + [s.gear for s in samples if s.gear]) + 0.5, "#1a7f37", True) + ev +
            f"<text x='{L}' y='{h - 6}' font-size='11' fill='#6b7587'>0</text>"
            f"<text x='{w - 70}' y='{h - 6}' font-size='11' fill='#6b7587'>{int(span // 60)} dk</text></svg>"
            "<p class='muted'>Mavi: hız (0-160 km/s) &middot; Turuncu: devir (0-4500 d/dk) &middot; "
            "Yeşil: tahmini vites &middot; Kesikli çizgi: olay</p>")


def build_trip_html(j: dict, samples: list) -> str:
    """Sürüş raporu. j: trip özet sözlüğü (puan, alt puanlar, olaylar, ipuçları)."""
    m = j.get("metrics", {})
    subs = [(k, v) for k, v in j.get("subs", {}).items()]
    tips = "".join(f"<li class='{'ok' if t['kind'] == 'good' else ''}'>{escape(t['text'])}</li>"
                   for t in j.get("tips", []))
    ev = [(f"{e['t']:.0f} sn", e["label"], e["text"]) for e in j.get("events", [])]
    when = datetime.fromtimestamp(j["start_ts"])
    rt = j.get("ratios") or []
    cnt = j.get("gear_counts") or [0] * len(rt)
    ratios = ", ".join(f"{i + 1}. vites {r:.1f}{'' if cnt[i] >= 40 else ' (tahmin)'}" for i, r in enumerate(rt))
    return f"""<html><head><meta charset="utf-8"><style>{CSS}</style></head><body>
<h1>{escape(APP_NAME)} - Sürüş Raporu</h1>
<p class="muted">{escape(APP_VEHICLE)} &middot; {when:%d.%m.%Y %H:%M} &middot; Sürüm {APP_VERSION}</p>
<h2>Özet</h2>
{_table(["Puan", "Süre", "Mesafe", "Ort. hız", "En yüksek hız", "Rölanti payı"],
        [(j.get("total", "-"), f"{m.get('duration_s', 0) / 60:.0f} dk", f"{m.get('distance_km', 0):.1f} km",
          f"{m.get('avg_speed', 0):.0f} km/s", f"{m.get('max_speed', 0):.0f} km/s",
          f"%{m.get('idle_share', 0) * 100:.0f}")])}
<h2>Alt puanlar</h2>{_table(["Başlık", "Puan"], subs)}
<h2>Koçluk ipuçları</h2><ul>{tips or "<li>-</li>"}</ul>
<h2>Grafik</h2>{_svg_chart(samples, j.get("events", []))}
<h2>Olaylar</h2>{_table(["Zaman", "Olay", "Ayrıntı"], ev)}
<p class="muted">Vites oranları (km/s / 1000 d/dk): {escape(ratios)}. Vites bilgisi araçtan okunmaz; devir/hız oranından tahmin edilir.</p>
<p class="muted">Bu rapor otomatik üretilmiştir. Sürüş sırasında uygulamayı kullanmayın.</p>
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
