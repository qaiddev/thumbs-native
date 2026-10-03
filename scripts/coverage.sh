#!/usr/bin/env bash
# Both platforms' unit-test coverage, gated, then the README badge.
#
#   scripts/coverage.sh
#
# Runs scripts/coverage-ios.sh, then scripts/coverage-android.sh (one after the other: each
# is a full build), and writes coverage-badge.json at the repo root in shields.io's endpoint
# format. The badge shows the lower of the two platforms' gated line coverage. Commit the
# badge: the README reads it from the prod branch on GitHub.
set -euo pipefail

export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

"$ROOT/scripts/coverage-ios.sh"
echo
# Android: dev.qaid.thumbs.core through Kover (JVM tests + Robolectric UI tests); writes
# coverage/android/totals.json with core "lines"/"branches" (gated) and "ui" (reported only).
coverage_android() {
    "$ROOT/scripts/coverage-android.sh"
}
coverage_android

/usr/bin/python3 - "$ROOT" <<'PY'
import json, math, os, sys

root = sys.argv[1]
ios = json.load(open(os.path.join(root, "coverage/ios/totals.json")))["lines"]
android = json.load(open(os.path.join(root, "coverage/android/totals.json")))["lines"]
# Same rounding and colours as the embeds' scripts/coverage-badge.ts.
pct = math.floor(min(ios, android) * 10 + 0.5) / 10  # Math.round, not banker's
message = f"{pct:g}%"
color = next(c for floor, c in ((90, "brightgreen"), (80, "green"), (70, "yellowgreen"),
                                (60, "yellow"), (50, "orange"), (0, "red")) if pct >= floor)
badge = {"schemaVersion": 1, "label": "coverage", "message": message, "color": color}
with open(os.path.join(root, "coverage-badge.json"), "w") as f:
    f.write(json.dumps(badge, indent=2) + "\n")
print(f"\niOS core lines {ios:.2f}%, Android core lines {android:.2f}%")
android_ui = json.load(open(os.path.join(root, "coverage/android/totals.json"))).get("ui")
if android_ui:
    print(f"Android ui/internal (not gated): lines {android_ui['lines']:.2f}%, branches {android_ui['branches']:.2f}%")
print(f"coverage-badge.json written: {message} ({color})")
PY
