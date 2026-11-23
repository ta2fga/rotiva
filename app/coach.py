"""Türkçe sürüş koçluğu: olaylardan öncelikli 3-5 somut ipucu + olumlu geri bildirim."""
from __future__ import annotations

from .scoring import CONFIG


def tips(res: dict, cfg: dict = None, limit: int = 5) -> list:
    """-> [{'kind': 'warn'|'good', 'text': str, 'weight': float}] (en önemli önce)"""
    cfg = cfg or CONFIG
    if not res or res.get("total") is None:
        return []
    m, subs = res["metrics"], res["subs"]
    c = m.get("counts", {})
    lo, hi = cfg["ideal_shift"]
    warn, good = [], []

    def W(sub, text):
        warn.append(dict(kind="warn", text=text, weight=100 - subs.get(sub, 100)))

    avg = m.get("avg_shift_rpm")
    if avg and m.get("upshift_count", 0) >= 2:
        if avg > hi + 300:
            W("Vites kullanımı / devir", f"Vitesi ortalama {avg} d/dk'da büyütüyorsunuz; dizel motorda {lo}-{hi} d/dk "
              f"arası yakıt tasarrufu sağlar.")
        elif avg < lo - 150:
            W("Vites kullanımı / devir", f"Vitesi ortalama {avg} d/dk'da büyütüyorsunuz; çok erken büyütmek motoru "
              f"zorlar. {lo}-{hi} d/dk arası ideal.")
    if c.get("over_rev"):
        W("Vites kullanımı / devir", f"{c['over_rev']} kez {cfg['over_rev_rpm']} d/dk üzerine çıktınız (en yüksek "
          f"{m.get('max_rpm', 0):.0f}); dizelde bu devirden sonra güç artmaz, yakıt ve aşınma artar. "
          f"Vitesi daha erken büyütün.")
    if c.get("lugging"):
        W("Vites kullanımı / devir", f"{c['lugging']} kez gazı yüklü şekilde {cfg['lug_rpm']} d/dk altında "
          f"zorladınız; turbo ve volan için zararlıdır. Bu devirde bir vites küçültün.")
    if c.get("harsh_accel"):
        W("Hızlanma", f"{c['harsh_accel']} sert kalkış/hızlanma var (> {cfg['harsh_accel_ms2']} m/s²). Gazı kademeli "
          f"verin; yumuşak hızlanma yakıtı belirgin azaltır.")
    if c.get("harsh_brake"):
        W("Frenleme", f"{c['harsh_brake']} ani frenleme var. Trafiği ve ışıkları önceden okuyup gazı bırakarak "
          f"yavaşlayın; fren balatası ve yakıt tasarrufu için.")
    if c.get("cold_abuse"):
        W("Soğuk motor", f"Motor soğukken (su < {cfg['cold_coolant']} °C) {cfg['cold_rpm']} d/dk üzerine çıktınız. "
          f"İlk dakikalarda 2500 d/dk altında, hafif yükle sürün.")
    if c.get("long_idle"):
        W("Rölanti", f"{c['long_idle']} kez {cfg['long_idle_s'] // 60} dakikadan uzun rölantide beklediniz. "
          f"Uzun beklemelerde motoru kapatmak yakıt tasarrufu sağlar.")
    elif m.get("idle_share", 0) > cfg["idle_share_ok"]:
        W("Rölanti", f"Yolculuğun %{m['idle_share'] * 100:.0f}'i rölantide geçti; mümkünse beklemeyi azaltın.")
    if c.get("unstable_speed"):
        W("Hız istikrarı / Eko", "Otoyolda hızınız sık sık dalgalandı. Sabit gaz ve (varsa) hız sabitleyici daha "
          "ekonomik; 20 sn içinde 15 km/s'den fazla oynamayın.")
    elif m.get("mean_abs_accel", 0) > cfg["eco_accel_ok"] + 0.2:
        W("Hız istikrarı / Eko", "Sık hızlanıp yavaşlıyorsunuz; akıcı ve öngörülü sürüş yakıtı azaltır.")

    warn.sort(key=lambda x: -x["weight"])
    far = m.get("distance_km", 0) >= 0.5
    if subs.get("Frenleme", 0) >= 95 and far:
        good.append("Frenlemeleriniz yumuşak, böyle devam.")
    if subs.get("Hızlanma", 0) >= 95 and far:
        good.append("Hızlanmalarınız dengeli ve ekonomik.")
    if avg and lo - 150 <= avg <= hi + 300 and m.get("upshift_count", 0) >= 2:
        good.append(f"Vites büyütme devriniz ({avg} d/dk) dizel için ideal aralıkta.")
    if subs.get("Soğuk motor", 0) == 100 and far:
        good.append("Motoru soğukken zorlamadınız.")
    out = warn[:max(3, limit - min(2, len(good)))]
    out += [dict(kind="good", text=g, weight=0) for g in good[:limit - len(out)]]
    if not out:
        out = [dict(kind="good", text="Belirgin bir sorun yok; sürüşünüz ekonomik ve düzgün.", weight=0)]
    return out[:limit]
