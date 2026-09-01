#!/usr/bin/env python3
"""Fail unless every screen journey actually ran on the emulator.

A passing Gradle task is weaker evidence than it looks. `pixelSmokeAndroidTest` is green
when it runs zero tests, when a filter excludes a class, when the androidTest component was
built for a variant that has none, and when every test in it called `assumeTrue` and
skipped. Each of those leaves the job reporting success having asserted nothing about the
app — which is precisely the failure these journeys exist to end.

So this reads the instrumentation's own result XML back and requires each journey to be
present, and to have neither failed, errored nor skipped. It is deliberately explicit about
which tests it expects: adding a journey means naming it here, and a journey deleted by
accident fails the build rather than shrinking the suite in silence.
"""

import pathlib
import sys
import xml.etree.ElementTree as ElementTree

RESULTS = pathlib.Path("androidApp/build/outputs/androidTest-results")

SUITE = "app.trimgallery.ui.GalleryJourneyTest"

REQUIRED = [
    "grantingAFolderRendersTheGrid",
    "tappingAPhotoOpensTheViewer",
    "tappingAVideoPlaysIt",
    "relaunchingAfterAGrantRendersTheGrid",
    "aStartupThatFailsLandsOnRecoveryAndDoesNotRetry",
]


def main() -> int:
    reports = sorted(RESULTS.rglob("*.xml"))
    if not reports:
        print(f"No instrumentation results under {RESULTS}. The journeys did not run.")
        return 1

    ran, bad = set(), []
    for report in reports:
        for case in ElementTree.parse(report).iter("testcase"):
            if case.get("classname") != SUITE:
                continue
            name = case.get("name", "")
            ran.add(name)
            for outcome in ("failure", "error", "skipped"):
                if case.find(outcome) is not None:
                    bad.append(f"{name}: {outcome}")

    missing = [name for name in REQUIRED if name not in ran]
    if missing:
        print(f"These journeys did not run at all: {', '.join(missing)}")
    if bad:
        print(f"These journeys did not pass: {', '.join(bad)}")
    if missing or bad:
        print(f"Read {len(reports)} report(s) under {RESULTS}.")
        return 1

    print(f"All {len(REQUIRED)} screen journeys ran and passed on the device.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
