# Cast preparation performance

The user confirms .7 stable casting, working playback/seek, captions, and audible
converted Dolby audio. The remaining concrete problem is roughly ten minutes of
conversion followed by a second video preparation stage. Preserve those working
features and the controller.

The .7 native fallback writes audio-only M4A then copies every original video
sample into a second MP4. Preferred approach: native AAC plus video passthrough
in one output, waiting for Stopped (input DEAD) instead of EndReached before
reading it. VideoLAN 3.0 src/input/input.c End() emits END_S before ES/output
cleanup; lib/media_player.c emits Stopped only for INPUT_EVENT_DEAD. This offers
a cleanup requirement, but the first native test still rejected all three candidates.
Further source analysis found regular MP4 Mux() requests two queued blocks while
Close() rewrites the file for faststart; fragmented MuxFrag() accepts one block
and CloseFrag() explicitly flushes the held entry and writes mfra seek indexes.
Use mp4frag for combined output, preserve an AAC registration buffer of 1000 ms
in media time, and verify random seeking on the two-minute fixture. Explicit
Cast media duration preserves the controller/receiver duration when the init
segment itself has no final duration.

Alternative: keep the validated two-stage path. Retain it as recovery if native
output validation fails. Avoid a new live streaming container/receiver, which
would expand scope and threaten seeking/captions. These are implementation calls
within the user's existing authorization to do the work without design questions.

Verify single-pass output against original decoded pixels and all frame timing
intervals for H264 with/without B frames and HEVC. At runtime compare frame count,
relative presentation span, dimensions, rotation and presence of stereo AAC;
fall back to .7 on mismatch. Validation must not copy or rewrite video samples.
Record phase timing to distinguish conversion, validation and fallback cost.

Do not tune an already stable HTTP path by guessing; exercise sustained concurrent
range/caption delivery and preserve current buffering unless measurements expose
a bottleneck. Publish .8 with the existing package/key only after review and tests.

The second experiment (mp4frag with 1000ms media buffering) failed: 31/72 AVC
frames and 30/72 HEVC frames survived, and composition timestamps were wrong
(confirmed by FFmpeg decoding, not only Android metadata). Native fragmented
MuxFrag consumes one queued packet per send and cannot drain the initial backlog
reliably. The next candidate uses the bundled avformat MP4 writer, whose Mux()
loops over queued packets with a one-packet minimum, writes PTS/DTS using 64-bit
stream rescaling, and closes with av_write_trailer. It produces a standard
indexed MP4 with moov at the end, avoiding a faststart relocation pass. The
source and tests retain the same preservation, seeking and fallback requirements.
