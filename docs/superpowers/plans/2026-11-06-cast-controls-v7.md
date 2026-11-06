# Cast controls and startup correction

The user confirmed .6 eventually plays smoothly after five minutes of whole-file
preparation, but the UI is poor, subtitles cannot be selected as expected, the
picker does not start beside the movie, and audio preparation becomes inaccessible
after playback starts. The supplied XPlayer screenshot is the layout reference.

## Decisions

- Preserve the working seekable preparation path as an explicit Convert action.
- Attempt original file streaming first, including Matroska; VideoLAN's own
  Chromecast code uses `video/x-matroska`. Receiver compatibility is still tested
  at playback, and failures offer explicit conversion instead of silently rewriting
  an entire movie. Seekable content URIs are served through descriptors directly.
- Allow audio preparation during an active cast; leave the current picture playing
  while preparing, then switch at the receiver's current position. Keep captions.
- Replace the rough form with compact dark cards, blue accents, subtitle/convert/
  stop/help actions, volume controls and a focused transport card. Honor system
  status/navigation insets. Device selection moves into a chooser.
- Reuse VLC's subtitle browser at the video's parent folder, accept its returned
  file URI, and offer the system picker as an alternative. Show format/read errors.

## Tasks and checks

1. Test-first HTTP Source abstraction: constructing and HEAD/range-serving a large
   virtual source must not copy/read the whole movie. Implement direct Android
   file/content descriptors and keep explicit preparation unchanged.
2. Service: explicit active conversion, replace served source while preserving
   position/subtitles, manage retired servers and complete cleanup. Receiver volume.
3. Independent native controller view plus activity bindings and movie-folder
   subtitle picker. Test callbacks and system-bar bounds on Android; capture actual
   emulator screenshots of the same view used in the app.
4. Core checks, codec fixtures, Android builds, independent review; sign/publish
   .7 with the same app identity. No receiver compatibility/performance claim
   beyond the user's confirmed smooth .6 prepared video.
