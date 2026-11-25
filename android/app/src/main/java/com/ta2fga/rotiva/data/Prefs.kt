package com.ta2fga.rotiva.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.ta2fga.rotiva.core.ConnectHint
import com.ta2fga.rotiva.core.DEFAULT_GEAR_COUNT
import com.ta2fga.rotiva.core.clampGearCount
import org.json.JSONArray
import org.json.JSONObject

enum class SpeedUnit(val label: String) { KMH("km/s"), MPH("mph") }
enum class TempUnit(val label: String) { C("°C"), F("°F") }

/** Kalıcı kullanıcı ayarları (desktop app/settings.py karşılığı). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(sp.getString("theme", "Gece Mavisi") ?: "Gece Mavisi")
    val theme: StateFlow<String> = _theme
    fun setTheme(v: String) { _theme.value = v; sp.edit().putString("theme", v).apply() }

    private val _autoStart = MutableStateFlow(sp.getBoolean("auto_start", true))
    val autoStart: StateFlow<Boolean> = _autoStart
    fun setAutoStart(v: Boolean) { _autoStart.value = v; sp.edit().putBoolean("auto_start", v).apply() }

    private val _speedUnit = MutableStateFlow(runCatching { SpeedUnit.valueOf(sp.getString("speed_unit", "KMH")!!) }.getOrDefault(SpeedUnit.KMH))
    val speedUnit: StateFlow<SpeedUnit> = _speedUnit
    fun setSpeedUnit(v: SpeedUnit) { _speedUnit.value = v; sp.edit().putString("speed_unit", v.name).apply() }

    private val _tempUnit = MutableStateFlow(runCatching { TempUnit.valueOf(sp.getString("temp_unit", "C")!!) }.getOrDefault(TempUnit.C))
    val tempUnit: StateFlow<TempUnit> = _tempUnit
    fun setTempUnit(v: TempUnit) { _tempUnit.value = v; sp.edit().putString("temp_unit", v.name).apply() }

    private val _dashboard = MutableStateFlow(
        (sp.getString("dashboard", null) ?: "rpm,speed,coolant,load,frp,atrv").split(",").filter { it.isNotBlank() })
    val dashboard: StateFlow<List<String>> = _dashboard
    fun setDashboard(v: List<String>) { _dashboard.value = v; sp.edit().putString("dashboard", v.joinToString(",")).apply() }
    fun togglePin(key: String) = setDashboard(if (key in _dashboard.value) _dashboard.value - key else _dashboard.value + key)

    /** İleri vites sayısı (4..7). Değişince vites öğrenmesi sıfırlanır (bkz. Engine.setGearCount). */
    private val _gearCount = MutableStateFlow(clampGearCount(sp.getInt("gear_count", DEFAULT_GEAR_COUNT)))
    val gearCount: StateFlow<Int> = _gearCount
    fun setGearCount(v: Int) { val k = clampGearCount(v); _gearCount.value = k; sp.edit().putInt("gear_count", k).apply() }

    var gearJson: JSONObject?
        get() = sp.getString("gear", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) { sp.edit().putString("gear", v?.toString()).apply() }

    /** Rapor hız grafiğinde görünen seriler (encodeSeries biçimi); raporlar arasında korunur. */
    var speedChartSeries: String?
        get() = sp.getString("speed_chart_series", null)
        set(v) { sp.edit().putString("speed_chart_series", v).apply() }

    var lastDevice: String?
        get() = sp.getString("last_device", null)
        set(v) { sp.edit().putString("last_device", v).apply() }
    var lastDeviceName: String?
        get() = sp.getString("last_device_name", null)
        set(v) { sp.edit().putString("last_device_name", v).apply() }

    /** Cihaz başına ELM önbelleği (protokol + desteklenen PID'ler): sonraki bağlantıda protokol araması yapılmaz. */
    fun elmHint(addr: String): ConnectHint? = sp.getString("elm_" + addr.uppercase(), null)?.let { raw ->
        runCatching {
            val j = JSONObject(raw)
            val a = j.getJSONArray("s")
            ConnectHint(j.getString("p"), j.optString("d"), (0 until a.length()).map { a.getInt(it) }.toSet())
        }.getOrNull()
    }

    fun setElmHint(addr: String, h: ConnectHint?) {
        val k = "elm_" + addr.uppercase()
        if (h == null) { sp.edit().remove(k).apply(); return }
        sp.edit().putString(k, JSONObject().put("p", h.protocolNum).put("d", h.protocolDesc)
            .put("s", JSONArray(h.supported.sorted())).toString()).apply()
    }
}
