package com.vizuzik.app.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.diagnostics.MediaSessionProbe
import com.vizuzik.app.diagnostics.SessionReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProbeUiState(
    val hasAccess: Boolean = false,
    val reports: List<SessionReport> = emptyList(),
    val lastResult: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class MediaSessionProbeViewModel @Inject constructor(
    private val probe: MediaSessionProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val hasAccess = probe.hasNotificationAccess()
        val reports = probe.activeControllers().map(probe::report)
        _state.update { it.copy(hasAccess = hasAccess, reports = reports) }
    }

    fun textReport(): String = probe.textReport(_state.value.hasAccess, _state.value.reports)

    fun sendTransport(packageName: String, command: TransportCommand) {
        val controller = probe.activeControllers().firstOrNull { it.packageName == packageName }
        if (controller == null) {
            _state.update { it.copy(lastResult = "Session $packageName introuvable — relance la lecture puis actualise.") }
            return
        }
        runCatching {
            when (command) {
                TransportCommand.PLAY -> controller.transportControls.play()
                TransportCommand.PAUSE -> controller.transportControls.pause()
                TransportCommand.NEXT -> controller.transportControls.skipToNext()
                TransportCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
            }
        }.onFailure { error ->
            _state.update { it.copy(lastResult = "${command.name} a échoué : ${error.message}") }
            return
        }
        observeEffect("${command.name} envoyée", packageName)
    }

    /**
     * Le test décisif : demander à l'app de lancer elle-même une recherche.
     * Si ça marche, on peut choisir la musique depuis notre interface ; sinon
     * on ne peut que télécommander ce qui a été lancé ailleurs.
     */
    fun testPlayFromSearch(packageName: String, query: String) {
        if (query.isBlank()) return
        val controller = probe.activeControllers().firstOrNull { it.packageName == packageName }
        if (controller == null) {
            _state.update { it.copy(lastResult = "Session $packageName introuvable — relance la lecture puis actualise.") }
            return
        }
        runCatching { controller.transportControls.playFromSearch(query, null) }
            .onFailure { error ->
                _state.update { it.copy(lastResult = "playFromSearch a échoué : ${error.message}") }
                return
            }
        observeEffect("playFromSearch(\"$query\") envoyée", packageName)
    }

    /** Compare le morceau avant/après pour dire si la commande a vraiment agi. */
    private fun observeEffect(label: String, packageName: String) {
        val before = _state.value.reports.firstOrNull { it.packageName == packageName }
        _state.update { it.copy(busy = true, lastResult = "$label…") }
        viewModelScope.launch {
            delay(2_500)
            refresh()
            val after = _state.value.reports.firstOrNull { it.packageName == packageName }
            val verdict = when {
                after == null -> "$label → la session a disparu."
                before == null -> "$label → état : ${after.state} · ${after.title ?: "sans titre"}"
                after.title != before.title ->
                    "$label → ✅ le morceau a changé : « ${before.title ?: "—"} » → « ${after.title ?: "—"} »"
                after.state != before.state ->
                    "$label → ✅ l'état a changé : ${before.state} → ${after.state}"
                else ->
                    "$label → ❌ aucun changement observé (morceau et état identiques)"
            }
            _state.update { it.copy(busy = false, lastResult = verdict) }
        }
    }
}

enum class TransportCommand { PLAY, PAUSE, NEXT, PREVIOUS }
