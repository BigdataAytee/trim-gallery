package app.trimgallery.engine.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderSelector
import app.trimgallery.engine.BitrateMode
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.CodecFactory
import app.trimgallery.engine.EncodeSpec
import app.trimgallery.engine.EncoderCaps
import app.trimgallery.engine.HwEncoder
import app.trimgallery.engine.PerformancePoint
import app.trimgallery.engine.VideoCodec
import com.google.common.collect.ImmutableList

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
     *
     * Asked, not read. The platform's `PerformancePoint` has no public accessor for its
     * width, height or frame rate — it is a closed value whose whole interface is
     * `covers()`. So this walks a ladder of the shapes this app ever encodes, constructs
     * the platform point for each, and keeps the ones some advertised point covers. The
     * answer comes from the device either way; the difference is that this asks the
     * question the API is willing to answer.
     */
    private fun performancePointsOf(video: MediaCodecInfo.VideoCapabilities): List<PerformancePoint> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val advertised = video.supportedPerformancePoints.orEmpty()
        if (advertised.isEmpty()) return emptyList()
        return PROBE_POINTS.filter { candidate ->
            val probe = MediaCodecInfo.VideoCapabilities.PerformancePoint(
                candidate.width,
                candidate.height,
                candidate.fps,
            )
            advertised.any { it.covers(probe) }
        }
    }

    override fun encoder(spec: EncodeSpec, background: Boolean): HwEncoder = TransformerEncoder(
        context = context,
        spec = spec,
        background = background,
        encoderSelector = hardwareOnlySelector(),
    )

    /**
     * A started **decoder** for [format], and the only door to one.
     *
     * Decoders are inside the codec guard for a reason that is not the hardware-only rule:
     * BUILD.md § 2 rule 2 bans software *encoding*, and a software decoder is ordinary and
     * necessary — an emulator has nothing else. What a decoder shares with an encoder is the
     * hardware slot. The codec-priority skill is explicit: *"Set it on all codecs in the
     * pipeline, not just the encoder — a realtime-priority decoder feeding a background
     * encoder still holds a slot the foreground wants."* Routing decoder creation through
     * here is what makes that impossible to forget.
     *
     * Configured for `COLOR_FormatYUV420Flexible`, which is the contract `getOutputImage`
     * needs: it is the one colour format every device must be able to hand back as a
     * three-plane `Image`, whatever it prefers internally.
     *
     * @return null when the device has no decoder for this format at all, which the caller
     *   turns into an empty window rather than a crash.
     */
    fun decoder(format: MediaFormat, background: Boolean = true): MediaCodec? {
        val mimeType = format.getString(MediaFormat.KEY_MIME) ?: return null
        return runCatching {
            MediaCodec.createDecoderByType(mimeType).apply {
                format.setInteger(
                    KEY_PRIORITY,
                    if (background) PRIORITY_BACKGROUND else PRIORITY_REALTIME,
                )
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                configure(format, null, null, 0)
                start()
            }
        }.getOrNull()
    }

    /**
     * A started **hardware** encoder for one probe window, and the only door to one.
     *
     * Separate from [encoder] because it produces a different thing. That one hands back a
     * Media3 `Transformer` export — file in, file out, audio transmuxed, muxed to MP4 —
     * which is what the final encode needs and is far more than a probe does. A probe has a
     * buffer of YUV already in memory and wants an elementary stream back; running it
     * through a muxer and a temp file would add a write, a read and a container per probe,
     * twelve times a file.
     *
     * Hardware-only, with no fallback: [hardwareEncodersFor] is the same filter the final
     * encode goes through, so a device that cannot encode this format in hardware gets null
     * here and the file is skipped. BUILD.md § 2 rule 2 permits nothing else.
     *
     * @return null when no hardware encoder on this device takes this format at this size,
     *   or when configuring one failed. Never a software encoder.
     */
    fun probeEncoder(spec: EncodeSpec, background: Boolean = true): Probe? {
        val mimeType = mimeTypeOf(spec.codec)
        val info = hardwareEncodersFor(mimeType).firstOrNull() ?: return null
        val video = info.getCapabilitiesForType(mimeType)?.videoCapabilities ?: return null

        // Rounded up to what the encoder will accept. Most take any even size; some want
        // multiples of 16, and asking for 1278 wide there is a configure that throws.
        val width = align(spec.width, video.widthAlignment)
        val height = align(spec.height, video.heightAlignment)
        if (!runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)) return null

        val format = probeFormat(mimeType, width, height, spec, info, background)
        return runCatching {
            val codec = MediaCodec.createByCodecName(info.name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            Probe(codec, width, height)
        }.getOrNull()
    }

    /**
     * A started hardware encoder, with the frame size it actually accepted.
     *
     * The size is returned rather than assumed because [probeEncoder] may have rounded it
     * up to the encoder's alignment, and a caller that filled its input buffers at the size
     * it asked for would shear every frame by the difference.
     */
    class Probe(val codec: MediaCodec, val width: Int, val height: Int)

    @Suppress("LongParameterList")
    private fun probeFormat(
        mimeType: String,
        width: Int,
        height: Int,
        spec: EncodeSpec,
        info: MediaCodecInfo,
        background: Boolean,
    ): MediaFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
        // The one colour format every device must accept as a three-plane `Image`, which is
        // what `getInputImage` needs. Anything else means guessing at a device's private
        // plane layout.
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, spec.setting.bitrate)
        // An integer, deliberately: encoders read KEY_FRAME_RATE as one and a float here is
        // an IllegalArgumentException at configure time on some devices.
        setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps.toInt().coerceAtLeast(1))
        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, spec.gopSeconds)
        setInteger(KEY_PRIORITY, if (background) PRIORITY_BACKGROUND else PRIORITY_REALTIME)

        // Pre-checked, per ARCHITECTURE.md § 13: fall back to VBR where CQ is not offered,
        // never to software. The search runs on VBR anyway; this is here so that a caller
        // asking for CQ on a device that lacks it gets a working encode rather than a throw.
        val cqSupported = info.getCapabilitiesForType(mimeType)?.encoderCapabilities
            ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) ?: false
        val cq = spec.setting.cq
        if (spec.setting.mode == BitrateMode.CQ && cqSupported && cq != null) {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            setInteger(MediaFormat.KEY_QUALITY, cq)
        } else {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
    }

    /**
     * An [EncoderSelector] that can only ever offer hardware encoders.
     *
     * Returning an empty list is the correct outcome on a device with no hardware
     * encoder for the format: the export then fails and the caller records
     * `EncodeOutcome.NoHardwareEncoder`, so the file is skipped with a reason rather
     * than encoded in software.
     */
    fun hardwareOnlySelector(): EncoderSelector = EncoderSelector { mimeType ->
        // `ImmutableList`, because that is what Media3 declares this method to return.
        // Guava is not a new dependency — it arrives with media3-transformer and is part of
        // the signature being implemented here.
        ImmutableList.copyOf(EncoderSelector.DEFAULT.selectEncoderInfos(mimeType).filter(::isHardware))
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
    private fun isHardware(info: MediaCodecInfo): Boolean = info.isHardwareAccelerated &&
        !info.isSoftwareOnly &&
        SOFTWARE_NAME_PREFIXES.none { info.name.startsWith(it, ignoreCase = true) }

    companion object {
        private val SOFTWARE_NAME_PREFIXES = listOf("OMX.google.", "c2.android.")

        /**
         * The shapes `performancePointsOf` asks the encoder about.
         *
         * Every size and rate a phone camera produces that this app would ever be asked to
         * re-encode, largest first. Not an arbitrary ladder: a point missing from here is a
         * capability the app will never claim, which is the safe direction to be wrong in —
         * `EncoderCaps.canSustain` then falls back to the width, height and rate bounds.
         */
        private val PROBE_POINTS = listOf(
            PerformancePoint(width = 7680, height = 4320, fps = 30),
            PerformancePoint(width = 3840, height = 2160, fps = 120),
            PerformancePoint(width = 3840, height = 2160, fps = 60),
            PerformancePoint(width = 3840, height = 2160, fps = 30),
            PerformancePoint(width = 1920, height = 1080, fps = 240),
            PerformancePoint(width = 1920, height = 1080, fps = 120),
            PerformancePoint(width = 1920, height = 1080, fps = 60),
            PerformancePoint(width = 1920, height = 1080, fps = 30),
            PerformancePoint(width = 1280, height = 720, fps = 240),
            PerformancePoint(width = 1280, height = 720, fps = 120),
            PerformancePoint(width = 1280, height = 720, fps = 60),
            PerformancePoint(width = 1280, height = 720, fps = 30),
        )

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

/**
 * Rounds a frame dimension up to what an encoder will accept.
 *
 * Top-level rather than a method for a reason that is not style: `MediaCodecFactory` is the
 * one class in the app allowed to create a codec, and every helper added to it makes that
 * responsibility harder to read. Neither this nor [mimeTypeOf] touches a codec.
 */
private fun align(value: Int, alignment: Int): Int {
    val step = alignment.coerceAtLeast(1)
    return ((value + step - 1) / step) * step
}

private fun mimeTypeOf(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> MimeTypes.VIDEO_H265
    VideoCodec.AV1 -> MimeTypes.VIDEO_AV1
}
