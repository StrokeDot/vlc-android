# Concurrent cast preparation

The user confirms .7 stable casting, working controls/seeking, captions and
converted audio. The concrete remaining problem is about ten minutes of serial
AAC conversion followed by movie preparation. Preserve the working UI and media.

The selected implementation overlaps the existing engines: VLC converts audio
only into a growing MPEG-TS file; Android MediaExtractor consumes that stream
through an unknown-length MediaDataSource and the existing Android MP4 muxer
copies every original video sample alongside the arriving AAC. TS preserves the
audio presentation timestamps, and the AAC origin is normalized as in the
existing audio-only preparation. Video bytes and presentation timestamps never
pass through VLC's video muxers. One progress phase follows muxed media time.

Wait for natural native Stopped, not just EndReached, before accepting encoder
completion. EOF is unavailable to the extractor until the producer finishes.
Cancellation aborts the producer and joins its final cleanup before deleting the
stream file. Verify producer success explicitly even if an extractor reports EOS.
Older Android/API<23 and failed live streaming retain the validated .7 audio-only
plus Android-remux recovery. Keep the package/key, controls, subtitles, receiver
volume and duration behavior.

Approaches considered: native combined MP4, native fragmented MP4 and the bundled
FFmpeg MP4 writer. Actual Android files showed lost frames, corrupted B-frame
timing or non-monotonic DTS in those video pipelines; the preservation tests
rejected them. Retaining Android's known video copy while overlapping audio is
the smallest robust architecture change. These are implementation decisions under
the user's established authorization to do the work without routine questions.

Tests must verify original compressed samples and all decoded frames/timing for
AVC, B frames and HEVC; rotation; cancellation; captions/control retention; real
random seeking; and producer/MP4-writing overlap on a 120s source. Record old/new
elapsed time, but distinguish emulator measurements from actual phone speed.
No speculative network tuning: preserve the existing stable HTTP implementation
and verify overlapping unaligned range and caption requests.
