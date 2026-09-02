package com.vizuzik.app.diagnostics

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ce que la session média d'une autre application expose réellement.
 * [decisiveActions] est le point qui décide de la faisabilité d'un lecteur
 * skinné : sans PLAY_FROM_SEARCH / PLAY_FROM_MEDIA_ID, on ne peut que
 * télécommander ce que l'utilisateur a lancé ailleurs, pas choisir un morceau
 * depuis notre interface.
 */
data class SessionReport(
    val packageName: String,
    val state: String,
    val positionMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val artwork: String,
    val declaredActions: List<String>,
    val decisiveActions: List<String>,
    val queueSize: Int?,
    val queueItems: List<QueueEntry>,
    val customActions: List<String>,
)

/** Un élément de la file exposée par l'autre app, avec ses identifiants natifs. */
data class QueueEntry(
    val queueId: Long,
    val mediaId: String?,
    val title: String?,
)

@Singleton
class MediaSessionProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val listenerComponent = ComponentName(context, VizuzikNotificationListener::class.java)

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Vide si l'autorisation manque ou si aucune app ne joue quoi que ce soit. */
    fun activeControllers(): List<MediaController> {
        if (!hasNotificationAccess()) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return runCatching { manager.getActiveSessions(listenerComponent) }.getOrDefault(emptyList())
    }

    fun report(controller: MediaController): SessionReport {
        val playback = controller.playbackState
        val metadata = controller.metadata
        val declared = playback?.actions?.let(::decodeActions).orEmpty()

        return SessionReport(
            packageName = controller.packageName,
            state = playback?.state?.let(::stateName) ?: "aucun PlaybackState",
            positionMs = playback?.position ?: -1L,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L,
            artwork = describeArtwork(metadata),
            declaredActions = declared,
            decisiveActions = declared.filter { it in DECISIVE },
            queueSize = controller.queue?.size,
            queueItems = controller.queue.orEmpty().take(6).map { item ->
                QueueEntry(
                    queueId = item.queueId,
                    mediaId = item.description.mediaId,
                    title = item.description.title?.toString(),
                )
            },
            customActions = playback?.customActions?.map { "${it.action} (${it.name})" }.orEmpty(),
        )
    }

    private fun describeArtwork(metadata: MediaMetadata?): String {
        metadata ?: return "pas de métadonnées"
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        return when {
            bitmap != null -> "bitmap ${bitmap.width}×${bitmap.height}"
            uri != null -> "URI : $uri"
            else -> "aucune"
        }
    }

    private fun stateName(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "REWINDING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
        else -> "inconnu($state)"
    }

    private fun decodeActions(mask: Long): List<String> =
        ACTION_NAMES.filter { (flag, _) -> mask and flag != 0L }.map { it.value }

    /**
     * Rapport texte, destiné à être copié et renvoyé pour analyse.
     * [lastResult] est le verdict du dernier test live : c'est LUI qui tranche,
     * pas les actions déclarées — une app annonce souvent des commandes qu'elle
     * n'implémente pas (Media3 les déclare par défaut).
     */
    fun textReport(hasAccess: Boolean, reports: List<SessionReport>, lastResult: String?): String = buildString {
        appendLine("=== Sonde MediaSession Vizuzik ===")
        appendLine("Accès aux notifications : ${if (hasAccess) "accordé" else "REFUSÉ"}")
        appendLine("Sessions actives : ${reports.size}")
        appendLine("DERNIER TEST LIVE : ${lastResult ?: "aucun test effectué"}")
        reports.forEach { r ->
            appendLine()
            appendLine("--- ${r.packageName} ---")
            appendLine("état          : ${r.state}  (position ${r.positionMs} ms)")
            appendLine("titre         : ${r.title ?: "—"}")
            appendLine("artiste       : ${r.artist ?: "—"}")
            appendLine("album         : ${r.album ?: "—"}")
            appendLine("durée         : ${r.durationMs} ms")
            appendLine("pochette      : ${r.artwork}")
            appendLine("file d'attente: ${r.queueSize?.toString() ?: "non exposée"}")
            r.queueItems.forEach { item ->
                appendLine("  · queueId=${item.queueId} mediaId=${item.mediaId ?: "—"} « ${item.title ?: "—"} »")
            }
            appendLine("actions décisives : ${r.decisiveActions.ifEmpty { listOf("AUCUNE") }.joinToString()}")
            appendLine("toutes actions    : ${r.declaredActions.ifEmpty { listOf("aucune") }.joinToString()}")
            if (r.customActions.isNotEmpty()) appendLine("actions custom    : ${r.customActions.joinToString()}")
        }
    }

    private companion object {
        val DECISIVE = setOf("PLAY_FROM_SEARCH", "PLAY_FROM_MEDIA_ID", "PLAY_FROM_URI", "SKIP_TO_QUEUE_ITEM")

        // Type explicite : sans lui, une constante erronée fait échouer
        // l'inférence de toute la map et noie l'erreur réelle sous 25 autres.
        // Note : SET_REPEAT_MODE / SET_SHUFFLE_MODE n'existent que dans la
        // couche compat AndroidX, pas sur le PlaybackState du framework.
        val ACTION_NAMES: Map<Long, String> = linkedMapOf(
            PlaybackState.ACTION_PLAY to "PLAY",
            PlaybackState.ACTION_PAUSE to "PAUSE",
            PlaybackState.ACTION_PLAY_PAUSE to "PLAY_PAUSE",
            PlaybackState.ACTION_STOP to "STOP",
            PlaybackState.ACTION_SKIP_TO_NEXT to "SKIP_TO_NEXT",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "SKIP_TO_PREVIOUS",
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM",
            PlaybackState.ACTION_SEEK_TO to "SEEK_TO",
            PlaybackState.ACTION_FAST_FORWARD to "FAST_FORWARD",
            PlaybackState.ACTION_REWIND to "REWIND",
            PlaybackState.ACTION_SET_RATING to "SET_RATING",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
            PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
            PlaybackState.ACTION_PREPARE to "PREPARE",
            PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "PREPARE_FROM_MEDIA_ID",
            PlaybackState.ACTION_PREPARE_FROM_SEARCH to "PREPARE_FROM_SEARCH",
            PlaybackState.ACTION_PREPARE_FROM_URI to "PREPARE_FROM_URI",
        )
    }
}
