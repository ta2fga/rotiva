package com.ta2fga.rotiva.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ta2fga.rotiva.core.Item
import com.ta2fga.rotiva.core.Status
import com.ta2fga.rotiva.core.tr1
import com.ta2fga.rotiva.data.SpeedUnit
import com.ta2fga.rotiva.data.TempUnit

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ex = LocalExtra.current
    Card(
        modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ex.card),
        border = BorderStroke(ex.borderWidth.dp, ex.border),
    ) { Column(Modifier.padding(14.dp), content = content) }
}

@Composable
fun statusColor(s: Status): Color {
    val ex = LocalExtra.current
    return when (s) {
        Status.OK -> ex.ok; Status.WARN -> ex.warn; Status.CRIT -> ex.crit; Status.NONE -> ex.muted
    }
}

@Composable
fun KeyValue(k: String, v: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
fun RowSpaced(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Birim dönüşümü: (metin, birim). */
fun formatValue(item: Item, v: Double?, su: SpeedUnit, tu: TempUnit): Pair<String, String> {
    if (v == null) return "—" to unitLabel(item.unit, su, tu)
    var x = v
    when {
        item.unit == "km/s" && su == SpeedUnit.MPH -> x = v * 0.621371
        item.unit == "°C" && tu == TempUnit.F -> x = v * 9 / 5 + 32
    }
    return tr1(x, item.decimals) to unitLabel(item.unit, su, tu)
}

fun unitLabel(u: String, su: SpeedUnit, tu: TempUnit) = when {
    u == "km/s" -> su.label
    u == "°C" -> tu.label
    else -> u
}

fun speedText(kmh: Double?, su: SpeedUnit): String =
    if (kmh == null) "—" else (if (su == SpeedUnit.MPH) kmh * 0.621371 else kmh).let { "%d".format(Math.round(it)) }
