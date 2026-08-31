import AVFoundation
import Foundation
import VideoToolbox
import shared

/// **The only place in this app that creates a video encoder on iOS.**
///
/// A build guard enforces that (ARCHITECTURE.md § 14): `VTCompressionSessionCreate` outside
/// this file fails the build, exactly as `MediaCodec.createEncoderByType` does on Android.
/// BUILD.md rule 2 — hardware codecs only, no software video encoding, ever — is only as
/// strong as its weakest call site, and VideoToolbox will quietly hand back a software
/// encoder if asked without the right key.
///
/// The three things that differ from Android and matter:
///
/// 1. **`kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder` is a request;
///    `RequireHardwareAcceleratedVideoEncoder` is a requirement.** Only the second one fails
///    rather than falling back, which is the behaviour rule 2 needs.
/// 2. **`AVAssetExportSession` is not used at all.** It has no bitrate control, and the
///    whole search in BUILD.md § 5 is a search over bitrates. `AVAssetWriter` it is.
/// 3. **AV1 encode exists only on A17 Pro and M-series.** `VTIsHardwareEncodeSupported`
///    answers for the device, which is what `CodecCaps` needs — and milestone 12 already
///    made those capabilities per codec rather than per device, because an AV1 encoder's
///    ceiling is usually lower than its HEVC sibling's.
@objc public final class VideoToolboxFactory: NSObject, CodecFactory {

    public func capabilities() -> CodecCaps {
        CodecCaps(
            hevc: caps(for: kCMVideoCodecType_HEVC),
            av1: caps(for: kCMVideoCodecType_AV1)
        )
    }

    public func encoder(spec: EncodeSpec, background: Bool) -> HwEncoder {
        AVAssetWriterEncoder(spec: spec, background: background)
    }

    /// What one codec's hardware encoder on this device can do.
    ///
    /// VideoToolbox has no equivalent of `getSupportedPerformancePoints()`, so the list comes
    /// back empty and `EncoderCaps.canSustain` falls back to the width, height and rate
    /// bounds — which is the documented meaning of empty there ("no information"), not "no
    /// limit". Whether iOS needs a throughput bound at all is a field-test question; Apple's
    /// encoders have historically not been the bottleneck the way some Android ones are.
    private func caps(for codec: CMVideoCodecType) -> EncoderCaps {
        guard VTIsHardwareEncodeSupported(codec) else { return EncoderCaps() }
        return EncoderCaps(
            hardware: true,
            maxWidth: Self.maxDimension,
            maxHeight: Self.maxDimension,
            maxFps: Self.maxFps,
            cqSupported: codec == kCMVideoCodecType_HEVC,
            performancePoints: []
        )
    }

    /// 8K, which is beyond anything an iPhone records and short of anything that would
    /// wrongly exclude an imported file.
    private static let maxDimension: Int32 = 7680
    private static let maxFps: Double = 240
}
