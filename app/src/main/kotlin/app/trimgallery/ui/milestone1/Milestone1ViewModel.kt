package app.trimgallery.ui.milestone1

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.trimgallery.optimiser.TransformerEncoder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives milestone 1: pick a video, encode it, play the result back.
 *
 * The source is only ever read. Output goes to app-private cache — nothing in this
 * milestone writes anywhere near the user's library (see the `safe-replace` skill).
 */
@UnstableApi
class Milestone1ViewModel(application: Application) : AndroidViewModel(application) {

    /** What the screen is doing, and what it has to show for it. */
    sealed interface State {
        data object Idle : State
        data class Encoding(val progress: Int) : State
        data class Done(val result: TransformerEncoder.Result) : State
        data class Failed(val message: String) : State
    }

    private val encoder = TransformerEncoder(application)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            encoder.progress.collect { percent ->
                _state.update { current ->
                    if (current is State.Encoding) current.copy(progress = percent) else current
                }
            }
        }
    }

    fun encode(source: Uri) {
        job?.cancel()
        _state.value = State.Encoding(progress = 0)

        job = viewModelScope.launch {
            val output = outputFile()
            try {
                // Transformer is single-threaded and bound to the thread that builds
                // it; the main thread is the one place every caller agrees on.
                val result = withContext(Dispatchers.Main) { encoder.encode(source, output) }
                _state.value = State.Done(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * A fresh output path per run, in app-private cache.
     *
     * Never next to the original: milestone 1 has no verification step, so its output
     * must not be able to end up mistaken for a real file in the user's library.
     */
    private fun outputFile(): File =
        File(getApplication<Application>().cacheDir, "milestone1").resolve(
            "encode-${System.currentTimeMillis()}.mp4",
        )

    override fun onCleared() {
        super.onCleared()
        cancel()
    }
}
