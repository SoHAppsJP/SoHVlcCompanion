# SoH VLC Companion

SoH VLC Companion is an Android playback companion application that uses libVLC for media playback and communicates with host applications through the SoHPlayerKit `companion-contract` module.

The project is currently in the initial bring-up stage. The immediate goal is to reproduce the existing VLC playback behavior from CloudVideoPlayer in a separate companion application without moving provider authentication or cloud-access logic into the companion.

## Architecture

```text
CloudVideoPlayer
    |
    | CompanionPlaybackContract / Intent
    | localhost HTTP media URL
    v
SoH VLC Companion
    |
    v
libVLC
```

CloudVideoPlayer remains responsible for provider access, authentication, resume-position persistence, playback history, and `ExternalPlaybackHttpServer`. SoH VLC Companion receives only the playback information required by the companion contract.

This separation also keeps the libVLC binary dependency out of CloudVideoPlayer. The currently used `org.videolan.android:libvlc-all:3.7.0` binary has been observed to contain GPL-enabled third-party components, so it is intentionally isolated in this separately distributed companion application.

## Current scope

The current implementation:

- Receives `CompanionPlaybackContract.ACTION_PLAY`.
- Reads the media URI, MIME type, display name, resume position, and optional video metadata from the contract.
- Plays the localhost HTTP media URI with libVLC 3.7.0.
- Preserves the current VLC initial-resume behavior, including both `MediaPlayer.time` and `MediaPlayer.position` seeking.
- Preserves retry/fallback handling around the initial resume seek.
- Returns the final playback position and optional duration through `CompanionPlaybackResult` when the activity finishes.

The following parts of the existing CloudVideoPlayer VLC implementation have not yet been fully migrated:

- SoHPlayerKit player UI integration
- Manual seek controls
- Playback speed and display settings
- Color controls
- DVD ISO/navigation behavior
- Full VLC metadata handling
- Remaining VLC-specific controller behavior

These will be moved incrementally so that playback behavior can be verified at each step.

## Development layout

Keep this repository next to a SoHPlayerKit checkout:

```text
AndroidStudioProjects/
├─ SoHPlayerKit/
└─ SoHVlcCompanion/
```

`settings.gradle.kts` uses:

```kotlin
includeBuild("../SoHPlayerKit")
```

so the current local SoHPlayerKit sources are used directly. The SoHPlayerKit checkout must contain the `companion-contract` module.

## Build

Requirements:

- Android Studio with Android SDK installed
- JDK 17 or later
- A sibling `SoHPlayerKit` checkout as described above

Create `local.properties` locally if Android Studio has not generated it automatically:

```properties
sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
```

`local.properties` is machine-specific and must not be committed.

On Windows, a debug build can be run with:

```powershell
.\gradlew.bat assembleDebug
```

## Related projects

- SoHPlayerKit: shared player core, UI, and companion playback contract
- CloudVideoPlayer: host application that owns cloud providers and exposes media to the companion through localhost HTTP

## License

SoH VLC Companion is licensed under the GNU General Public License, version 3 or later (`GPL-3.0-or-later`). See [LICENSE](LICENSE).

Copyright (C) 2026 SoH Apps

### libVLC and bundled third-party components

libVLC itself is generally distributed by VideoLAN under the GNU Lesser General Public License version 2.1 or later (`LGPL-2.1-or-later`). However, this application currently uses:

```text
org.videolan.android:libvlc-all:3.7.0
```

An audit of the actual Android binary used by CloudVideoPlayer found that the bundled `libvlc.so` contains FFmpeg 4.4.5 built with options including:

```text
--enable-gpl
--enable-postproc
--enable-static
```

The same binary also contains VLC configuration indicating that `live555` is enabled. Because this distributed binary contains or may contain GPL-covered components in addition to LGPL-covered libVLC code, it must not be treated as an LGPL-only libVLC build merely from the Maven/POM license metadata.

For that reason, SoH VLC Companion is distributed under `GPL-3.0-or-later`, and the libVLC dependency is kept out of CloudVideoPlayer.

The exact licenses, copyright notices, corresponding-source requirements, and other redistribution obligations of libVLC and all software bundled within its native binaries remain applicable independently of this project's GPL license. A complete third-party notice/source package will be maintained before public binary distribution.

This repository's GPL license does not replace or alter the licenses of libVLC, FFmpeg, LIVE555, or any other third-party component.
