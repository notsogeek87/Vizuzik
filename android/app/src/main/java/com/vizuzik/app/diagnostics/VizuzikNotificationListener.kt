package com.vizuzik.app.diagnostics

import android.service.notification.NotificationListenerService

/**
 * Service volontairement vide : aucune notification n'est lue, transmise ni
 * stockée. Il n'existe que pour obtenir l'autorisation « accès aux
 * notifications », seule porte d'entrée légitime vers
 * [android.media.session.MediaSessionManager.getActiveSessions], qui permet
 * de lire et de piloter la session média d'une autre application (Deezer).
 */
class VizuzikNotificationListener : NotificationListenerService()
