# Direct Cast Implementation Plan

> Execute inline with test-first core behavior and a final independent review.

**Goal:** Deliver a testable direct casting path with external subtitles and
seekable audio preparation, preserving VLC's existing renderer fallback.

**Architecture:** Java core for bounded Cast protobuf framing, byte-range file
HTTP and subtitle conversion. Kotlin Android service for TLS Cast protocol,
NSD receiver discovery and MediaCodec preparation. Activity owns controls only.

**Tech stack:** Existing Android/Kotlin/Java; no new third-party dependency.

**Spec:** `docs/superpowers/specs/2026-11-06-direct-cast-design.md`.

## Global constraints

Same `com.strokedot.bluevlc` application and signing identity. No video encoding
in the new path. Captions delivered as WebVTT. Local files only. Android 5+
for preparation; older devices retain native casting. No unsupported success
claims. Preserve work on the feature branch until Android build passes.

## Review focus

Receiver disconnect during preparation; malformed subtitle and range requests;
session replacement without leaked servers; unseekable content URIs; screen-off
service survival. Test the pure parts and inspect Android lifecycle paths.

## Tasks

1. Core: add failing JVM tests for bounded protobuf, SRT/WebVTT conversion and
   real HTTP GET/HEAD/range/OPTIONS/unauthorized requests. Implement those
   independent Java classes, run tests, commit.
2. Cast transport/service: implement Default Media Receiver launch/LOAD,
   actual text tracks, session ID commands, heartbeat/error handling, token
   HTTP endpoints and foreground lifecycle. Prepare finished MP4 by video
   sample copy and AAC audio conversion; cancellation cleans up temporary files.
3. UI: discover multiple receivers; integrate player's Cast button, controls,
   subtitle picker, preparation status and native fallback. Update version.
4. Verification: CI core tests and Android APK build, inspect build outputs;
   independent whole-branch review, fix material findings, signed test build
   after verified compile. Record unresolved device tests honestly.
