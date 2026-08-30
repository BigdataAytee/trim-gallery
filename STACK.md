# STACK.md — Approved libraries and tools (all GitHub links verified 30 Aug 2026)

Use these. Do not substitute alternatives without noting why in PROJECT.md.
"Gradle" = add as a dependency (resolve the latest stable version from Maven Central / Google Maven).
"NDK" = build from source with CMake for arm64-v8a, NEON enabled, via a git submodule under `native/`.
"Reference" = read the code for the algorithm; do not vendor it.

## Front end
| Project | Use | How | Source |
|---|---|---|---|
| Jetpack Compose + Material 3 | UI | Gradle `androidx.compose.*`, `androidx.compose.material3` | https://github.com/androidx/androidx |
| Compose shared-element transitions | grid ↔ viewer motion | Gradle `androidx.compose.animation` | https://github.com/androidx/androidx |
| Coil 3 | image / video-frame thumbnails | Gradle `io.coil-kt.coil3:coil-compose`, `coil-video` | https://github.com/coil-kt/coil |
| Media3 ExoPlayer | playback, muted grid previews | Gradle `androidx.media3:media3-exoplayer` | https://github.com/androidx/media |
| Telephoto | pinch / double-tap zoom | Gradle `me.saket.telephoto:zoomable-image-coil3` | https://github.com/saket/telephoto |
| Zoomable (alternative) | zoom | Gradle `net.engawapg.lib:zoomable` | https://github.com/usuiat/Zoomable |
| Lottie for Compose | progress ring, cards, empty states | Gradle `com.airbnb.android:lottie-compose` | https://github.com/airbnb/lottie-android |
| Accompanist Permissions | permission flows | Gradle `com.google.accompanist:accompanist-permissions` | https://github.com/google/accompanist |
| Gainmap / Ultra HDR, Motion Photo | correct rendering of Pixel/Samsung photos | platform `android.graphics.Gainmap`; parse XMP `MotionPhoto` offset | https://github.com/androidx/androidx |

## Encoding pipeline
| Project | Use | How | Source |
|---|---|---|---|
| Media3 Transformer | decode → encode → mux, audio passthrough | Gradle `androidx.media3:media3-transformer`, `media3-effect`, `media3-muxer` | https://github.com/androidx/media |
| LiTr | fallback transcoder / reference | Gradle `com.linkedin.android.litr:litr` | https://github.com/linkedin/LiTr |
| ab-av1 | bitrate/CRF search loop | Reference | https://github.com/alexheretic/ab-av1 |
| Av1an | per-scene target quality, XPSNR mode | Reference | https://github.com/rust-av/Av1an |

## Quality metrics
| Project | Use | How | Source |
|---|---|---|---|
| XPSNR | search metric | NDK (standalone C from Fraunhofer HHI) | https://github.com/fraunhoferhhi/xpsnr |
| libvmaf | verification, vmaf_v0.6.1, n_subsample=10 | NDK (meson/ninja, arm64, NEON) | https://github.com/Netflix/vmaf |
| SSIMULACRA2 | photo quality gate | NDK (part of libjxl, `tools/ssimulacra2`) | https://github.com/libjxl/libjxl |

## Photos
| Project | Use | How | Source |
|---|---|---|---|
| jpegli | JPEG → smaller JPEG | NDK | https://github.com/google/jpegli |
| libjxl | reversible JPEG XL recompress | NDK | https://github.com/libjxl/libjxl |
| libheif | HEIC where HeifWriter unavailable | NDK | https://github.com/strukturag/libheif |
| oxipng | lossless PNG | Rust via cargo-ndk | https://github.com/oxipng/oxipng |
| libwebp | lossless WebP option | platform `Bitmap.CompressFormat.WEBP_LOSSLESS` | https://github.com/webmproject/libwebp |

## On-device intelligence
| Project | Use | How | Source |
|---|---|---|---|
| ML Kit Image Labeling, Face Detection, Text Recognition | labels, faces, OCR (bundled on-device models only) | Gradle `com.google.mlkit:image-labeling`, `face-detection`, `text-recognition` | https://github.com/googlesamples/mlkit |
| LiteRT (TensorFlow Lite) | run face-embedding model | Gradle `com.google.ai.edge.litert:litert` | https://github.com/google-ai-edge/LiteRT |
| MobileFaceNet / InsightFace | face embedding model for people clustering | TFLite model file | https://github.com/sirius-ai/MobileFaceNet_TF , https://github.com/deepinsight/insightface |
| JImageHash | perceptual hash for duplicates | Gradle `dev.brachtendorf:JImageHash` (or Kotlin port) | https://github.com/KilianB/JImageHash |
| PySceneDetect / FFmpeg scdet | scene detection (stretch) | Reference | https://github.com/Breakthrough/PySceneDetect , https://github.com/FFmpeg/FFmpeg |

## Scheduling, storage, thermal
| Project | Use | How | Source |
|---|---|---|---|
| WorkManager | charging / idle / battery-full scheduling | Gradle `androidx.work:work-runtime-ktx` | https://github.com/androidx/androidx |
| Room | database | Gradle `androidx.room:room-ktx` + ksp | https://github.com/androidx/androidx |
| DataStore | settings | Gradle `androidx.datastore:datastore-preferences` | https://github.com/androidx/androidx |
| SimpleStorage | SAF folder grants, move/rename | Gradle `com.anggrayudi:storage` | https://github.com/anggrayudi/SimpleStorage |
| ExifInterface | EXIF/XMP copy | Gradle `androidx.exifinterface:exifinterface` | https://github.com/androidx/androidx |
| mp4parser | MP4 creation time / GPS / rotation atoms | Gradle `org.mp4parser:isoparser` | https://github.com/sannies/mp4parser |
| PowerManager thermal headroom, BatteryManager | thermal pause, energy calibration | platform API | — |

