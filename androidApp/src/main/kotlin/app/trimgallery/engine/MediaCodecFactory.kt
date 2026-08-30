package app.trimgallery.engine.android

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderSelector
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.HwEncoder
import app.trimgallery.engine.VideoCodec

/**
 * **The only place in the app that touches `MediaCodecList` or creates a codec.**
 *
 * A build guard enforces that (ARCHITECTURE.md § 14), because BUILD.md § 2.2 —
 * *hardware codecs only, no software video encoding, ever* — is only as strong as its
 * weakest call site. One `createEncoderByType` in a helper is a software encoder on any
 * device without the hardware one, and nothing above it would notice.
 *
 * See the `codec-priority` skill.
 */
@UnstableApi
class MediaCodecFactory(private val context: Context) : CodecFactory {

    override fun capabilities(): CodecCaps {
        val hevc = hardwareEncodersFor(MimeTypes.VIDEO_H265)
        val av1 = hardwareEncodersFor(MimeTypes.VIDEO_AV1)
        val best = hevc.firstOrNull()

        val videoCaps = best
            ?.getCapabilitiesForType(MimeTypes.VIDEO_H265)
            ?.videoCapabilities

        return CodecCaps(
            hardwareHevc = hevc.isNotEmpty(),
            hardwareAv1 = av1.isNotEmpty(),
            cqSupported = best
                ?.getCapabilitiesForType(MimeTypes.VIDEO_H265)
                ?.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                ?: false,
            maxWidth = videoCaps?.supportedWidths?.upper ?: 0,
            maxHeight = videoCaps?.supportedHeights?.upper ?: 0,
            maxFps = videoCaps?.supportedFrameRates?.upper?.toDouble() ?: 0.0,
        )
    }

    override fun encoder(spec: EncodeSpec, background: Boolean): HwEncoder =
        TransformerEncoder(
            context = context,
            spec = spec,
            background = background,
            encoderSelector = hardwareOnlySelector(),
        )

    /**
     * An [EncoderSelector] that can only ever offer hardware encoders.
     *
     * Returning an empty list is the correct outcome on a device with no hardware
     * encoder for the format: the export then fails and the caller records
     * `EncodeOutcome.NoHardwareEncoder`, so the file is skipped with a reason rather
     * than encoded in software.
     */
    fun hardwareOnlySelector(): EncoderSelector = EncoderSelector { mimeType ->
        EncoderSelector.DEFAULT.selectEncoderInfos(mimeType).filter(::isHardware)
    }

    private fun hardwareEncodersFor(mimeType: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, ignoreCase = true) } }
            .filter(::isHardware)

    /**
     * The rule that decides whether an encoder may be used at all.
     *
     * `isHardwareAccelerated` and `isSoftwareOnly` are the documented signals, but they
     * have been wrong on some devices, so the name check stays as a second line of
     * defence.
     */
    private fun isHardware(info: MediaCodecInfo): Boolean =
        info.isHardwareAccelerated &&
            !info.isSoftwareOnly &&
            SOFTWARE_NAME_PREFIXES.none { info.name.startsWith(it, ignoreCase = true) }

    companion object {
        private val SOFTWARE_NAME_PREFIXES = listOf("OMX.google.", "c2.android.")

        /**
         * `MediaFormat.KEY_PRIORITY = 1` — best effort, i.e. background.
         *
         * This is what makes a foreground camera or video call win the hardware from the
         * night job. It is applied to the Media3 encoder through
         * `VideoEncoderSettings.setEncoderPerformanceParameters`.
         */
        const val PRIORITY_BACKGROUND = 1
        const val PRIORITY_REALTIME = 0

        /** Named for readability at the call site; see [MediaFormat.KEY_PRIORITY]. */
        const val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY
    }
}
