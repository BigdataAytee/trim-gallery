package app.trimgallery.core.pipeline

import app.trimgallery.core.domain.billing.Entitlements
import app.trimgallery.core.domain.billing.ProFeature
import app.trimgallery.core.domain.billing.Tier
import app.trimgallery.core.model.MediaItem
import app.trimgallery.core.model.Settings
import app.trimgallery.core.model.SkipReason
import app.trimgallery.engine.CodecCaps
import app.trimgallery.engine.VideoCodec

/**
 * Which codec a file is re-encoded to (BUILD.md § 10, milestone 12).
 *
 * > HEVC via MediaCodec on all devices; AV1 where `MediaCodecList` reports a hardware AV1
 * > encoder.
 *
 * AV1 is worth roughly a third off HEVC's bitrate at the same quality, which on a phone
 * full of holiday video is real money in storage. It also has three properties that make
 * "use it when it exists" the wrong rule, and this object is the three of them written
 * down:
 *
 * 1. **It is not always faster than it needs to be.** A phone AV1 encoder is frequently a
 *    fraction of the speed of its HEVC sibling. BUILD.md § 6 caps the night in *minutes*,
 *    so an encoder at 1× real time turns a night that would have cleared four hours of
 *    video into one that clears one. The saving per file is bigger and the saving per
 *    night is smaller.
 * 2. **Its advertised throughput is lower.** Most phones that have an AV1 encoder at all
 *    top out below their HEVC ceiling — commonly 4K30 against 4K60 — and BUILD.md § 10 is
 *    explicit that the advertised points are to be respected rather than discovered.
 * 3. **Not everything can play it.** The file stays on the user's phone, which can
 *    obviously decode what it just encoded, but it also gets shared, cast and opened on
 *    other people's devices. That is a cost the user should choose, which is why it is a
 *    setting that is off by default rather than a default.
 *
 * And one rule that is not about AV1 being good or bad:
 *
 * 4. **An AV1 source is only ever re-encoded to AV1.** Triage counts AV1 above ~8 Mbps as a
 *    candidate (BUILD.md § 5), and taking that file to HEVC would usually make it *larger*
 *    at the same quality — the app would have spent a night's battery to lose the user
 *    space. Where AV1 is not available, such a file is skipped and told so.
 */
object CodecChoice {

    /**
     * How much slower than real time an AV1 encode may be before HEVC is the better answer.
     *
     * Below this the night stops being able to work through a library: at 0.5× a
     * ten-minute clip costs twenty minutes of the sixty the default cap allows, and one
     * file has eaten a third of the night for a saving one file wide. The number is a
     * *measurement*, taken from what this device actually did (`Job.realtimeMultiple`), not
     * a guess about the chip.
     */
    const val MIN_AV1_REALTIME_MULTIPLE = 1.0

    /**
     * How much of the library's video AV1 has to have handled before its speed is believed.
     *
     * The same reasoning as the predictor's confidence threshold: one slow file is a file,
     * not a fact about the encoder, and demoting AV1 for the rest of the phone's life on
     * the strength of a single thermally-throttled clip would be a bug nobody could see.
     */
    const val CONFIDENT_SAMPLES = 5

    /** What was chosen, and — when nothing was — what to tell the user. */
    sealed interface Choice {
        data class Encode(val codec: VideoCodec, val reason: Reason) : Choice

        /** No codec on this device can take this file. Triage's skip reason, verbatim. */
        data class Skip(val reason: SkipReason) : Choice
    }

    /** Why this codec rather than the other. Recorded on the job, and shown in History. */
    enum class Reason {
        /** AV1 in, AV1 out. Never anything else. */
        SOURCE_IS_AV1,

        /** The user turned AV1 on, has Pro, and the device can sustain it. */
        AV1_ALLOWED,

        /** The ordinary path: HEVC on every device (BUILD.md § 10). */
        HEVC_DEFAULT,

