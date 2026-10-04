package com.vizuzik.app

import android.content.Context
import com.lielu.githubupdater.UpdateConfig
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateInfo
import com.lielu.githubupdater.UpdateManager
import com.lielu.githubupdater.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Résultat d'une recherche manuelle (bouton des réglages), renvoyé au web. */
sealed class CheckResult {
    data object UpToDate : CheckResult()

    data class Available(val versionName: String) : CheckResult()

    data class Failed(val error: UpdateError) : CheckResult()

    /** Un téléchargement ou une installation est déjà en cours. */
    data object Busy : CheckResult()

    /** Applications à suffixe d'applicationId (`.staging`) : pas de mise à jour depuis la release de production. */
    data object Disabled : CheckResult()
}

/**
 * Vérifie les mises à jour à chaque ouverture de l'app et pilote [UpdateDialogs]. Un singleton : l'état
 * brut de [UpdateManager] est partagé entre la fenêtre de lancement et le bouton « Rechercher une mise à
 * jour » des réglages (plugin [AppUpdatePlugin]).
 */
object AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: UpdateManager
    private var enabled = false

    private val _dismissed = MutableStateFlow(false)

    /** `true` une fois « Plus tard » touché : la fenêtre revient à la prochaine ouverture de l'app. */
    val dismissed: StateFlow<Boolean> get() = _dismissed

    private val _userStarted = MutableStateFlow(false)

    /** `true` dès que l'utilisateur a agi (« Installer », « Rechercher ») : seulement alors on lui montre une erreur. */
    val userStarted: StateFlow<Boolean> get() = _userStarted

    val state: StateFlow<UpdateState> get() = manager.state

    val isEnabled: Boolean get() = enabled

    fun init(context: Context) {
        if (::manager.isInitialized) return
        val app = context.applicationContext
        manager = UpdateManager(app, UpdateConfig(githubOwner = "notsogeek87", githubRepository = "vizuzik"))
        // Un applicationId à suffixe (ex. .staging) ne peut pas être mis à jour par la release de production.
        enabled = !app.packageName.endsWith(".staging")
    }

    /** `false` tant qu'Android n'a pas autorisé l'app à installer des applications (étape à expliquer). */
    fun canInstallPackages(): Boolean = manager.canInstallPackages()

    /**
     * Appelée à chaque passage de l'app au premier plan (onStart). `force = true` : sans cela la bibliothèque
     * réutilise sa réponse précédente (« à jour ») jusqu'à `checkIntervalHours`, et une release publiée entre-temps
     * n'est pas vue. Une requête GitHub par ouverture reste très en dessous du quota (60/h).
     */
    fun checkOnOpen() {
        if (!enabled) return
        val s = state.value
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return
        // Chaque ouverture réaffiche une mise à jour encore en attente (fermée par « Plus tard »).
        _dismissed.value = false
        _userStarted.value = false
        // Ne pas écraser une fenêtre déjà affichée (mise à jour proposée ou téléchargée), ni un téléchargement en cours.
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && s !is UpdateState.Error) return
        // Les erreurs (hors ligne, quota GitHub…) sont publiées dans `state` ; ici on reste silencieux.
        scope.launch { runCatching { manager.checkForUpdate(force = true) } }
    }

    /** Bouton « Rechercher une mise à jour » des réglages : même vérification, mais les erreurs sont montrées. */
    suspend fun checkNow(): CheckResult {
        if (!enabled) return CheckResult.Disabled
        val s = state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return CheckResult.Busy
        _dismissed.value = false
        _userStarted.value = true
        if (s is UpdateState.UpdateAvailable) return CheckResult.Available(s.update.versionName)
        if (s is UpdateState.Downloaded) return CheckResult.Available("")
        return try {
            val update = manager.checkForUpdate(force = true)
            if (update == null) CheckResult.UpToDate else CheckResult.Available(update.versionName)
        } catch (e: com.lielu.githubupdater.UpdateException) {
            CheckResult.Failed(e.error)
        }
    }

    fun onDismiss() {
        _dismissed.value = true
    }

    fun onInstall(update: UpdateInfo) {
        _userStarted.value = true
        scope.launch {
            runCatching {
                val apk = manager.downloadUpdate(update)
                install(apk)
            }
        }
    }

    /** Relance l'installation d'un APK déjà téléchargé, typiquement au retour des réglages Android. */
    fun onInstallDownloaded(apk: File) {
        _userStarted.value = true
        runCatching { install(apk) }
    }

    private fun install(apk: File) {
        if (manager.canInstallPackages()) {
            manager.installUpdate(apk)
        } else {
            manager.openInstallPermissionSettings()
        }
    }
}
