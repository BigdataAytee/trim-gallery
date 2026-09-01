#!/usr/bin/env python3
"""Fail unless every screen journey actually ran on the emulator, and say why if not.

A passing Gradle task is weaker evidence than it looks. `pixelSmokeAndroidTest` is green
when it runs zero tests, when a filter excludes a class, when the androidTest component was
built for a variant that has none, and when every test in it called `assumeTrue` and
skipped. Each of those leaves the job reporting success having asserted nothing about the
app — which is precisely the failure these journeys exist to end.

So this reads the instrumentation's own result XML back and requires each journey to be
present, and to have neither failed, errored nor skipped. It is deliberately explicit about
which tests it expects: adding a journey means naming it here, and a journey deleted by
accident fails the build rather than shrinking the suite in silence.

It also **prints the failure**. Gradle's own summary says only "There were failing tests,
see the report at file:///..." — a path on a machine that no longer exists — and the report
is uploaded to an artifact store that some environments cannot reach. The stack trace is
right there in the XML, so a red run should hand it over rather than describe where it
would have been. One red cycle that names the exception beats three that do not.
"""

import os
import pathlib
import sys
import xml.etree.ElementTree as ElementTree

RESULTS = pathlib.Path("androidApp/build/outputs/androidTest-results")

# Suite -> the tests in it that must have run and passed.
REQUIRED = {
    "app.trimgallery.ui.GalleryJourneyTest": [
        "grantingAFolderRendersTheGrid",
        "tappingAPhotoOpensTheViewer",
        "tappingAVideoPlaysIt",
        "relaunchingAfterAGrantRendersTheGrid",
        "aStartupThatFailsLandsOnRecoveryAndDoesNotRetry",
    ],
    # The real Activity over the real graph with a folder granted, which is what a phone
    # does on every launch after the first and what no test covered until a build shipped
    # that crashed doing it.
    # The decoder the VMAF gate reads through. It can be proved here, unlike the encoder:
    # an ATD image has no hardware encoder but decoding in software is ordinary.
    "app.trimgallery.engine.android.YuvSourceAndroidTest": [
        "decodesRealFramesAtTheRequestedWidth",
        "thePlanesAreExactlyTheSizeTheMetricsWillReadThemAs",
        "theFramesHoldAPictureRatherThanAnEmptyBuffer",
        "aLaterWindowIsADifferentPictureFromTheFirst",
        "aWidthLargerThanTheSourceIsNotUpscaled",
        "anUnreadableFileIsAnEmptyWindowRatherThanACrash",
    ],
    # The encoder half of the search. Only the two tests that can run on any machine are
    # required: the rest need a hardware encoder, which an ATD image does not have, and
    # BUILD.md rule 2 forbids the software one. They are reported as not-run rather than
    # required, so a green CI run never implies an encode was proved here.
    "app.trimgallery.engine.android.ProbeEncoderAndroidTest": [
        "theOutcomeMatchesWhatTheDeviceSaysItCanEncode",
        "anEmptyWindowIsAnEmptyWindowRatherThanACrash",
    ],
    # Long-press to Optimise, over a faked step. The encode itself cannot run here — no
    # hardware encoder on an ATD image — so what these prove is everything between the
    # finger and the encoder, which is where the screen is.
    "app.trimgallery.ui.OptimiseJourneyTest": [
        "holdingAVideoOffersToOptimiseIt",
        "startingItReportsWhatChanged",
        "theRunIsRecordedSoSpaceCanShowIt",
        "aRunThatReplacedNothingNeverOffersAnUndo",
        "undoPutsTheOriginalBack",
    ],
    # That the night pass can build what it runs. A missing Koin definition compiles, passes
    # every unit test, and fails at 3am in a worker with no screen — so it is asked of the
    # assembled graph on a device, which is the only place the question can be put.
    "app.trimgallery.engine.android.NightWiringTest": [
        "theNightPassCanResolveEverythingItRuns",
        "theWholeOptimiseChainResolves",
    ],
    "app.trimgallery.ui.MainActivityGrantedLaunchTest": [
        "theRealActivityWithAFolderGrantedDrawsTheGrid",
        "theLaunchMarkIsClearedOnceAFrameIsDrawn",
    ],
}


