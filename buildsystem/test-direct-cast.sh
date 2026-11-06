#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" application/vlc-android/src/org/videolan/vlc/cast/CastFrame.java \
  application/vlc-android/src/org/videolan/vlc/cast/CastSubtitles.java \
  application/vlc-android/src/org/videolan/vlc/cast/CastFileServer.java \
  application/vlc-android/test/org/videolan/vlc/cast/CastCoreTest.java
java -ea -cp "$OUT" org.videolan.vlc.cast.CastCoreTest
