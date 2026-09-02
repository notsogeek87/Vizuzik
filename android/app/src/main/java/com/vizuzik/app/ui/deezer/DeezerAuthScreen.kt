package com.vizuzik.app.ui.deezer

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerAuthScreen(
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    viewModel: DeezerAuthViewModel = hiltViewModel(),
) {
    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connexion Deezer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) } },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !viewModel.isConfigured -> NotConfiguredMessage()
                isAuthenticated -> ConnectedContent(onOpenLibrary = onOpenLibrary, onLogout = viewModel::logout)
                state.isExchanging -> LoadingContent()
                else -> Column(Modifier.fillMaxSize()) {
                    state.error?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    DeezerLoginWebView(
                        authorizationUrl = viewModel.authorizationUrl,
                        redirectUri = viewModel.redirectUri,
                        onCodeReceived = viewModel::onCodeReceived,
                        onError = viewModel::onWebViewError,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotConfiguredMessage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Identifiants Deezer manquants", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cette build n'a pas été compilée avec DEEZER_APP_ID/DEEZER_APP_SECRET. " +
                        "Crée une app sur developers.deezer.com/myapps, ajoute ses identifiants comme " +
                        "secrets du dépôt GitHub (DEEZER_APP_ID, DEEZER_APP_SECRET), puis relance le build.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Connexion à Deezer…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun ConnectedContent(onOpenLibrary: () -> Unit, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card {
            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Connecté à Deezer", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tu peux maintenant ouvrir tes albums et playlists Deezer depuis Vizuzik.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                    Text("Ouvrir ma bibliothèque Deezer")
                }
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("Se déconnecter")
                }
            }
        }
    }
}

/**
 * Le redirect_uri déclaré (https://vizuzik.local/oauth/callback) n'a pas
 * besoin d'être un serveur réel : on intercepte la navigation vers cette
 * URL côté client (shouldOverrideUrlLoading) pour en extraire ?code=... et
 * on annule la navigation avant qu'elle n'échoue.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DeezerLoginWebView(
    authorizationUrl: String,
    redirectUri: String,
    onCodeReceived: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return interceptRedirect(request.url, redirectUri, onCodeReceived, onError)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // Une erreur sur la navigation finale (le redirect_uri n'étant pas
                        // un vrai serveur) est normale : on ne la remonte que si elle ne
                        // concerne pas justement cette redirection déjà interceptée.
                        val url = request?.url ?: return
                        if (!url.toString().startsWith(redirectUri)) {
                            onError("Erreur de chargement : ${error?.description}")
                        }
                    }
                }
                loadUrl(authorizationUrl)
            }
        },
    )
}

private fun interceptRedirect(
    url: Uri,
    redirectUri: String,
    onCodeReceived: (String) -> Unit,
    onError: (String) -> Unit,
): Boolean {
    if (!url.toString().startsWith(redirectUri)) return false
    val code = url.getQueryParameter("code")
    val errorReason = url.getQueryParameter("error_reason")
    when {
        !code.isNullOrBlank() -> onCodeReceived(code)
        !errorReason.isNullOrBlank() -> onError("Connexion refusée par Deezer : $errorReason")
        else -> onError("Redirection Deezer sans code d'autorisation.")
    }
    return true
}
