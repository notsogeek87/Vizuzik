package com.vizuzik.app.data.remote.deezer

import com.vizuzik.app.diagnostics.MediaSessionProbe
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DeezerLaunchResult {
    data object Started : DeezerLaunchResult
    data object DeezerNotRunning : DeezerLaunchResult
    data object NotificationAccessMissing : DeezerLaunchResult
}

/**
 * Lance un album/playlist Deezer via `playFromSearch` sur sa session média —
 * validé en direct : pas de précision au morceau près, mais l'album/la
 * playlist visé(e) démarre bien (c'est tout ce dont Vizuzik a besoin). Le nom
 * de paquet n'est pas figé en dur : on cherche la session active dont le nom
 * contient "deezer", plus robuste qu'un identifiant fixe.
 */
@Singleton
class DeezerPlaybackLauncher @Inject constructor(
    private val probe: MediaSessionProbe,
) {
    fun launch(searchQuery: String): DeezerLaunchResult {
        if (!probe.hasNotificationAccess()) return DeezerLaunchResult.NotificationAccessMissing
        val controller = probe.activeControllers()
            .firstOrNull { it.packageName.contains("deezer", ignoreCase = true) }
            ?: return DeezerLaunchResult.DeezerNotRunning
        controller.transportControls.playFromSearch(searchQuery, null)
        return DeezerLaunchResult.Started
    }
}
