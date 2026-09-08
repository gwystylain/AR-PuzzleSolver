#!/usr/bin/env bash
#
# Collects everything needed to debug a scan that went wrong, into one folder.
#
#   tools/collect-session.sh [output-dir] [--with-sessions] [--no-gems]
#
# Run it straight after the attempt, with the phone still connected and the app
# still in the foreground. The canvas dump and the heartbeat log are both live
# state: killing the app first throws away the two most useful artifacts.
#
# On Git Bash, MSYS rewrites arguments that look like Unix paths into Windows
# ones, so `adb pull /sdcard/...` fails with a baffling "No such file" naming
# `C:/Program Files/Git/sdcard/...`. MSYS_NO_PATHCONV=1 is what stops that.

set -u

PKG=com.puzzlesolver.app
REMOTE=/sdcard/Android/data/$PKG/files
OUT=""
WITH_SESSIONS=0
WITH_GEMS=1

for arg in "$@"; do
  case "$arg" in
    --with-sessions) WITH_SESSIONS=1 ;;
    --no-gems)       WITH_GEMS=0 ;;
    -*)              echo "unknown option: $arg" >&2; exit 2 ;;
    *)               OUT=$arg ;;
  esac
done
OUT=${OUT:-session-$(date +%Y%m%d-%H%M%S)}

ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || {
  echo "adb not on PATH. Set ADB=/path/to/adb, or add platform-tools:" >&2
  echo "  export PATH=\$PATH:\$HOME/AppData/Local/Android/Sdk/platform-tools" >&2
  exit 1
}

"$ADB" get-state >/dev/null 2>&1 || { echo "no device connected" >&2; exit 1; }

mkdir -p "$OUT"
echo "collecting into $OUT"

# 1. The canvas, in colour. The single most useful artifact: every stage after the
#    mosaic consumes this image, and it can be replayed offline against :core.
echo "  requesting canvas dump..."
"$ADB" shell am broadcast -a $PKG.DEBUG --ez dump true >/dev/null 2>&1
sleep 3
MSYS_NO_PATHCONV=1 "$ADB" pull "$REMOTE/canvas-dump.png" "$OUT/canvas-dump.png" >/dev/null 2>&1 \
  && echo "  canvas-dump.png" \
  || echo "  no canvas dump -- nothing was observed, which is itself the finding"

# 2. The log. Carries the heartbeat: grid, pitch, coverage, tile counts, why
#    detection declined, and the read tally.
"$ADB" logcat -d -v time > "$OUT/logcat-full.txt" 2>/dev/null
grep -E "ScanPipeline|MainActivity|AndroidRuntime|FATAL" "$OUT/logcat-full.txt" \
  > "$OUT/logcat-app.txt" 2>/dev/null
echo "  logcat-app.txt ($(wc -l < "$OUT/logcat-app.txt" 2>/dev/null || echo 0) lines)"

# 3. ARCore recordings, if any. These are the gold standard: playback re-runs real
#    tracking over the recorded camera and IMU, so poses and depth behave exactly
#    as they did in the room. Large, so they are listed rather than pulled by
#    default -- pass --with-sessions to take them.
if [ "$WITH_SESSIONS" = 1 ]; then
  MSYS_NO_PATHCONV=1 "$ADB" pull "$REMOTE/sessions" "$OUT/sessions" >/dev/null 2>&1 \
    && echo "  sessions/ pulled"
else
  MSYS_NO_PATHCONV=1 "$ADB" shell ls -la "$REMOTE/sessions" > "$OUT/sessions-listing.txt" 2>&1
  echo "  sessions listed only (re-run with --with-sessions to pull them)"
fi

# 4. Gem captures. Live Gems has neither of the two artifacts above: it runs on its
#    own Camera2 source, so there is no ARCore session to record and no canvas to
#    dump. What it has instead is the frames the scanner actually read, each paired
#    with the reading it made of that frame, written by the Capture button.
#
#    Pulled by default, unlike sessions -- the frames are gzipped PPMs of a mostly
#    black wall, so a whole run is a few megabytes rather than a few hundred.
#
#    Deliberately NOT triggered from here, unlike the canvas dump above. By the time
#    this script runs the phone is on a desk beside a laptop, and a capture taken then
#    records the ceiling. Press Capture at the wall; this only collects what that wrote.
if MSYS_NO_PATHCONV=1 "$ADB" shell ls "$REMOTE/gems" >/dev/null 2>&1; then
  if [ "$WITH_GEMS" = 1 ]; then
    MSYS_NO_PATHCONV=1 "$ADB" pull "$REMOTE/gems" "$OUT/gems" >/dev/null 2>&1
    # Unpacked here rather than left packed: these are P6 PPMs, which is the format
    # core's GemFixture loads, so an unpacked frame drops straight into
    # core/src/test/resources and turns the failure into a regression test.
    if command -v gunzip >/dev/null 2>&1; then
      gunzip -f "$OUT"/gems/*.ppm.gz 2>/dev/null
    fi
    FRAMES=$(ls "$OUT"/gems/*.ppm "$OUT"/gems/*.ppm.gz 2>/dev/null | wc -l | tr -d ' ')
    echo "  gems/ ($FRAMES frames)"
    # The log is the index into those frames, so it belongs at the top of the bundle
    # rather than buried among them.
    if [ -f "$OUT/gems/gems-log.txt" ]; then
      mv "$OUT/gems/gems-log.txt" "$OUT/gems-log.txt"
    fi
  else
    LOG=$REMOTE/gems/gems-log.txt
    MSYS_NO_PATHCONV=1 "$ADB" pull "$LOG" "$OUT/gems-log.txt" >/dev/null 2>&1
    echo "  gem frames skipped (--no-gems)"
  fi
  if [ -f "$OUT/gems-log.txt" ]; then
    echo "  gems-log.txt (pitch, exposure, and every gem's three rings, per frame)"
  fi
else
  echo "  no gem captures -- press Capture in Gems mode while pointing at the wall"
fi

# 5. Device and build identity, so a report is not ambiguous about what ran.
{
  echo "model:    $("$ADB" shell getprop ro.product.model | tr -d '\r')"
  echo "android:  $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
  echo "arcore:   $("$ADB" shell dumpsys package com.google.ar.core \
      | grep -m1 versionName | tr -d '\r' | sed 's/^ *//')"
  echo "app:      $("$ADB" shell dumpsys package $PKG \
      | grep -m1 versionName | tr -d '\r' | sed 's/^ *//')"
} > "$OUT/device.txt" 2>/dev/null
echo "  device.txt"

echo
echo "done. Send the whole $OUT folder."
echo "For Mines: the heartbeat lines in logcat-app.txt and canvas-dump.png."
echo "For Gems:  gems-log.txt and the frames beside it -- that is the whole of it."
