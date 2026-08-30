#!/usr/bin/env bash
# Resolves the latest stable version of every dependency in gradle/libs.versions.toml
# that is hosted on Google Maven.
#
# Why this exists: the version catalog was written in an environment whose egress
# policy blocked dl.google.com, so every [google] entry is a best-known-good guess.
# Run this once from a machine that can reach Google Maven and reconcile the output
# with the catalog before the first build.
set -euo pipefail

GOOGLE="https://dl.google.com/dl/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"

latest_stable() {
  local base="$1" path="$2"
  curl -fsS --max-time 30 "$base/$path/maven-metadata.xml" 2>/dev/null \
    | grep -oP '(?<=<version>)[^<]+' \
    | grep -viE 'alpha|beta|rc|-M[0-9]|eap|dev' \
    | tail -1
}

report() {
  local base="$1" path="$2" label="$3"
  local v
  v="$(latest_stable "$base" "$path" || true)"
  printf '%-46s %s\n' "$label" "${v:-UNRESOLVED}"
}

echo "=== Google Maven ==="
report "$GOOGLE" com/android/tools/build/gradle                     "AGP (agp)"
report "$GOOGLE" androidx/compose/compose-bom                       "Compose BOM (composeBom)"
report "$GOOGLE" androidx/activity/activity-compose                 "activity-compose (activityCompose)"
report "$GOOGLE" androidx/lifecycle/lifecycle-runtime-compose       "lifecycle (lifecycle)"
report "$GOOGLE" androidx/media3/media3-transformer                 "media3 (media3)"
report "$GOOGLE" androidx/work/work-runtime-ktx                     "work (work)"
report "$GOOGLE" androidx/room/room-ktx                             "room (room)"
report "$GOOGLE" androidx/datastore/datastore-preferences           "datastore (datastore)"
report "$GOOGLE" androidx/exifinterface/exifinterface               "exifinterface (exifinterface)"
report "$GOOGLE" androidx/documentfile/documentfile                 "documentfile (documentfile)"
report "$GOOGLE" androidx/benchmark/benchmark-macro-junit4          "benchmark (benchmark)"
report "$GOOGLE" androidx/test/ext/junit                            "androidx-test-junit (androidxTestJunit)"
report "$GOOGLE" androidx/test/espresso/espresso-core               "espresso (espresso)"
report "$GOOGLE" com/google/accompanist/accompanist-permissions     "accompanist (accompanist)"
report "$GOOGLE" com/google/mlkit/image-labeling                    "ML Kit labels (mlkit-imageLabeling)"
report "$GOOGLE" com/google/mlkit/face-detection                    "ML Kit faces (mlkit-faceDetection)"
report "$GOOGLE" com/google/mlkit/text-recognition                  "ML Kit OCR (mlkit-textRecognition)"
report "$GOOGLE" com/google/ai/edge/litert/litert                   "LiteRT (litert)"

echo
echo "=== Maven Central (already verified, re-check on upgrade) ==="
report "$CENTRAL" org/jetbrains/kotlin/kotlin-stdlib                "Kotlin (kotlin)"
report "$CENTRAL" com/google/devtools/ksp/symbol-processing-api     "KSP (ksp)"
report "$CENTRAL" org/jetbrains/kotlinx/kotlinx-coroutines-android  "Coroutines (coroutines)"
report "$CENTRAL" com/google/dagger/hilt-android                    "Hilt (hilt)"
report "$CENTRAL" io/coil-kt/coil3/coil-compose                     "Coil 3 (coil)"
report "$CENTRAL" me/saket/telephoto/zoomable-image-coil3           "Telephoto (telephoto)"
report "$CENTRAL" com/airbnb/android/lottie-compose                 "Lottie (lottie)"
report "$CENTRAL" com/linkedin/android/litr/litr                    "LiTr (litr)"
report "$CENTRAL" com/anggrayudi/storage                            "SimpleStorage (simpleStorage)"
report "$CENTRAL" org/mp4parser/isoparser                           "mp4parser (isoparser)"
report "$CENTRAL" dev/brachtendorf/JImageHash                       "JImageHash (jimagehash)"
report "$CENTRAL" app/cash/turbine/turbine                          "Turbine (turbine)"
report "$CENTRAL" io/gitlab/arturbosch/detekt/detekt-gradle-plugin  "Detekt (detekt)"

echo
echo "KSP must pair with the Kotlin minor version. Reconcile both together."
