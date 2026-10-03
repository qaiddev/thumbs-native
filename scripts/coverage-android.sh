#!/usr/bin/env bash
# Unit-test coverage for the Android SDK, with a gate on its pure package.
#
#   scripts/coverage-android.sh
#
# Runs the debug unit tests under Kover (configured in android/build.gradle.kts, the
# standalone root build: apps that include :qaidfeedback by source never load Kover) and
# prints a per-file table. Writes:
#   coverage/android/core.xml       dev.qaid.feedback.core only: the gated scope
#   coverage/android/all.xml        everything, internal/ and QaidFeedback.kt included
#   coverage/android/html/          the same, browsable
#   coverage/android/totals.json    {"lines", "branches"} for core, for coverage.sh
# Exits non-zero when a test fails or core falls under the gate (koverVerifyCore).
set -euo pipefail

export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
OUT="$ROOT/coverage/android"
REPORTS="$ANDROID/qaidfeedback/build/reports/kover"
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f "$ANDROID/local.properties" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

rm -rf "$OUT"
mkdir -p "$OUT"

echo "== Android: debug unit tests under Kover"
# --continue: the reports are written even when the gate fails, so the table shows why.
status=0
"$ANDROID/gradlew" -p "$ANDROID" --no-daemon --continue -q \
    :qaidfeedback:koverXmlReportCore :qaidfeedback:koverXmlReportDebug :qaidfeedback:koverHtmlReportDebug \
    :qaidfeedback:koverVerifyCore || status=$?

[ -f "$REPORTS/reportCore.xml" ] || { echo "no Kover report: the tests did not run" >&2; exit 1; }
cp "$REPORTS/reportCore.xml" "$OUT/core.xml"
cp "$REPORTS/reportDebug.xml" "$OUT/all.xml"
[ -d "$REPORTS/htmlDebug" ] && cp -R "$REPORTS/htmlDebug" "$OUT/html"

/usr/bin/python3 - "$OUT" <<'PY'
import json, os, sys
import xml.etree.ElementTree as ET

out = sys.argv[1]

def pct(c, t):
    missed, covered = c.get(t, (0, 0))
    total = missed + covered
    return (100.0 * covered / total if total else 100.0), covered, total

def counters(node):
    return {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in node.findall("counter")}

def table(path, title):
    root = ET.parse(path).getroot()
    print(f"\n== Android: {title}")
    print(f"{'File':<44} {'Lines':>17} {'Branches':>17}")
    for pkg in root.findall("package"):
        for sf in sorted(pkg.findall("sourcefile"), key=lambda s: s.get("name")):
            c = counters(sf)
            name = (pkg.get("name").removeprefix("dev/qaid/feedback").strip("/") + "/" + sf.get("name")).lstrip("/")
            cells = []
            for t in ("LINE", "BRANCH"):
                p, cov, tot = pct(c, t)
                cells.append(f"{p:6.2f}% {cov:>4}/{tot:<4}" if tot else f"{'-':>17}")
            print(f"{name:<44} {cells[0]:>17} {cells[1]:>17}")
    c = counters(root)
    lines, branches = pct(c, "LINE")[0], pct(c, "BRANCH")[0]
    print(f"{'TOTAL':<44} {lines:6.2f}%{'':>10} {branches:6.2f}%")
    return lines, branches

table(os.path.join(out, "all.xml"), "everything (internal/ needs a device; not gated)")
lines, branches = table(os.path.join(out, "core.xml"), "dev.qaid.feedback.core (gated)")
json.dump({"lines": round(lines, 2), "branches": round(branches, 2)}, open(os.path.join(out, "totals.json"), "w"), indent=2)
PY

if [ "$status" -ne 0 ]; then
    echo "Android coverage failed: a test failed or core is under the gate (see above)" >&2
    exit "$status"
fi
echo "core gate ok (koverVerifyCore)"
