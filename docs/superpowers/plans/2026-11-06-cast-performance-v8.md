# Cast Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the normal second movie-writing pass from Dolby audio preparation while preserving stable playback, seeking and captions.

**Architecture:** Overlap native audio-only TS encoding with the existing Android video/AAC copy path using a growing MediaDataSource. Keep .7 audio-only/Android remux as fallback. Preserve the existing HTTP serving strategy and test concurrent sustained transfers.

**Tech Stack:** Kotlin, LibVLC 3.7.7, Android MediaExtractor, Java HTTP server.

**Spec:** docs/superpowers/specs/2026-11-06-cast-performance-v8-design.md

## Global Constraints
- Preserve package com.strokedot.bluevlc and permanent signing key.
- Never video-transcode; preserve every decoded frame, relative timeline and rotation.
- Keep seeking, external SRT/WebVTT, pause/play and volume controls.
- Limit changes to conversion performance and evidence-based streaming improvements.

## Review Focus
- Last buffered frame at EndReached: verify complete output after Stopped.
- B-frame timelines and HEVC packetization: compare decoded video and normalized timestamps.
- Cancellation/error: release writer before deleting any partial output; keep .7 recovery.
- Provider content files: retain URI staging ownership and cleanup.
- Busy HTTP requests: concurrent ranges plus captions must complete with exact payloads.

### Task 1: Concurrent audio/video preparation
- [ ] Add regression cases comparing .7's intermediate-audio behavior against single-pass preparation, plus H264 B-frame and HEVC fixtures and completion/error/cancellation checks.
- [ ] Implement audio-only CastNativePreparation.convert plus CastPipelinedPreparation, waiting for Stopped and feeding timestamped AAC to the existing Android muxer while encoding runs.
- [ ] Test exact video sample/timestamp preservation, decoded pixels, size/rotation and stereo AAC; select existing .7 fallback on live-source failure.
- [ ] Record phase duration and whether the fallback was necessary. Keep one accurate progress phase per actual operation.
- [ ] Run Android codec tests, compare all decoded fixture frames and record a larger-clip timing comparison.

### Task 2: Delivery and release
- [ ] Exercise sustained overlapping HTTP ranges and captions; change serving only for a demonstrated defect.
- [ ] Run core suite and Android build, obtain independent review, address findings once.
- [ ] Build/sign .8, verify certificate and test gate, publish and merge. Report software measurements separately from actual phone/TV acceptance.
