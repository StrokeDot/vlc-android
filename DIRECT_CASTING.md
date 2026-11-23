# VLC Plus direct casting test

Open a local video and tap its Cast button. Choose **Subtitle → Extract from movie** to select an embedded text track,
or choose an external SRT or WebVTT file. Then choose the TV or Chromecast. The controller offers
pause/resume, a seek slider, and 15-second jumps. Casting continues through a
foreground service when the control screen is closed or the phone screen is off.

The default path attempts original local files directly, including Matroska,
without rewriting an entire movie first. Seekable content URIs are served through
their descriptors without a bulk copy. Receiver compatibility is still determined
by actual playback; unsupported originals offer explicit conversion.
External subtitles are delivered as Cast text tracks, not burned into the picture.
The existing native path is available through **Use VLC casting**.

If there is no sound, tap **Convert** during playback. The existing picture keeps
playing while stereo audio is prepared; when ready the receiver switches at the
current position. Android-compatible audio is prepared alongside copied video.
For audio omitted by Android's reader (such as Dolby in some Matroska files),
VLC encodes AAC while Android copies the original video into the finished MP4.
These operations overlap, replacing the normal sequential audio-then-video wait.
The existing two-stage method remains recovery for unsupported streaming sources
or Android versions below API23. Preparation still needs time and temporary
storage; exact phone speed must be measured on the real movie.
Conversion can also be explicitly selected before choosing a receiver.

**Subtitle → Choose from movie folder** reuses VLC's browser in the video's
directory. **Choose using Files** opens the system picker, with a folder hint
when available. File selection/read errors are visible. Conversion and subtitle
controls remain usable after playback starts; volume controls address the receiver.

Limitations: local files only. Matroska embedded SubRip, ASS/SSA and WebVTT text tracks are read
directly from seekable MKV files, preserving track timestamps and durations
without rewriting the movie. The fallback Android media extractor handles
other compatible local video containers and timed-text formats, subject to
platform support. PGS and VobSub bitmap subtitles are not supported. If your Android
device does not expose the track, choose an external SRT or WebVTT subtitle.
No subtitle is burned into the video or uploaded to the internet. Standard mono/stereo/5.1/7.1 PCM layouts
are accepted for downmixing. This build must be tested on real receivers before
claiming that it matches XPlayer or fixes the original VLC slowdown.

## Validation

`bash buildsystem/test-direct-cast.sh` exercises protocol framing, receiver
termination policy, actual HTTP range/HEAD/CORS/authorization behavior,
subtitle conversion and the surround downmix without Android.
`bash buildsystem/test-cast-jvm.sh` (needs kotlinc, ffmpeg, ffprobe, mkvmerge)
builds ffmpeg and mkvmerge test movies and checks subtitle extraction plus a
full streaming session over HTTP decoded by ffmpeg. `CastHlsDeviceTest` repeats
the streaming checks with Android's extractors, codecs and libVLC.
`buildsystem/tests/CastReconnectCheck.kt` and `CastCommandCheck.kt` drive
`CastConnection` against a local TLS fake receiver (reconnection, rejected
commands, cancelled replacement loads and Stop delivery).

The opt-in `-PcastCodecTests=true` module compiles the production preparation,
input and controller sources into an isolated Android test app. CI checks direct
file/content-URI access, audio/video sample preservation, a Dolby-in-MKV fallback,
and active subtitle/conversion controls. Android 16 renders the actual controller
at the phone's display dimensions and saves a screenshot for visual inspection.
Fixtures are synthetic two-second test patterns and sine-wave clips.

Device acceptance remains: discover both receivers; show captions on the TV;
play smoothly; seek ±15 seconds; repeat after audio preparation; continue with
the phone screen off; stop from phone or receiver and verify disconnection.

## Streaming conversion (Convert in seconds)

**Convert** first tries a streaming conversion for Matroska (MKV/WebM) movies with
H.264 or HEVC video, the usual "no sound / won't play" case:

- The receiver gets an HLS stream (CMAF/fMP4 audio and video renditions) from
  the phone's local server instead of a finished MP4.
- Video segments (~6 s, cut at keyframes from the Matroska Cues index) are
  written **on request** from the original file. Samples are copied
  byte-for-byte, so no picture quality is lost and nothing is re-encoded.
- Audio that is already AAC-LC mono/stereo is copied too. Otherwise it is
  converted to stereo AAC ahead of the playhead: by Android's decoder when the
  phone has one (AC-3/E-AC-3 on most Dolby-licensed phones, DTS where present,
  MP3, FLAC, multichannel AAC), and a seek restarts it at the new position. For
  formats the phone cannot decode (e.g. TrueHD), VLC converts the audio, which
  only runs forward: seeking far ahead waits until VLC reaches that point.
