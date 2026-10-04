package com.vizuzik.app

import android.content.Context
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fenêtres natives de mise à jour, qui expliquent les étapes à l'utilisateur : Installer → autoriser
 * l'installation d'apps inconnues si Android le demande → confirmer « Mettre à jour ».
 * Observe [AppUpdater] tant que l'Activity est visible (STARTED) ; une fenêtre ne survit pas à onStop.
 */
class UpdateDialogs(private val activity: AppCompatActivity) {
    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null

    /** Identifie le contenu affiché, pour ne recréer la fenêtre que lorsqu'il change. */
    private var shown: Any? = null

    fun start() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(AppUpdater.state, AppUpdater.dismissed, AppUpdater.userStarted) { s, d, u -> Triple(s, d, u) }
                        .collect { (s, dismissed, userStarted) -> render(s, dismissed, userStarted) }
                } finally {
                    close()
                }
            }
        }
    }

    private fun render(
        state: UpdateState,
        dismissed: Boolean,
        userStarted: Boolean,
    ) {
        // `canInstall` fait partie de la clé : au retour des réglages Android, le texte de l'étape change.
        val key: Any? =
            when {
                dismissed -> null
                state is UpdateState.UpdateAvailable -> state.update
                state is UpdateState.Downloading -> DOWNLOADING
                state is UpdateState.Downloaded -> state.file to AppUpdater.canInstallPackages()
                state is UpdateState.Error && userStarted -> state.error
                else -> null
            }
        if (key == null) {
            close()
            return
        }
        if (state is UpdateState.Downloading && shown == DOWNLOADING) {
            progressBar?.progress = state.progress.percentage ?: 0
            return
        }
        if (key == shown && dialog?.isShowing == true) return
        close()
        shown = key
        dialog =
            when (state) {
                is UpdateState.UpdateAvailable ->
                    builder(R.string.update_available_title)
                        .setMessage(
                            steps(
                                activity.getString(R.string.update_available_intro, state.update.versionName),
                                R.string.update_step_download,
                                R.string.update_step_allow_source,
                                R.string.update_step_confirm,
                            ),
                        )
                        .setPositiveButton(R.string.update_action_install) { _, _ -> AppUpdater.onInstall(state.update) }
                        .setNegativeButton(R.string.update_action_later) { _, _ -> AppUpdater.onDismiss() }
                        .setOnCancelListener { AppUpdater.onDismiss() }
                        .show()
                is UpdateState.Downloading -> {
                    val bar =
                        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            progress = state.progress.percentage ?: 0
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                    progressBar = bar
                    val pad = (24 * activity.resources.displayMetrics.density).toInt()
                    builder(R.string.update_downloading_title)
                        .setView(bar, pad, pad / 2, pad, 0)
                        .setCancelable(false)
                        .show()
                }
                is UpdateState.Downloaded -> {
                    val message =
                        if (AppUpdater.canInstallPackages()) {
                            steps(activity.getString(R.string.update_downloaded_intro), R.string.update_downloaded_step_install)
                        } else {
                            steps(
                                activity.getString(R.string.update_permission_intro),
                                R.string.update_permission_step_open,
                                R.string.update_permission_step_enable,
                                R.string.update_permission_step_retry,
                            )
                        }
                    builder(R.string.update_downloaded_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.update_action_install) { _, _ -> AppUpdater.onInstallDownloaded(state.file) }
                        .setNegativeButton(R.string.update_action_later) { _, _ -> AppUpdater.onDismiss() }
                        .setOnCancelListener { AppUpdater.onDismiss() }
                        .show()
                }
                is UpdateState.Error ->
                    builder(R.string.update_error_title)
                        .setMessage(updateErrorMessage(activity, state.error))
                        .setPositiveButton(R.string.update_action_ok) { _, _ -> AppUpdater.onDismiss() }
                        .setOnCancelListener { AppUpdater.onDismiss() }
                        .show()
                else -> null
            }
    }

    private fun builder(title: Int) = AlertDialog.Builder(activity).setTitle(title)

    private fun steps(
        intro: String,
        vararg steps: Int,
    ): String =
        buildString {
            append(intro)
            steps.forEachIndexed { i, id -> append("\n\n${i + 1}. ${activity.getString(id)}") }
            append("\n\n").append(activity.getString(R.string.update_data_kept))
        }

    private fun close() {
        dialog?.dismiss()
        dialog = null
        progressBar = null
        shown = null
    }

    private companion object {
        const val DOWNLOADING = "downloading"
    }
}

internal fun updateErrorMessage(
    context: Context,
    error: UpdateError,
): String =
    when (error) {
        is UpdateError.NetworkError -> context.getString(R.string.update_error_network)
        is UpdateError.RateLimit -> context.getString(R.string.update_error_rate_limit)
        UpdateError.ReleaseNotFound -> context.getString(R.string.update_error_no_release)
        is UpdateError.ApkNotFound -> context.getString(R.string.update_error_no_apk)
        UpdateError.InstallationNotAllowed -> context.getString(R.string.update_error_not_allowed)
        else -> context.getString(R.string.update_error_generic, error.message ?: "")
    }
