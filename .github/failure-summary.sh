#!/usr/bin/env bash
# Prints why a Gradle build failed, from the logs named on the command line.
#
# Two audiences, one script. On stdout it prints the interesting lines and a tail, for
# anyone reading the job log. It also emits them as `::error::` annotations, which survive
# in the check run even when the job ends in two hundred lines of submodule cleanup and the
# log tail is useless.
#
# The filter deliberately does not match `^> `: that catches every "> Task :x:y" line
# Gradle prints, and once this build got far enough to do real work there were several
# hundred of them — they filled the summary and pushed the actual failure past `head`.
set -u

# The last alternative is ktlint's own format — `/path/File.kt:12:5 Message` — which none
# of the Gradle-shaped patterns match, so a style violation used to reach the annotations
# as nothing but the name of the task that failed.
#
# `FAILED: `, `error: ` and `CMake Error` are the ninja/clang/CMake half. Without them a
# native build failure summarised to nothing but AGP's ProcessException heading and the
# ninja command line it ran, which names every target but not one diagnostic.
pattern='^e: |FAILURE: |What went wrong|Caused by: |> Task .* FAILED|Analysis failed|VIOLATION|misconfigured|no manifests to scan|no sources to scan|^/.*\.kts?:[0-9]+:[0-9]+ |^FAILED: |error: |CMake Error|ninja: error|undefined (reference|symbol)'

for log in "$@"; do
  [ -f "$log" ] || continue
  echo "=== $log ==="
  {
    grep -E "$pattern" "$log" | head -30 || true
    # The heading on its own says nothing: the guards and the manifest merger put the whole
    # explanation in the lines *after* "What went wrong", which no pattern above matches.
    grep -A4 "What went wrong" "$log" | grep -vE "^--$|What went wrong" | head -20 || true
  } | grep -vE '^\s*$' | head -40 > /tmp/failure-summary.txt
  cat /tmp/failure-summary.txt
  echo "--- tail ---"
  tail -25 "$log" || true
  while IFS= read -r line; do
    [ -n "$line" ] && echo "::error::$line"
  done < <(head -8 /tmp/failure-summary.txt)
done
