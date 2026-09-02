package com.vizuzik.app.data.remote.deezer

import com.vizuzik.app.BuildConfig
import java.net.URLEncoder

/**
 * App ID/Secret viennent de [BuildConfig] (injectés à la compilation depuis
 * des secrets CI ou android/local.properties, jamais commités — voir
 * app/build.gradle). Vides tant que l'utilisateur n'a pas créé son app sur
 * developers.deezer.com : [isConfigured] permet à l'UI de le signaler
 * clairement plutôt que d'échouer silencieusement.
 */
object DeezerOAuthConfig {
    val appId: String = BuildConfig.DEEZER_APP_ID
    val appSecret: String = BuildConfig.DEEZER_APP_SECRET
    val redirectUri: String = BuildConfig.DEEZER_REDIRECT_URI

    val isConfigured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()

    // basic_access : profil de base. manage_library : lecture (et gestion) des
    // albums/playlists — Deezer n'expose pas de scope "lecture seule" dédié.
    private const val PERMS = "basic_access,manage_library"

    fun authorizationUrl(): String =
        "https://connect.deezer.com/oauth/auth.php" +
            "?app_id=$appId" +
            "&redirect_uri=${URLEncoder.encode(redirectUri, "UTF-8")}" +
            "&perms=$PERMS"
}