## Cross-platform (KMP) — added for ARCHITECTURE.md, reasons in PROJECT.md
| Project | Use | How | Source |
|---|---|---|---|
| Kotlin Multiplatform | one core for Android + iOS (ARCHITECTURE.md § 1) | Gradle plugin `org.jetbrains.kotlin.multiplatform` | https://github.com/JetBrains/kotlin |
| Compose Multiplatform | every screen (ARCHITECTURE.md § 11) | Gradle plugin `org.jetbrains.compose` | https://github.com/JetBrains/compose-multiplatform |
| SQLDelight | shared database — **replaces Room**, which has no Kotlin/Native iOS target | Gradle `app.cash.sqldelight` + plugin | https://github.com/sqldelight/sqldelight |
| Koin | DI — **replaces Hilt**, which is JVM/Android only and cannot generate for Kotlin/Native | Gradle `io.insert-koin:koin-core`, `koin-android` | https://github.com/InsertKoinIO/koin |
| kotlinx-datetime | shared timestamps | Gradle `org.jetbrains.kotlinx:kotlinx-datetime` | https://github.com/Kotlin/kotlinx-datetime |
| AndroidX Lifecycle / Navigation (multiplatform) | shared ViewModel + nav graph | Gradle `org.jetbrains.androidx.lifecycle`, `org.jetbrains.androidx.navigation` | https://github.com/JetBrains/compose-multiplatform-core |

Room and Hilt in the tables above are superseded by SQLDelight and Koin respectively and
have been removed from the version catalog so they cannot be applied by accident.

## Engineering quality
| Project | Use | How | Source |
|---|---|---|---|
| Kotlin Coroutines + Flow | concurrency | Gradle `org.jetbrains.kotlinx:kotlinx-coroutines-android` | https://github.com/Kotlin/kotlinx.coroutines |
| Hilt | DI | Gradle `com.google.dagger:hilt-android` | https://github.com/google/dagger |
| Macrobenchmark + Baseline Profiles | 120 fps gallery | Gradle `androidx.benchmark:benchmark-macro-junit4` | https://github.com/androidx/androidx |
| Perfetto | frame drops, codec stalls | tooling | https://github.com/google/perfetto |
| Detekt | lint | Gradle plugin `io.gitlab.arturbosch.detekt` | https://github.com/detekt/detekt |
| ktlint | formatting | Gradle plugin `org.jlleitschuh.gradle.ktlint` | https://github.com/ktlint/ktlint |
| Turbine | Flow testing | Gradle `app.cash.turbine:turbine` | https://github.com/cashapp/turbine |
| cargo-ndk | Rust builds | tooling | https://github.com/bbqsrc/cargo-ndk |
| Tdarr | library state-machine reference | Reference | https://github.com/HaveAGitGat/Tdarr |

## Native build layout
```
native/
  CMakeLists.txt          # top-level, arm64-v8a only, -march=armv8-a+simd
  xpsnr/                  # submodule fraunhoferhhi/xpsnr
  vmaf/                   # submodule Netflix/vmaf   (libvmaf via meson → static lib)
  libjxl/                 # submodule libjxl/libjxl  (jpegli + ssimulacra2)
  libheif/                # submodule strukturag/libheif
  oxipng/                 # cargo-ndk crate wrapper
  jni/                    # one thin JNI bridge per library
```

## Skills to write for Claude Code (in .claude/skills/)
- `ndk-build`: CMake setup for arm64 native libs, NEON flags, meson-inside-CMake for libvmaf, JNI bridge pattern
- `codec-priority`: KEY_PRIORITY=1, codec reclaim handling, performance points, no software fallback ever
- `safe-replace`: originals are read-only until final rename; size+mtime check; undo/offload flow; lastModified reset; rescan

## Anthropic open-source (for Claude Code itself, verified 30 Aug 2026)
| Repo | Use | Link |
|---|---|---|
| anthropics/skills | Official skills library; copy the skill format and any relevant skills (e.g. frontend design) into `.claude/skills/` | https://github.com/anthropics/skills |
| anthropics/claude-code | Claude Code docs, examples, issue tracker | https://github.com/anthropics/claude-code |
| anthropics/claude-code-action | GitHub Action to run Claude Code on PRs / CI reviews | https://github.com/anthropics/claude-code-action |
| anthropics/claude-cookbooks | Patterns and examples | https://github.com/anthropics/claude-cookbooks |

Setup step for Claude Code (paths verified by cloning on 30 Aug 2026):
```
git clone --depth 1 https://github.com/anthropics/skills.git /tmp/anthropic-skills
mkdir -p .claude/skills
cp -r /tmp/anthropic-skills/skills/frontend-design .claude/skills/frontend-design
cp -r /tmp/anthropic-skills/skills/skill-creator  .claude/skills/skill-creator
cp -r /tmp/anthropic-skills/skills/webapp-testing .claude/skills/webapp-testing
cp    /tmp/anthropic-skills/template/SKILL.md     .claude/skills/TEMPLATE.md
```
Then create `ndk-build`, `codec-priority` and `safe-replace` under `.claude/skills/` using TEMPLATE.md (or the skill-creator skill). Add `claude-code-action` to `.github/workflows/` so every PR gets an automated review against BUILD.md.
