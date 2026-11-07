# Casting tuning and subtitle appearance

Goal: reduce avoidable preparation and delivery work; add saved casting subtitle appearance with preview/Apply. User requests focused implementation and bounded verification; proceed inline without extra design rounds or repeated broad testing.

1. Delivery: use channel file transfer for regular files, retain existing InputStream route for providers and zero-progress fallback. Preserve exact ranges/captions/close semantics. No frame synthesis or claims of measured TV FPS.
2. Preparation: reuse readable MediaStore paths instead of staging full content files; use libavcodec's documented fast AAC coder only in the overlapping path at existing stereo/192kbps/48kHz. Keep .8 native settings in compatibility fallback. Compare .8 overlap versus fast overlap once on the existing two-minute fixture.
3. Subtitle appearance: immutable bounded style model, standard receiver generic fonts, scale, text/background colors, outline/shadow, bold/italic. Saved defaults, live phone preview, Cancel changes nothing. Newly chosen file waits for Apply; existing subtitle styles update via EDIT_TRACKS_INFO without LOAD. Receiver may choose its own installed font.
4. Verify: one core run per meaningful code change, local Kotlin compile and protocol check, one signed Android release gate including existing codec/frame preservation checks and focused style/provider tests. One compact final review. Publish .9 with the existing app ID/certificate after gate passes.

Risks checked: partial channel transfers/large offsets; provider streams without file paths; unsupported encoder uses established fallback; malformed saved preferences; style change must not reload video or end casting on receiver rejection.
