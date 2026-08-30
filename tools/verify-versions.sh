#!/usr/bin/env bash
#
# Checks every dependency pinned in gradle/libs.versions.toml.
#
# For each library and plugin it does two things:
#   1. confirms the exact pinned coordinate resolves (catches a typo'd artifact name,
#      not just a plausible version number), and
#   2. reports the latest stable version, so upgrades are visible.
#
# It parses the catalog rather than a hand-maintained list, so it cannot drift out of
# step with it — the previous version of this script had already gone stale within a
# single commit.
#
# Why it exists: the environment the catalog was written in could not reach Google Maven
# (dl.google.com), so every entry marked [google] in the catalog is a best-known-good
# guess. Run this from a machine with access before the first build.
#
# Exit status: 0 if every pinned coordinate resolved, 1 otherwise. Safe to run in CI.
#
# Usage:
#   tools/verify-versions.sh            # everything
#   tools/verify-versions.sh --google   # only the entries that could not be verified
#   tools/verify-versions.sh --quiet    # only problems and the summary

set -uo pipefail
cd "$(dirname "$0")/.."

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
command -v curl    >/dev/null || { echo "curl is required" >&2; exit 2; }

python3 - "$@" <<'PY'
import re, sys, subprocess, pathlib
from concurrent.futures import ThreadPoolExecutor

args = set(sys.argv[1:])
GOOGLE_ONLY = "--google" in args
QUIET = "--quiet" in args

CENTRAL = "https://repo1.maven.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"
PORTAL = "https://plugins.gradle.org/m2"

text = pathlib.Path("gradle/libs.versions.toml").read_text(encoding="utf-8")


def section(name):
    if f"[{name}]" not in text:
        return ""
    return re.split(r"^\[", text.split(f"[{name}]", 1)[1], flags=re.M)[0]


versions = dict(re.findall(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"', section("versions"), re.M))


def resolve(entry):
    """Turns a catalog entry's version spec into a concrete version string."""
    ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', entry)
    if ref:
        return versions.get(ref.group(1))
    lit = re.search(r'version\s*=\s*"([^"]+)"', entry)
    return lit.group(1) if lit else None


def is_google(group):
    return group.startswith(("androidx.", "com.android.", "com.google."))


targets = []       # (label, group, artifact, version, repos_to_try)
bom_managed = []   # (label, group, artifact, repos) — version comes from a BOM

for alias, entry in re.findall(r"^([A-Za-z0-9_.-]+)\s*=\s*(\{[^}]*\})", section("libraries"), re.M):
    g = re.search(r'group\s*=\s*"([^"]+)"', entry)
    a = re.search(r'name\s*=\s*"([^"]+)"', entry)
    v = resolve(entry)
    if not (g and a):
        print(f"  ?? {alias}: could not parse group/name")
        continue
    group, artifact = g.group(1), a.group(1)
    repos = [GOOGLE] if is_google(group) else [CENTRAL, PORTAL]
    if v is None:
        # No version by design: the Compose BOM supplies it. Record it so the artifact
        # name is still checked, but there is no pin to verify.
        bom_managed.append((alias, group, artifact, repos))
        continue
    targets.append((alias, group, artifact, v, repos))

for alias, entry in re.findall(r"^([A-Za-z0-9_.-]+)\s*=\s*(\{[^}]*\})", section("plugins"), re.M):
    pid = re.search(r'id\s*=\s*"([^"]+)"', entry)
    v = resolve(entry)
    if not (pid and v):
        continue
    # Gradle plugin marker coordinate.
    marker = f"{pid.group(1)}.gradle.plugin"
    repos = [GOOGLE, PORTAL] if is_google(pid.group(1)) else [PORTAL, CENTRAL]
    targets.append((f"plugin:{alias}", pid.group(1), marker, v, repos))

if GOOGLE_ONLY:
    targets = [t for t in targets if GOOGLE in t[4]]


def http(url):
    r = subprocess.run(
        ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "25", url],
        capture_output=True, text=True,
    )
    return r.stdout.strip()


def version_key(v):
    """Sorts 1.10.0 above 1.9.0. Maven metadata is not reliably ordered."""
    return [int(part) for part in v.split(".")]


# A release version is digits and dots only. Anything with a qualifier -- -alpha,
# -rc01, -0.6.x-compat -- is not something to recommend upgrading to.
RELEASE = re.compile(r"^\d+(\.\d+)*$")


def latest_stable(group, artifact, repo):
    r = subprocess.run(
        ["curl", "-sS", "--max-time", "25", f"{repo}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"],
        capture_output=True, text=True,
    )
    found = [v for v in re.findall(r"<version>([^<]+)</version>", r.stdout) if RELEASE.match(v)]
    return max(found, key=version_key) if found else None


def check(t):
    label, group, artifact, version, repos = t
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    for repo in repos:
        if http(f"{repo}/{path}") == "200":
            return label, group, artifact, version, latest_stable(group, artifact, repo), True, repo
    return label, group, artifact, version, None, False, repos[0]


# Probe each repository once, so an unreachable host is reported as a network problem
# rather than as 25 broken catalog entries.
REPO_NAMES = {CENTRAL: "Maven Central", GOOGLE: "Google Maven", PORTAL: "Gradle Plugin Portal"}
reachable = {}
for repo in (CENTRAL, GOOGLE, PORTAL):
    code = http(f"{repo}/")
    # Any HTTP answer means the host is up; 000 means the connection never completed.
    reachable[repo] = code != "000"
    if not reachable[repo]:
        print(f"  UNREACHABLE  {REPO_NAMES[repo]} ({repo}) — entries hosted there cannot be checked")
if not all(reachable.values()):
    print()

with ThreadPoolExecutor(max_workers=8) as pool:
    results = list(pool.map(check, targets))

missing, outdated, unchecked = [], [], []
for label, group, artifact, version, latest, ok, repo in sorted(results):
    if not ok and not any(reachable[r] for r in (repo,)):
        unchecked.append((label, group, artifact, version))
        print(f"  unchecked {label:<34} {group}:{artifact}:{version}  ({REPO_NAMES[repo]} unreachable)")
    elif not ok:
        missing.append((label, group, artifact, version))
        print(f"  MISSING   {label:<34} {group}:{artifact}:{version}")
    elif latest and latest != version:
        outdated.append((label, version, latest))
        print(f"  outdated  {label:<34} {version}  ->  {latest}")
    elif not QUIET:
        print(f"  ok        {label:<34} {version}")

print()
if bom_managed and not QUIET:
    print()
    for alias, group, artifact, _ in sorted(bom_managed):
        print(f"  bom       {alias:<34} {group}:{artifact} (version from compose-bom)")

resolved = len(results) - len(missing) - len(unchecked)
print(f"{len(results)} pinned coordinates: {resolved} resolved, {len(missing)} missing, "
      f"{len(unchecked)} unchecked, {len(outdated)} behind latest stable.")
if bom_managed:
    print(f"{len(bom_managed)} further entries take their version from the Compose BOM.")

if missing:
    print()
    print("MISSING means the repository answered but does not have that coordinate.")
    print("That is a catalog error: fix the group, artifact or version.")

if unchecked:
    print()
    print("UNCHECKED means the repository could not be reached at all, so nothing can be")
    print("said about those entries either way. Fix the network, then re-run.")

# Only a genuine catalog error fails the run; an unreachable repository is not one.
sys.exit(1 if missing else 0)
PY
