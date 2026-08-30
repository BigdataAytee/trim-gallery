package app.trimgallery.optimiser

import android.media.MediaCodecInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderSelector

/**
 * An [EncoderSelector] that only ever offers hardware encoders.
 *
 * BUILD.md rule 2: "Hardware codecs only (MediaCodec). No software video encoding on
 * the phone, ever. Skip the file instead." A software encoder is slower than real
 * time, drains the battery the app exists to protect, and heats a device that is
 * meant to be sitting still on a charger — there is no setting under which it is the
 * right answer.
 *
 * Returning an empty list is the correct outcome when a device has no hardware
 * encoder for the requested format: Transformer then fails the export, and the caller
 * records the file as skipped rather than falling back.
 */
@UnstableApi
object HardwareOnlyEncoderSelector : EncoderSelector {

    /**
     * Software codec name prefixes.
     *
     * [MediaCodecInfo.isHardwareAccelerated] and [MediaCodecInfo.isSoftwareOnly] are
     * the documented signals, but they have been wrong on some devices, so the name
     * check stays as a second line of defence.
     */
    private val SOFTWARE_NAME_PREFIXES = listOf("OMX.google.", "c2.android.")

    override fun selectEncoderInfos(mimeType: String): List<MediaCodecInfo> =
        EncoderSelector.DEFAULT.selectEncoderInfos(mimeType).filter(::isHardware)

    /** Visible for testing: the rule that decides whether an encoder may be used. */
    fun isHardware(info: MediaCodecInfo): Boolean =
        info.isEncoder &&
            info.isHardwareAccelerated &&
            !info.isSoftwareOnly &&
            SOFTWARE_NAME_PREFIXES.none { info.name.startsWith(it, ignoreCase = true) }
}
