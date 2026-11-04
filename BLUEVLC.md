# BlueVLC — development fork

Personal experimental fork of [VLC for Android](https://code.videolan.org/videolan/vlc-android), maintained independently by StrokeDot. Not an official VideoLAN release or endorsed by VideoLAN. The original VLC app is unchanged.

## First milestone: Android app identity

- Use independent Android `applicationId`: `com.strokedot.bluevlc` (debug builds also get the usual `.debug` suffix).
- Override the visible app label with `BlueVLC`.
- Adapt the existing GPL-licensed VLC cone launcher vectors to a blue foreground and background; Android 8+ adaptive launcher icon uses those vectors and a monochrome cone. Older fallback PNG mipmap densities still need conversion to blue.
- Give APK outputs a `BlueVLC-` filename prefix.
- Keep upstream Android/Kotlin namespaces intact, because the package ID is distinct from the code namespaces.

## Casting development plan — not yet implemented

1. Establish a reproducible Android build and signing workflow with a verified arm64 APK. The GitHub mirror omits parts of VLC's native build tree and a Gradle wrapper; do not mistake the static checks for an APK build.
2. Reproduce and trace cast subtitle selection behavior in VLC Android and LibVLC; evaluate embedded and external SRT tracks, WebVTT conversion and receiver capabilities.
3. Detect incompatible remote audio codecs and offer *audio-only* conversion to a compatible format where feasible, without needlessly re-encoding video. Confirm acceptable latency and licensing.
4. Validate direct Cast discovery, playback, controls, subtitles and fallback transcoding with a Chromecast and a Google TV.

## Credits, licensing and compatibility

VLC for Android is GPLv2-or-later (see [COPYING](COPYING) and the upstream [README](README.md)). Changed artwork derives from the upstream GPL-licensed source. Retain authorship and license notices when redistributing source or binaries. No proprietary XPlayer code is included.

This project is experimental. It does **not** yet provide an installable APK or working subtitle/audio casting changes. Keep the original VLC installed for normal playback and test new builds separately. Third-party extension permissions, remote-access integration, older Android launcher assets, and native build dependencies must be checked before a release.
