//! `png_optimise` for `trim_native.h`.
//!
//! Lossless by construction: oxipng re-derives filters and re-deflates, and never touches
//! a pixel. BUILD.md § 5 therefore gives this path no quality gate — there is no quality
//! question to ask. It still has to come out smaller, and the caller checks that.
//!
//! Everything crosses the boundary as bytes with an explicit length, per the ndk-build
//! skill's ABI rules. Nothing here opens a file: the bytes arrive from
//! `LibraryStorage.openRead` and leave to app-private scratch.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

pub const TRIM_OK: i32 = 0;
pub const TRIM_ERR_INVALID_ARG: i32 = -1;
pub const TRIM_ERR_INTERNAL: i32 = -4;

/// Optimises a PNG in memory.
///
/// Two-call convention, matching the rest of the ABI: pass `out == null` to learn the size,
/// then call again with a buffer.
///
/// # Safety
/// `src` must point to `src_len` readable bytes, and `out`, when non-null, to `*out_len`
/// writable ones.
#[no_mangle]
pub unsafe extern "C" fn png_optimise(
    src: *const u8,
    src_len: usize,
    out: *mut u8,
    out_len: *mut usize,
) -> i32 {
    if src.is_null() || src_len == 0 || out_len.is_null() {
        return TRIM_ERR_INVALID_ARG;
    }

    let input = slice::from_raw_parts(src, src_len);

    // oxipng panics on some malformed input rather than returning an error. A panic across
    // the FFI boundary is undefined behaviour, and one bad PNG in a library of a hundred
    // thousand must mark that item FAILED and let the night continue
    // (ARCHITECTURE.md § 13) — not take the process with it.
    let optimised = match catch_unwind(AssertUnwindSafe(|| optimise(input))) {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(_)) => return TRIM_ERR_INVALID_ARG,
        Err(_) => return TRIM_ERR_INTERNAL,
    };

    if out.is_null() {
        *out_len = optimised.len();
        return TRIM_OK;
    }
    if *out_len < optimised.len() {
        *out_len = optimised.len();
        return TRIM_ERR_INVALID_ARG;
    }

    std::ptr::copy_nonoverlapping(optimised.as_ptr(), out, optimised.len());
    *out_len = optimised.len();
    TRIM_OK
}

fn optimise(input: &[u8]) -> Result<Vec<u8>, oxipng::PngError> {
    // Level 2 rather than the default 3, and never level 6.
    //
    // The night has a minute budget (BUILD.md § 6) and screenshots are numerous but small.
    // Levels above 2 spend most of their extra time on Zopfli-class searching for the last
    // couple of per cent, which is a poor trade against the videos waiting behind them.
    let mut options = oxipng::Options::from_preset(2);

    // Metadata is the app's promise, not oxipng's to discard: BUILD.md § 2.4 keeps EXIF and
    // XMP, and a screenshot's creation time is what the gallery sorts on.
    options.strip = oxipng::StripChunks::None;

    // Colour-type reduction and bit-depth reduction are lossless and are where most of the
    // win on a screenshot comes from: a UI is usually a handful of colours stored as
    // 24-bit RGB.
    options.optimize_alpha = false; // alters transparent pixels; harmless but not lossless
    options.interlace = None; // never introduce interlacing, which costs bytes

    oxipng::optimize_from_memory(input, &options)
}
