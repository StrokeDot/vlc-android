# Direct casting with external subtitles

Alejandro delegates implementation choices and asks for smooth, ad-free casting
with external subtitles on his TCL Google TV and Chromecast, including useful
seeking when audio conversion is needed. Existing VLC Plus .5 only exposes a
subtitle selector; it does not deliver text tracks. Both VLC applications now
stutter in his tests. The supplied log confirms LibVLC 3.7.7 but contains no
playback trace, so the original stutter's cause remains unproven.

## Approach

Use a separate Cast V2 connection to the Default Media Receiver, a local HTTP
file server with byte ranges, and SRT-to-WebVTT subtitle delivery. This avoids
video decoding/encoding for compatible media. Keep the existing VLC renderer
as an explicitly available fallback. Use existing Android/Kotlin/Java APIs;
do not add a proprietary SDK to the GPL application. Protocol references:
VideoLAN's Chromecast implementation, PyChromecast, and Google's media messages
and text-track documentation. Implement the protocol independently.

Alternatives: modifying LibVLC requires a native engine rebuild and does not
provide a seekable direct file endpoint; burning captions forces video encoding
and works against performance. Neither is the first implementation choice.

## Behavior and constraints

- The player's Cast button opens a receiver list and remote playback controls.
- Discovery uses Android NSD for `_googlecast._tcp.`; handle multiple receivers.
- Serve original local MP4/WebM files directly when possible. A receiver error
  is visible, never represented as successful playback.
- Offer preparation of an MP4: copy the video samples and convert audio to AAC
  with Android MediaCodec. The finished file is served with byte ranges so the
  receiver can seek; preparation takes time and temporary storage, shown clearly.
- External SRT or WebVTT files are selectable; send actual media text-track URLs
  and active IDs. Embedded subtitles and ASS/bitmap conversion are future work.
- A foreground service owns streaming, receiver heartbeat, notification,
  Wi-Fi/CPU locks and cleanup; leaving the control screen does not stop casting.
- Serve only random-token paths bound to the local interface used for the
  selected receiver. No filesystem URLs, browsing, credentials or analytics.
- Keep package ID and signing key unchanged. Do not change the original VLC app.
- No claim of matching XPlayer until actual device playback is verified.

## Verification

JVM tests cover actual HTTP range responses, CORS, HEAD, authorization paths,
malformed protobuf frames, and subtitle conversion. Android compilation and
codec fixture checks run in CI. A signed test build is reviewable after passing
compilation. Device acceptance: both receivers discoverable; video/audio smooth;
SRT captions visible; pause/resume and seek ±15 seconds work; prepared audio
file seeks; screen-off casting persists; disconnect releases server/locks.
