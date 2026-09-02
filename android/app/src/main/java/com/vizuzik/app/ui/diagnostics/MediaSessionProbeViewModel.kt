package com.vizuzik.app.ui.diagnostics

import android.media.session.MediaController
import androidx.core.net.toUri
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

    fun textReport(): String =
        probe.textReport(_state.value.hasAccess, _state.value.reports, _state.value.lastResult)

    fun sendTransport(packageName: String, command: TransportCommand) {
        withSession(packageName, command.name) { controller ->
            when (command) {
                TransportCommand.PLAY -> controller.transportControls.play()
                TransportCommand.PAUSE -> controller.transportControls.pause()
                TransportCommand.NEXT -> controller.transportControls.skipToNext()
                TransportCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
            }
        }
    }

    /**
     * Le test décisif : demander à l'app de lancer elle-même une recherche.
     * Si ça marche, on peut choisir la musique depuis notre interface ; sinon
     * on ne peut que télécommander ce qui a été lancé ailleurs.
     */
    fun testPlayFromSearch(packageName: String, query: String) {
        if (query.isBlank()) return
        withSession(packageName, "playFromSearch(\"$query\")") { controller ->
            controller.transportControls.playFromSearch(query, null)
        }
    }

    /** Deezer déclare PLAY_FROM_URI : une URL deezer.com pointe un morceau précis. */
    fun testPlayFromUri(packageName: String, uri: String) {
        if (uri.isBlank()) return
        withSession(packageName, "playFromUri(\"$uri\")") { controller ->
            controller.transportControls.playFromUri(uri.toUri(), null)
        }
    }

    /** Le plus fiable : on rejoue un identifiant que l'app a elle-même publié dans sa file. */
    fun testPlayFromMediaId(packageName: String, mediaId: String) {
        withSession(packageName, "playFromMediaId(\"$mediaId\")") { controller ->
            controller.transportControls.playFromMediaId(mediaId, null)
        }
    }

    /** Non déclaré par Deezer, mais à essayer quand même : déclarer n'est pas honorer. */
    fun testSkipToQueueItem(packageName: String, queueId: Long) {
        withSession(packageName, "skipToQueueItem($queueId)") { controller ->
            controller.transportControls.skipToQueueItem(queueId)
        }
    }

    private fun withSession(packageName: String, label: String, action: (MediaController) -> Unit) {
        val controller = probe.activeControllers().firstOrNull { it.packageName == packageName }
        if (controller == null) {
            _state.update { it.copy(lastResult = "Session $packageName introuvable — relance la lecture puis actualise.") }
            return
        }
        runCatching { action(controller) }.onFailure { error ->
            _state.update { it.copy(lastResult = "$label a échoué : ${error.message}") }
            return
        }
        observeEffect(label, packageName)
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