"""How much of a failure to print. Enough to name the exception and the frames in our own
code; short enough that ten failures do not bury the one that matters."""
MESSAGE_CHARS = 2000
TRACE_LINES = 40

"""Where to *also* write the failure, so it survives being read from a distance.

This step's output sits in the middle of a job that ends in two hundred lines of submodule
cleanup, and reading it back through the API means guessing a tail length. Four separate
attempts to read one failure landed one line short of it, each costing a CI round trip. The
last step of the job prints the tail of this log, so appending here puts the diagnosis
somewhere it cannot be missed — and `failure-summary.sh` hoists `JOURNEY:` lines into its
summary and into the check-run annotations."""
ALSO_APPEND_TO = os.environ.get("JOURNEY_LOG", "/tmp/smoke.log")
JOURNEY_PREFIX = "JOURNEY: "


def main() -> int:
    reports = sorted(RESULTS.rglob("*.xml"))
    if not reports:
        print(f"No instrumentation results under {RESULTS}. The journeys did not run.")
        return 1

    expected = [
        f"{suite.rsplit('.', 1)[-1]}.{name}"
        for suite, names in REQUIRED.items()
        for name in names
    ]
    required = set(expected)

    ran, bad, stood_down = set(), [], []
    for report in reports:
        for case in ElementTree.parse(report).iter("testcase"):
            suite = case.get("classname")
            if suite not in REQUIRED:
                continue
            name = f"{suite.rsplit('.', 1)[-1]}.{case.get('name', '')}"
            # A required suite may hold a test this machine cannot run — the encode
            # assertions need a hardware encoder, and an ATD image has none. Requiring those
            # would leave two bad options: a CI run that is always red, or a suite that
            # pretends to prove an encode it never performed. So they are named as not
            # required and *listed* below instead, and the run says which ones stood down.
            if name not in required:
                if case.find("skipped") is not None:
                    stood_down.append(name)
                continue
            ran.add(name)
            for outcome in ("failure", "error", "skipped"):
                node = case.find(outcome)
                if node is not None:
                    bad.append((name, outcome, node.get("message", ""), (node.text or "")))

    missing = [name for name in expected if name not in ran]
    if missing:
        print(f"These journeys did not run at all: {', '.join(missing)}")
    for name, outcome, message, detail in bad:
        print(f"\n--- {name}: {outcome} " + "-" * 40)
        if message:
            print(message.strip()[:MESSAGE_CHARS])
        trace = detail.strip()
        if trace:
            print("\n".join(trace.splitlines()[:TRACE_LINES]))
    if missing or bad:
        print(f"Read {len(reports)} report(s) under {RESULTS}.")
        _also_append(missing, bad)
        return 1

    print(f"All {len(expected)} screen journeys ran and passed on the device.")
    if stood_down:
        # Not a failure, and not silence either: this is the line that keeps a green tick
        # from being read as "everything was proved here".
        print(f"Not proved on this machine ({len(stood_down)} stood down): {', '.join(sorted(set(stood_down)))}")
    return 0


def _also_append(missing, bad) -> None:
    """Writes one line per problem where the job's final step will echo it.

    One line each, and the first line of the message, because a diagnosis worth reading
    from a distance has to fit in a tail and in a check annotation. The full message and
    trace are still printed above for anyone reading this step directly.
    """
    # Failures first, then the ones that never ran. `failure-summary.sh` keeps the first
    # thirty matching lines, and a suite that did not run at all can produce dozens — which
    # would push the one real failure past the cut, which is the whole bug being fixed here.
    lines = [
        f"{JOURNEY_PREFIX}{name}: {outcome}: {(message or detail).strip().splitlines()[0][:400]}"
        for name, outcome, message, detail in bad
        if (message or detail).strip()
    ]
    lines += [f"{JOURNEY_PREFIX}{name} did not run at all" for name in missing]
    if not lines:
        return
    try:
        with open(ALSO_APPEND_TO, "a", encoding="utf-8") as log:
            log.write("\n" + "\n".join(lines) + "\n")
    except OSError as failure:
        # Never let the reporting be the thing that fails the reporting.
        print(f"(could not append to {ALSO_APPEND_TO}: {failure})")


if __name__ == "__main__":
    sys.exit(main())
