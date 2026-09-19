package io.mo.glassmic.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.mo.glassmic.R
import io.mo.glassmic.audio.BandSettings
import io.mo.glassmic.proto.AudioBand
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
internal fun AudioBandSection(config: AudioBand, onEnabled: (Boolean) -> Unit, onBand: (Int, Int) -> Unit) {
    val band = BandSettings.normalized(config.enabled, config.lowHz, config.highHz)
    var lowText by remember(band.lowHz, band.highHz) { mutableStateOf(band.lowHz.toString()) }
    var highText by remember(band.lowHz, band.highHz) { mutableStateOf(band.highHz.toString()) }
    val low = lowText.toIntOrNull()
    val high = highText.toIntOrNull()
    val valid = low != null && high != null && low >= BandSettings.MIN_HZ &&
        low < high && high <= BandSettings.MAX_HZ
    val apply = {
        // Read state at release time; the last drag event may precede recomposition.
        val latestLow = lowText.toIntOrNull()
        val latestHigh = highText.toIntOrNull()
        if (latestLow != null && latestHigh != null && latestLow >= BandSettings.MIN_HZ &&
            latestLow < latestHigh && latestHigh <= BandSettings.MAX_HZ) {
            onBand(latestLow, latestHigh)
        }
    }
    val preset = when {
        !band.enabled -> 0
        band.lowHz == 100 && band.highHz == 8000 -> 1
        band.lowHz == 300 && band.highHz == 3400 -> 2
        else -> 3
    }
    Section(stringResource(R.string.settings_band_title)) {
        SwitchRow(
            label = stringResource(R.string.settings_band_enabled),
            hint = stringResource(R.string.settings_band_hint),
            checked = band.enabled,
            onChange = onEnabled
        )
        DropdownPickerRow(
            title = stringResource(R.string.settings_band_preset),
            current = preset,
            options = listOf(
                stringResource(R.string.settings_band_original) to 0,
                stringResource(R.string.settings_band_wide) to 1,
                stringResource(R.string.settings_band_narrow) to 2,
                stringResource(R.string.settings_band_custom) to 3
            ),
            onSelect = {
                when (it) {
                    0 -> onEnabled(false)
                    1 -> { lowText = "100"; highText = "8000"; onBand(100, 8000) }
                    2 -> { lowText = "300"; highText = "3400"; onBand(300, 3400) }
                    else -> onEnabled(true)
                }
            }
        )
        if (band.enabled) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FrequencyControl(
                    stringResource(R.string.settings_band_low), lowText,
                    (low ?: band.lowHz).coerceIn(BandSettings.MIN_HZ, BandSettings.MAX_HZ - 1),
                    isError = !valid,
                    onText = { lowText = it },
                    onSlide = {
                        lowText = it.coerceAtMost((high ?: band.highHz).coerceIn(21, 20000) - 1).toString()
                    },
                    onFinished = apply
                )
                FrequencyControl(
                    stringResource(R.string.settings_band_high), highText,
                    (high ?: band.highHz).coerceIn(BandSettings.MIN_HZ + 1, BandSettings.MAX_HZ),
                    isError = !valid,
                    onText = { highText = it },
                    onSlide = {
                        highText = it.coerceAtLeast((low ?: band.lowHz).coerceIn(20, 19999) + 1).toString()
                    },
                    onFinished = apply
                )
                if (!valid) Text(
                    stringResource(R.string.settings_band_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = apply, enabled = valid) {
                    Text(stringResource(R.string.settings_band_apply))
                }
                Text(stringResource(R.string.settings_band_preview_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FrequencyControl(
    label: String, text: String, value: Int, isError: Boolean,
    onText: (String) -> Unit, onSlide: (Int) -> Unit, onFinished: () -> Unit
) {
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) onText(it) },
        label = { Text(label) },
        suffix = { Text("Hz") },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    val minLog = ln(BandSettings.MIN_HZ.toFloat())
    val span = ln(BandSettings.MAX_HZ.toFloat()) - minLog
    Slider(
        value = ((ln(value.toFloat()) - minLog) / span).coerceIn(0f, 1f),
        onValueChange = { onSlide(exp(minLog + it * span).roundToInt().coerceIn(20, 20000)) },
        onValueChangeFinished = onFinished
    )
}
