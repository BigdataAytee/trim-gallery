package app.trimgallery.ui.milestone1

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.trimgallery.optimiser.TransformerEncoder
import java.util.Locale

/**
 * Milestone 1 screen (BUILD.md section 13, step 1).
 *
 * Pick a video, encode it with Media3 Transformer, play the result. This is a proving
 * harness for the encode pipeline, not the gallery — the gallery shell is milestone 8
 * and gets its look from the `frontend-design` skill.
 */
@UnstableApi
@Composable
fun Milestone1Screen(
    viewModel: Milestone1ViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        // OpenDocument, not GetContent: it gives a durable URI and matches the
        // Storage Access Framework model the rest of the app uses (BUILD.md section 4).
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::encode) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Milestone 1", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Encode one video with Media3 Transformer — HEVC, hardware encoder only, " +
                "audio passed through untouched — then play the result.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { picker.launch(arrayOf("video/*")) },
            enabled = state !is Milestone1ViewModel.State.Encoding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pick a video")
        }

        when (val current = state) {
            is Milestone1ViewModel.State.Idle -> Unit

            is Milestone1ViewModel.State.Encoding -> {
                Text("Encoding — ${current.progress}%")
                LinearProgressIndicator(
                    progress = { current.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }

            is Milestone1ViewModel.State.Failed -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Encode failed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(current.message, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If no hardware HEVC encoder was found this is the correct outcome: " +
                            "the file is skipped rather than encoded in software.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            is Milestone1ViewModel.State.Done -> {
                ResultCard(current.result)
                Spacer(Modifier.height(8.dp))
                Text("Playback", style = MaterialTheme.typography.titleMedium)
                Player(uri = Uri.fromFile(current.result.output))
            }
        }

        AnimatedVisibility(visible = state is Milestone1ViewModel.State.Done) {
            Text(
                "The source file was not modified. Verification and safe replace are " +
                    "milestone 4.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ResultCard(result: TransformerEncoder.Result) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Encoded", style = MaterialTheme.typography.titleMedium)
            Row("Source", formatBytes(result.sourceSizeBytes))
            Row("Output", formatBytes(result.outputSizeBytes))
            Row("Factor", String.format(Locale.US, "%.2fx", result.sizeFactor))
            Row("Duration", "${result.durationMs / 1000}s")
            Row("Encode time", "${result.elapsedMs / 1000}s")
            Row("Video", result.videoMimeType ?: "unknown")
            Row("Audio", result.audioMimeType ?: "none")
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Plays the encoded file — the "and plays back" half of milestone 1.
 *
 * Uses the platform PlayerView through AndroidView; the Compose-native player surface
 * and the real viewer arrive with the gallery shell in milestone 8.
 */
@UnstableApi
@Composable
private fun Player(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "unknown"
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}
