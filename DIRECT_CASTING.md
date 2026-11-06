# VLC Plus direct casting test

Open a local video and tap its Cast button. Choose an SRT or WebVTT subtitle
file if desired, then choose the TV or Chromecast. The controller offers
pause/resume, a seek slider, and 15-second jumps. Casting continues through a
foreground service when the control screen is closed or the phone screen is off.

The new path serves compatible MP4/WebM files directly over the local network.
External subtitles are delivered as Cast text tracks, not burned into the picture.
The existing native path is available through **Use VLC casting**.

If there is no sound, disconnect, select **Prepare audio for TV**, and choose
the receiver again. Preparation copies the original video and produces AAC audio
in a finished MP4. This takes time and temporary free storage before playback;
the resulting file supports range requests and seeking. Matroska files are also
prepared into MP4 automatically. Audio conversion depends on available Android
decoders; unsupported video codecs or audio layouts need the native fallback.

Limits: local files only; external SRT/WebVTT only; embedded, ASS and bitmap
subtitles are not extracted by this path. Standard mono/stereo/5.1/7.1 PCM layouts
are accepted for downmixing. This build must be tested on real receivers before
claiming that it matches XPlayer or fixes the original VLC slowdown.

## Validation

`bash buildsystem/test-direct-cast.sh` exercises protocol framing, receiver
termination policy, actual HTTP range/HEAD/CORS/authorization behavior and
subtitle conversion without Android.

The opt-in `-PcastCodecTests=true` module compiles the exact production
`CastPreparation.kt` source into a tiny isolated Android test app. CI runs two
fixture tests on an API 31 emulator: forced AAC preparation preserves video
samples/timestamps, and MKV remuxing preserves compatible audio/video payloads.
Fixtures are synthetic two-second test pattern and sine-wave clips.

Device acceptance remains: discover both receivers; show captions on the TV;
play smoothly; seek ±15 seconds; repeat after audio preparation; continue with
the phone screen off; stop from phone or receiver and verify disconnection.