        /** AV1 was possible but the HEVC encoder is the one that can hold this frame. */
        AV1_CANNOT_SUSTAIN,

        /** AV1 was possible but measured too slow on this device to be worth the night. */
        AV1_TOO_SLOW,
    }

    /**
     * What this device actually managed, per codec.
     *
     * Supplied by the caller from the `job` table rather than computed here, because the
     * numbers belong to the device and outlive any one night.
     */
    data class MeasuredSpeed(val realtimeMultiple: Double, val samples: Int) {
        val confident: Boolean get() = samples >= CONFIDENT_SAMPLES
    }

    /**
     * Chooses the output codec for one file.
     *
     * @param av1Speed what AV1 has measured on this device, or null where it has never run.
     *   Null means "try it": an encoder that has never been measured cannot be demoted for
     *   being slow, and the first few files are how the measurement gets made.
     */
    fun choose(
        item: MediaItem,
        caps: CodecCaps,
        settings: Settings,
        tier: Tier,
        av1Speed: MeasuredSpeed? = null,
    ): Choice {
        val fps = item.fps ?: 0.0
        val av1Sustains = caps.av1.canSustain(item.width, item.height, fps)
        val hevcSustains = caps.hevc.canSustain(item.width, item.height, fps)

        // Rule 4 first, because it is the only one that can turn into a skip. A source that
        // is already AV1 has exactly one destination, and "HEVC is available" is not a
        // consolation — it is a bigger file for the same picture.
        if (isAv1(item)) {
            return if (av1Sustains && av1Permitted(settings, tier)) {
                Choice.Encode(VideoCodec.AV1, Reason.SOURCE_IS_AV1)
            } else {
                Choice.Skip(SkipReason.NO_HARDWARE_ENCODER)
            }
        }

        val av1Wanted = av1Permitted(settings, tier)
        if (!av1Wanted) {
            return if (hevcSustains) {
                Choice.Encode(VideoCodec.HEVC, Reason.HEVC_DEFAULT)
            } else {
                Choice.Skip(SkipReason.NO_HARDWARE_ENCODER)
            }
        }

        if (!av1Sustains) {
            return if (hevcSustains) {
                Choice.Encode(VideoCodec.HEVC, Reason.AV1_CANNOT_SUSTAIN)
            } else {
                Choice.Skip(SkipReason.NO_HARDWARE_ENCODER)
            }
        }

        if (tooSlow(av1Speed) && hevcSustains) {
            return Choice.Encode(VideoCodec.HEVC, Reason.AV1_TOO_SLOW)
        }

        return Choice.Encode(VideoCodec.AV1, Reason.AV1_ALLOWED)
    }

    /**
     * Whether the user may have AV1 at all.
     *
     * Two conditions, both the user's: the Pro entitlement (MONETIZATION.md) and the
     * setting, which `SettingsPolicy.sanitise` already clears for a free tier. Checked here
     * as well rather than trusted, because a codec choice that silently depended on
     * something else having sanitised first is one refactor away from encoding a free
     * user's library into a format they did not choose.
     */
    fun av1Permitted(settings: Settings, tier: Tier): Boolean =
        settings.allowAv1 && Entitlements.allows(tier, ProFeature.AV1_ENCODE)

    private fun tooSlow(speed: MeasuredSpeed?): Boolean =
        speed != null && speed.confident && speed.realtimeMultiple < MIN_AV1_REALTIME_MULTIPLE

    /**
     * Why a file was skipped, for the Skipped screen.
     *
     * An AV1 source on a device that cannot encode AV1 is genuinely "this phone can't do
     * this one", which is what `SkipList` already says for `NO_HARDWARE_ENCODER` — so the
     * reason is reused rather than a new one invented for a distinction the user does not
     * have to care about.
     */
    private fun isAv1(item: MediaItem): Boolean {
        val codec = item.codec?.lowercase() ?: return false
        return codec.contains("av01") || codec.contains("av1")
    }
}
