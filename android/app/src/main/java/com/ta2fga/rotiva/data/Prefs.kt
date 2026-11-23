package com.ta2fga.rotiva.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    var gearJson: JSONObject?
        get() = sp.getString("gear", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) { sp.edit().putString("gear", v?.toString()).apply() }

    var lastDevice: String?
        get() = sp.getString("last_device", null)
        set(v) { sp.edit().putString("last_device", v).apply() }
    var lastDeviceName: String?
        get() = sp.getString("last_device_name", null)
        set(v) { sp.edit().putString("last_device_name", v).apply() }
}
