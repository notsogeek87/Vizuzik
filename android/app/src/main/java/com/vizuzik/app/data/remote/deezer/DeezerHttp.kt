package com.vizuzik.app.data.remote.deezer

import java.net.HttpURLConnection
import java.net.URL

/**
 * Un client HTTP minimal en `HttpURLConnection` (zéro dépendance Gradle
 * supplémentaire) suffit pour les quelques appels GET dont Vizuzik a besoin
 * côté Deezer.
 */
internal object DeezerHttp {
    fun get(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.IOException("Réponse vide (HTTP ${connection.responseCode})")
        } finally {
            connection.disconnect()
        }
    }
}
