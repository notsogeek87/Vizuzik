package com.vizuzik.app.ui.diagnostics

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.diagnostics.SessionReport

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaSessionProbeScreen(onBack: () -> Unit, viewModel: MediaSessionProbeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var trackUrl by remember { mutableStateOf("") }

    // Pré-remplit avec l'artiste en cours : sinon le bouton de recherche reste
    // grisé et l'appui semble « ne rien faire ».
    LaunchedEffect(state.reports) {
        if (query.isBlank()) {
            query = state.reports.firstNotNullOfOrNull { it.artist }.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sonde Deezer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) } },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualiser")
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(viewModel.textReport())) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copier le rapport")
                    }
                },
            )
        },
        // Le verdict vit dans une barre fixe en bas : les boutons de test sont
        // en bas de la liste, un résultat affiché en haut serait hors écran.
        bottomBar = {
            state.lastResult?.let { result ->
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Dernier test", style = MaterialTheme.typography.titleSmall)
                        Text(result, style = MaterialTheme.typography.bodyMedium)
                        if (state.busy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasAccess) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Accès aux notifications requis", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "C'est cette autorisation qui permet de lire la session média de Deezer. " +
                                    "Vizuzik ne lit aucune notification : le service est vide, il ne sert qu'à " +
                                    "débloquer l'accès aux sessions média.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text("Ouvrir les réglages") }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Mode d'emploi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "1. Autorise l'accès aux notifications ci-dessus.\n" +
                                "2. Lance n'importe quel morceau dans l'app Deezer, puis reviens ici.\n" +
                                "3. Appuie sur ↻ : la session Deezer doit apparaître.\n" +
                                "4. Teste les commandes, surtout « Lancer une recherche » — c'est elle qui décide " +
                                "si on peut choisir la musique depuis notre interface.\n" +
                                "5. Copie le rapport (icône en haut) et renvoie-le moi.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.hasAccess && state.reports.isEmpty()) {
                item {
                    Text(
                        "Aucune session média active. Lance un morceau dans Deezer puis appuie sur ↻.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(state.reports, key = SessionReport::packageName) { report ->
                SessionCard(
                    report = report,
                    query = query,
                    onQueryChange = { query = it },
                    trackUrl = trackUrl,
                    onTrackUrlChange = { trackUrl = it },
                    onTransport = { viewModel.sendTransport(report.packageName, it) },
                    onPlayFromSearch = { viewModel.testPlayFromSearch(report.packageName, query) },
                    onPlayFromUri = { viewModel.testPlayFromUri(report.packageName, trackUrl) },
                    onPlayFromMediaId = { viewModel.testPlayFromMediaId(report.packageName, it) },
                    onSkipToQueueItem = { viewModel.testSkipToQueueItem(report.packageName, it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    report: SessionReport,
    query: String,
    onQueryChange: (String) -> Unit,
    trackUrl: String,
    onTrackUrlChange: (String) -> Unit,
    onTransport: (TransportCommand) -> Unit,
    onPlayFromSearch: () -> Unit,
    onPlayFromUri: () -> Unit,
    onPlayFromMediaId: (String) -> Unit,
    onSkipToQueueItem: (Long) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(report.packageName, style = MaterialTheme.typography.titleMedium)

            // Test principal : un seul appui, aucune saisie. On redemande à
            // l'app de jouer un morceau qu'elle a elle-même publié dans sa
            // file — si elle refuse ça, elle n'honore rien.
            report.queueItems.firstOrNull()?.mediaId?.let { mediaId ->
                Button(
                    onClick = { onPlayFromMediaId(mediaId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("▶ LANCER LE TEST (aucune saisie requise)")
                }
                Text(
                    "Appuie, attends 3 secondes, et lis le verdict en bas de l'écran.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                buildString {
                    appendLine("état     : ${report.state}")
                    appendLine("titre    : ${report.title ?: "—"}")
                    appendLine("artiste  : ${report.artist ?: "—"}")
                    appendLine("album    : ${report.album ?: "—"}")
                    appendLine("durée    : ${report.durationMs} ms")
                    appendLine("pochette : ${report.artwork}")
                    append("file     : ${report.queueSize?.toString() ?: "non exposée"}")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Text(
                if (report.decisiveActions.isEmpty()) {
                    "⚠️ Aucune action décisive déclarée : cette app ne permettrait, a priori, " +
                        "que de télécommander une lecture lancée ailleurs."
                } else {
                    "✅ Actions décisives déclarées : ${report.decisiveActions.joinToString()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (report.decisiveActions.isEmpty()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )

            Text(
                "Toutes les actions déclarées : ${report.declaredActions.ifEmpty { listOf("aucune") }.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (report.customActions.isNotEmpty()) {
                Text("Actions custom : ${report.customActions.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onTransport(TransportCommand.PLAY) }) { Text("Play") }
                OutlinedButton(onClick = { onTransport(TransportCommand.PAUSE) }) { Text("Pause") }
                OutlinedButton(onClick = { onTransport(TransportCommand.PREVIOUS) }) { Text("Précédent") }
                OutlinedButton(onClick = { onTransport(TransportCommand.NEXT) }) { Text("Suivant") }
            }

            Text(
                "Test décisif — cible : ${report.packageName}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Une commande peut fonctionner même si l'app ne la déclare pas — il faut donc l'essayer " +
                    "réellement. Tape un artiste ou un titre que tu as sur Deezer.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Recherche") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = onPlayFromSearch, enabled = query.isNotBlank()) {
                Text("1. Lancer une recherche (playFromSearch)")
            }

            OutlinedTextField(
                value = trackUrl,
                onValueChange = onTrackUrlChange,
                label = { Text("URL d'un morceau (deezer.com/track/…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onPlayFromUri, enabled = trackUrl.isNotBlank()) {
                Text("2. Lancer cette URL (playFromUri)")
            }

            if (report.queueItems.isNotEmpty()) {
                Text(
                    "3. Depuis la file exposée par l'app — ce sont ses propres identifiants, " +
                        "donc le déclenchement le plus fiable possible :",
                    style = MaterialTheme.typography.bodySmall,
                )
                report.queueItems.forEach { item ->
                    Column {
                        Text(
                            "« ${item.title ?: "—"} »  ·  mediaId=${item.mediaId ?: "—"}  ·  queueId=${item.queueId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.mediaId?.let { id ->
                                OutlinedButton(onClick = { onPlayFromMediaId(id) }) { Text("playFromMediaId") }
                            }
                            OutlinedButton(onClick = { onSkipToQueueItem(item.queueId) }) { Text("skipToQueueItem") }
                        }
                    }
                }
            }
        }
    }
}
