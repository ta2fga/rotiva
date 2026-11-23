package com.ta2fga.obd2.core

import kotlin.math.roundToInt

data class Tip(val kind: String, val text: String, val weight: Double)   // kind: "warn" | "good"

/** Türkçe sürüş koçluğu (desktop app/coach.py) + yükseklik/eğim bağlamlı ipuçları. */
fun coachTips(res: ScoreResult, cfg: ScoreCfg = DEFAULT_CFG, limit: Int = 5, events: List<Event> = res.events): List<Tip> {
    if (res.total == null) return emptyList()
    val m = res.metrics
    val subs = res.subs
    val c = m.counts
    val warn = ArrayList<Tip>()
    val good = ArrayList<String>()
    fun w(sub: String, text: String) { warn.add(Tip("warn", text, 100.0 - (subs[sub] ?: 100))) }
    val lo = cfg.idealShiftLo.roundToInt(); val hi = cfg.idealShiftHi.roundToInt()

    val avg = m.avgShiftRpm
    if (avg != null && m.upshiftCount >= 2) {
        if (avg > hi + 300) w("Vites kullanımı / devir", "Vitesi ortalama $avg d/dk'da büyütüyorsunuz; dizel motorda $lo-$hi d/dk arası yakıt tasarrufu sağlar.")
        else if (avg < lo - 150) w("Vites kullanımı / devir", "Vitesi ortalama $avg d/dk'da büyütüyorsunuz; çok erken büyütmek motoru zorlar. $lo-$hi d/dk arası ideal.")
    }
    c["over_rev"]?.let { w("Vites kullanımı / devir", "$it kez ${cfg.overRevRpm.roundToInt()} d/dk üzerine çıktınız (en yüksek ${m.maxRpm.roundToInt()}); dizelde bu devirden sonra güç artmaz, yakıt ve aşınma artar. Vitesi daha erken büyütün.") }
    c["lugging"]?.let { w("Vites kullanımı / devir", "$it kez gazı yüklü şekilde ${cfg.lugRpm.roundToInt()} d/dk altında zorladınız; turbo ve volan için zararlıdır. Bu devirde bir vites küçültün.") }

    // --- yükseklik / eğim bağlamı
    val upEv = events.filter { it.type == "lug_uphill" }
    if (upEv.isNotEmpty()) {
        val steep = upEv.maxOf { it.avgGrade }
        w("Eğim yönetimi", "Toplam ${tr1(m.ascent, 0)} m tırmanış yaptınız; ${upEv.size} kez %${tr1(steep, 0)} civarı yokuşta düşük devirde yüklendiniz. %6'dan dik yokuşlarda 2. vitese erken geçmek motoru zorlamayı önler.")
    } else if (m.ascent >= 150 && (m.maxUpGrade ?: 0.0) >= 6) {
        good.add("Toplam ${tr1(m.ascent, 0)} m tırmanışta (en dik %${tr1(m.maxUpGrade ?: 0.0, 0)}) motoru zorlamadınız.")
    }
    val dEv = events.filter { it.type == "descent_brake" }
    if (dEv.isNotEmpty()) {
        val e = dEv.maxByOrNull { it.lenKm }!!
        w("Eğim yönetimi", "Uzun inişlerde (${tr1(e.lenKm)} km, ortalama ${tr1(e.avgGrade, 0)}%) frene sürekli basmak yerine motor freni kullanın: bir veya iki vites küçültüp gazdan çekin; fren balatası ısınmaz ve soluklanır.")
    }
    val brakeOnDescent = events.count { it.type == "harsh_brake" && "iniş" in it.text }
    if (brakeOnDescent > 0) {
        w("Frenleme", "İnişte $brakeOnDescent kez sert frenleme var. İnişe girmeden vites küçültüp motor freniyle yavaşlayın.")
    }
    c["harsh_corner"]?.let { w("Viraj", "$it virajda yanal ivme ${tr1(cfg.cornerG, 2)} g üzerine çıktı (en yüksek ${tr1(m.maxLatG, 2)} g). Virajlara daha yavaş girin, çıkışta hızlanın.") }

    c["harsh_accel"]?.let { w("Hızlanma", "$it sert kalkış/hızlanma var (> ${tr1(cfg.harshAccelMs2)} m/s²). Gazı kademeli verin; yumuşak hızlanma yakıtı belirgin azaltır.") }
    if (brakeOnDescent == 0) c["harsh_brake"]?.let { w("Frenleme", "$it ani frenleme var. Trafiği ve ışıkları önceden okuyup gazı bırakarak yavaşlayın; fren balatası ve yakıt tasarrufu için.") }
    c["cold_abuse"]?.let { w("Soğuk motor", "Motor soğukken (su < ${cfg.coldCoolant.roundToInt()} °C) ${cfg.coldRpm.roundToInt()} d/dk üzerine çıktınız. İlk dakikalarda 2500 d/dk altında, hafif yükle sürün.") }
    if (c["long_idle"] != null) w("Rölanti", "${c["long_idle"]} kez ${(cfg.longIdleS / 60).roundToInt()} dakikadan uzun rölantide beklediniz. Uzun beklemelerde motoru kapatmak yakıt tasarrufu sağlar.")
    else if (m.idleShare > cfg.idleShareOk) w("Rölanti", "Yolculuğun %${(m.idleShare * 100).roundToInt()}'i rölantide geçti; mümkünse beklemeyi azaltın.")
    if (c["unstable_speed"] != null) w("Hız istikrarı / Eko", "Otoyolda hızınız sık sık dalgalandı. Sabit gaz ve (varsa) hız sabitleyici daha ekonomik; 20 sn içinde 15 km/s'den fazla oynamayın.")
    else if (m.meanAbsAccel > cfg.ecoAccelOk + 0.2) w("Hız istikrarı / Eko", "Sık hızlanıp yavaşlıyorsunuz; akıcı ve öngörülü sürüş yakıtı azaltır.")

    warn.sortByDescending { it.weight }
    val far = m.distanceKm >= 0.5
    if ((subs["Frenleme"] ?: 0) >= 95 && far) good.add("Frenlemeleriniz yumuşak, böyle devam.")
    if ((subs["Hızlanma"] ?: 0) >= 95 && far) good.add("Hızlanmalarınız dengeli ve ekonomik.")
    if (avg != null && avg >= lo - 150 && avg <= hi + 300 && m.upshiftCount >= 2) good.add("Vites büyütme devriniz ($avg d/dk) dizel için ideal aralıkta.")
    if ((subs["Soğuk motor"] ?: 0) == 100 && far) good.add("Motoru soğukken zorlamadınız.")
    if (dEv.isEmpty() && m.longestDescentKm >= 0.5) good.add("Uzun inişte (${tr1(m.longestDescentKm)} km) frene sürekli basmadınız; motor freni iyi kullanılmış.")
    val take = maxOf(3, limit - minOf(2, good.size))
    val out = ArrayList<Tip>(warn.take(take))
    good.take(limit - out.size).forEach { out.add(Tip("good", it, 0.0)) }
    if (out.isEmpty()) out.add(Tip("good", "Belirgin bir sorun yok; sürüşünüz ekonomik ve düzgün.", 0.0))
    return out.take(limit)
}