- Playback starts once the first segments exist: under a second on a 1.3 GB,
  10-minute 1080p E-AC-3 test file (JVM measurement; phones are slower, and the
  VLC path adds its own start-up). It no longer depends on the movie's length.
- If the TV rejects or fails to play the stream, VLC Plus switches to the full
  conversion automatically. Tap **Convert** again while streaming to ask for
  the full conversion directly. Other containers (MP4, AVI, TS…) still use the
  full conversion.

## Embedded subtitles

Matroska text subtitles are read by a shared reader (`CastMkvReader`) that also
powers streaming conversion:

- zlib and header-stripping track compression (mkvmerge's defaults for many
  files; previously shown on the TV as binary garbage) and Xiph/EBML/fixed lacing;
- the Cues index when it lists the subtitle track (mkvmerge does), so only the
  subtitle blocks are read instead of walking the whole movie;
- unknown-size clusters, and track names and forced flags in the track list.

Cue text is made WebVTT-safe for embedded tracks and external SRT files alike:
`&`, `<` and `>` are escaped (`I <3 you` used to vanish), `-->` inside text can
no longer start a timing line, blank lines no longer cut a cue short, ASS
drawing commands and override tags (`{\p1}m 0 0 l…`, `{\an8}`) are removed,
and `<font>` tags keep their text. `<i>`, `<b>` and `<u>` are kept. Extracted
tracks are cached for the session.

## Subtitle appearance

Open **Subtitle → Appearance & preview** to choose font family, size, text color,
outline/shadow, dark background, bold and italic. The preview changes immediately;
**Apply** saves the settings and updates the TV without reloading the movie.
Newly selected subtitle files show the same preview before they are loaded.
Cancel leaves the previous subtitle/settings in place. Actual font rendering can
vary with the receiver's installed fonts.

## One controller for every Cast button

The library toolbar Cast button now opens the same controller as the player.
Previously, with exactly one receiver on the network, it silently started the
original LibVLC casting; videos then opened the old remote screen and the new
controller showed every button grayed out while the TV kept playing.

- Library Cast with a video loaded: opens the controller for that video.
- Library Cast with nothing playing: choose the TV; the next video you open
  casts to it automatically.
- If the original VLC casting is already running, the controller shows it and
  its play/pause, seek, ±15 s, volume and Stop work through VLC's renderer.
  Choosing a device switches to direct casting at the same position.
- **Use VLC casting** remains the explicit way to open the old renderer list.

Session robustness:

- A play/pause/seek the receiver rejects (common while buffering) is reported
  and resynchronised instead of ending the cast.
- A replacement LOAD (new subtitles, converted audio) that the TV cancels,
  rejects or never starts leaves the current playback running.
- While the control channel reconnects the controls stay live; the latest
  play/pause/seek and volume are replayed when the session returns. Recovery
  now allows six attempts (about 30 s) for Wi-Fi power-save gaps.
- Stop, failures and service shutdown send STOP and close the receiver app
  from a background thread. The previous main-thread STOP was rejected by
  Android and never reached the TV.
- Errors are shown once; reopening the controller no longer replays an old
  error dialog.

## Performance

Readable MediaStore paths bypass the full source-file staging copy. On Android 9
and later, audio that needs VLC decoding can pass through timed stereo PCM to
Android's AAC encoder in batches. The encoder uses AAC-LC at 192 kbps and 48 kHz.
The PCM file is temporary and the app falls back to the established VLC AAC
path if there is insufficient space or the Android encoder is unavailable.
Original video samples, frame timing and full-file seeking are preserved.

The local HTTP server reads request headers through a buffer (previously one
system call per byte), enables TCP_NODELAY with a 1 MiB send buffer, sends
content-URI movies with zero-copy `transferTo` like regular files, and allows
16 concurrent range requests so quick seeks are not rejected. 5.1/7.1 audio is
downmixed with bulk fixed-point math (10 s of 5.1 in tens of milliseconds on a
desktop JVM). Controller updates only touch views whose content changed, and
progress notifications are limited to about one per second.

The app caches the most recent eligible completed conversion, up to 8 GiB, and
reuses it when the source file is unchanged. The prepared file moves into the
cache after casting stops. Clearing the app cache removes it.


## Combined pre-release validation

This branch combines receiver session/autoplay recovery with embedded subtitle
selection. It also adds a synthetic Matroska video containing an actual embedded
English SubRip track, exercised in the Android instrumentation suite.
The signed .10 release does NOT yet include these features. Real-device TV
casting, subtitle appearance, and long 4K audio conversion still require
verification before releasing a signed update.
