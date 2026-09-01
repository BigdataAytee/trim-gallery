package app.trimgallery.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.trimgallery.core.model.FolderGrant
import app.trimgallery.engine.android.FolderChoice
import app.trimgallery.engine.android.GrantedFolders
import app.trimgallery.engine.android.NightPass

/**
 * Choosing a folder, with the help the refusal rules require, in one place.
 *
 * Two screens ask for a folder — the first-run gallery and Settings — and they must not
 * diverge: both have to take the grant *and* the write permission, both have to schedule
 * the night pass the first time there is anything to work on, and both have to answer an
 * empty result with the help sheet rather than a shrug. Written twice, one of them would
 * eventually be the one that forgets.
 *
 * [HelpSheet] is separate from [choose] because of where it has to be drawn: it is a
 * full-screen scrim over whatever is underneath, and in Compose a sibling emitted *first*
 * is drawn *underneath*. So the sheet is a composable the caller places last, inside a Box
 * — not something this file can emit on its own and hope lands on top.
 */
@Stable
class FolderPicker internal constructor(private val launch: (Uri?) -> Unit, private val help: MutableState<Help>) {

    /** Whether the help sheet is up, and what it should lead with. */
    internal sealed interface Help {
        data object Hidden : Help
        data class Shown(val refusal: FolderChoice.Refusal?) : Help
    }

    /**
     * Opens the picker wherever the system opens it.
     *
     * **No initial-URI hint.** It used to open at DCIM/Camera, on the reasoning that the
     * ordinary path would then never meet a folder Android blocks. What it actually did was
     * drop the user *inside* one folder with no way up: `EXTRA_INITIAL_URI` sets where the
     * picker starts, and starting deep is indistinguishable from being trapped there for
     * anyone whose photographs live somewhere else.
     *
     * The help sheet already covers the case the hint was guarding against, and it does it
     * after the fact, when the user has actually hit it, rather than by narrowing what they
     * can choose in advance.
     */
    fun choose() = launch(null)

    /** The sheet. Emits nothing unless the last attempt came back with nothing usable. */
    @Composable
    fun HelpSheet() {
        val shown = help.value as? Help.Shown ?: return
        FolderHelpSheet(
            refusal = shown.refusal,
            onChoose = {
                help.value = Help.Hidden
                choose()
            },
        )
    }
}

/**
 * A [FolderPicker] bound to this composition.
 *
 * @param onGranted the grants as they now stand, so the caller can rescan. Called only
 *   when a folder was actually taken.
 */
@Composable
fun rememberFolderPicker(
    folders: GrantedFolders,
    nightPass: NightPass,
    onGranted: (List<FolderGrant>) -> Unit,
): FolderPicker {
    // Null means one of two things the app cannot tell apart: Android refused the folder
    // (the picker greys out "Use this folder" for the three locations in FolderChoice, so
    // the only way out is to back away), or the user changed their mind. Either way the
    // next thing on screen has to be help rather than an accusation.
    val help = remember { mutableStateOf<FolderPicker.Help>(FolderPicker.Help.Hidden) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            help.value = FolderPicker.Help.Shown(refusal = null)
        } else {
            // Reachable when a picker allows what the platform documents as unpickable.
            // Taking the grant would leave a folder that is stored, looks granted, and
            // scans nothing.
            val refusal = FolderChoice.refusalFor(uri)
            if (refusal != null) {
                help.value = FolderPicker.Help.Shown(refusal)
            } else {
                folders.take(uri)
                help.value = FolderPicker.Help.Hidden
                // The first moment the app has anything to work on. Until this call
                // existed, granting a folder scheduled nothing: NightScheduler.schedule had
                // no caller anywhere in the app, so the night pass had never run on any
                // device.
                nightPass.sync()
                onGranted(folders.grants())
            }
        }
    }
    return remember(launcher) { FolderPicker({ uri -> launcher.launch(uri) }, help) }
}
