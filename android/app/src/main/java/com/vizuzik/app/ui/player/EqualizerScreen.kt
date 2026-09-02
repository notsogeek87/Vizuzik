package com.vizuzik.app.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.audio.EqualizerBand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(onBack: () -> Unit, viewModel: EqualizerViewModel = hiltViewModel()) {
    val bands by viewModel.bands.collectAsStateWithLifecycle()
    val eqEnabled by viewModel.equalizerEnabled.collectAsStateWithLifecycle()
    val bassEnabled by viewModel.bassBoostEnabled.collectAsStateWithLifecycle()
    val bassStrength by viewModel.bassBoostStrength.collectAsStateWithLifecycle()
    val virtEnabled by viewModel.virtualizerEnabled.collectAsStateWithLifecycle()
    val virtStrength by viewModel.virtualizerStrength.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Égaliseur") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) } },
                actions = { Switch(checked = eqEnabled, onCheckedChange = viewModel::setEqualizerEnabled) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            if (bands.isEmpty()) {
                Text("Égaliseur indisponible sur cet appareil, ou aucune lecture en cours.")
            } else {
                bands.forEach { band -> EqualizerBandRow(band, enabled = eqEnabled, onChange = { viewModel.setBandLevel(band.index, it) }) }
            }

            Text("Bass Boost", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = bassEnabled, onCheckedChange = viewModel::setBassBoostEnabled)
                Slider(
                    value = bassStrength.toFloat(),
                    onValueChange = { viewModel.setBassBoostStrength(it.toInt()) },
                    valueRange = 0f..1000f,
                    enabled = bassEnabled,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Text("Virtualizer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = virtEnabled, onCheckedChange = viewModel::setVirtualizerEnabled)
                Slider(
                    value = virtStrength.toFloat(),
                    onValueChange = { viewModel.setVirtualizerStrength(it.toInt()) },
                    valueRange = 0f..1000f,
                    enabled = virtEnabled,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun EqualizerBandRow(band: EqualizerBand, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val freqLabel = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000}k" else "${band.centerFreqHz}"
        Text(text = freqLabel, modifier = Modifier.padding(end = 8.dp))
        Slider(
            value = band.currentLevelMillibel.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = band.minLevelMillibel.toFloat()..band.maxLevelMillibel.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(text = "${band.currentLevelMillibel / 100}dB", modifier = Modifier.padding(start = 8.dp))
    }
}
