# AudioProPlayer — notes for Claude Code

Android app (Kotlin, Jetpack Compose, single module `app`) that replaces the Audio Pro app on the local Wi‑Fi. Tested on an ADDON C10 MkII, firmware 1.56.

## Layout
- `net/SpeakerClient.kt`: LinkPlay HTTP API (`/httpapi.asp?command=`), port probing 4443→443→8443→80, JSON parsing, hex title decoding.
- `net/LinkPlayTls.kt`: mutual TLS using `res/raw/linkplay_client.pem`; trusts the speaker's self-signed cert.
- `net/Upnp.kt`: SSDP search plus RenderingControl/AVTransport SOAP (volume and play state on MkII firmware).
- `net/Discovery.kt`: mDNS `_linkplay._tcp` and SSDP, merged into one Flow.
- `SpeakerViewModel.kt`: state, the polling/reconnect/relocate loop, and actions with optimistic UI.
- `net/Stations.kt`: built-in stations and the Station model. `net/NtsApi.kt`: NTS artwork and live show names.
- `ui/`: Setup, Player, Radio and Diagnostics screens.

## Speaker facts (C10 MkII, firmware 1.56)
- A physical preset button needs a queue with the preset's exact name and `PressType` = button number. See docs/PROTOCOL.md.
- Test firmware assumptions on the real speaker: a mock built from the app's assumptions missed `getPlayerStatus` being unsupported.

## Conventions
- No networking libraries: HttpURLConnection plus coroutines on Dispatchers.IO.
- Every network failure goes through `log()`, so it appears in Diagnostics → Copy report.
- When the user pastes a diagnostics report, use the raw `getStatusEx` / `getPlayerStatus` JSON in it to adjust the parsing.

## Build
`./gradlew assembleDebug` (needs the Android SDK; set `JAVA_HOME` to Android Studio's `Contents/jbr/Contents/Home` and `ANDROID_HOME` to the SDK). Install it with `./gradlew installDebug`.
