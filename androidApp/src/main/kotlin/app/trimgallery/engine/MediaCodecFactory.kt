package app.trimgallery.engine.android

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderSelector
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.EncoderCaps
import app.trimgallery.engine.HwEncoder
import app.trimgallery.engine.PerformancePoint
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

    override fun capabilities(): CodecCaps = CodecCaps(
        hevc = capsFor(MimeTypes.VIDEO_H265),
        av1 = capsFor(MimeTypes.VIDEO_AV1),
    )

    /**
     * What one format's best hardware encoder on this device can do.
     *
     * Queried per format rather than once for the device, because HEVC and AV1 differ and
     * the difference is not cosmetic: on most phones that have an AV1 encoder at all, its
     * ceiling is lower than the HEVC one — commonly 4K30 against 4K60. Until milestone 12
     * this method read one set of limits from the HEVC encoder and applied them to both,
     * which let an AV1 spec through against the wrong encoder's ceiling.
     */
    private fun capsFor(mimeType: String): EncoderCaps {
        val best = hardwareEncodersFor(mimeType).firstOrNull() ?: return EncoderCaps()
        val forType = best.getCapabilitiesForType(mimeType)
        val video = forType?.videoCapabilities ?: return EncoderCaps(hardware = true)

        return EncoderCaps(
            hardware = true,
            maxWidth = video.supportedWidths?.upper ?: 0,
            maxHeight = video.supportedHeights?.upper ?: 0,
            maxFps = video.supportedFrameRates?.upper?.toDouble() ?: 0.0,
            cqSupported = forType.encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                ?: false,
            performancePoints = performancePointsOf(video),
        )
    }

    /**
     * The throughput the encoder advertises (BUILD.md § 10).
     *
     * > Check `getSupportedPerformancePoints()`; never request beyond advertised
     * > throughput.
     *
     * Added in API 29 and permitted to return null at any level, which is not the same as
     * "no limit": an empty list means the encoder did not say, and `EncoderCaps.canSustain`
     * then falls back to the width, height and rate bounds rather than treating silence as
     * permission.
     */
    private fun performancePointsOf(video: MediaCodecInfo.VideoCapabilities): List<PerformancePoint> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return video.supportedPerformancePoints.orEmpty().map {
            PerformancePoint(width = it.width, height = it.height, fps = it.maxFrameRate)
        }
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
