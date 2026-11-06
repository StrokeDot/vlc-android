# VLC Plus direct casting test

Open a local video and tap its Cast button. Choose an SRT or WebVTT subtitle
file if desired, then choose the TV or Chromecast. The controller offers
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

Limits: local files only; external SRT/WebVTT only; embedded, ASS and bitmap
subtitles are not extracted by this path. Standard mono/stereo/5.1/7.1 PCM layouts
are accepted for downmixing. This build must be tested on real receivers before
claiming that it matches XPlayer or fixes the original VLC slowdown.

## Validation

`bash buildsystem/test-direct-cast.sh` exercises protocol framing, receiver
termination policy, actual HTTP range/HEAD/CORS/authorization behavior and
subtitle conversion without Android.

The opt-in `-PcastCodecTests=true` module compiles the production preparation,
input and controller sources into an isolated Android test app. CI checks direct
file/content-URI access, audio/video sample preservation, a Dolby-in-MKV fallback,
and active subtitle/conversion controls. Android 16 renders the actual controller
at the phone's display dimensions and saves a screenshot for visual inspection.
Fixtures are synthetic two-second test patterns and sine-wave clips.

Device acceptance remains: discover both receivers; show captions on the TV;
play smoothly; seek ±15 seconds; repeat after audio preparation; continue with
the phone screen off; stop from phone or receiver and verify disconnection.
