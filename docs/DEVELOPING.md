# Developing ReadArea

## Layout

```
core/          Pure Kotlin, shared by every platform: the format parsers (EPUB, FB2, MOBI, DOCX, ODT, RTF,
               Markdown, TXT, HTML), the text and offset model behind progress, search, notes and read aloud,
               opening books from files, library rules and the reading themes.
app/           The Android app: Jetpack Compose, Room, a custom page engine and page-curl view.
desktop/       The macOS, Windows and Linux app: Swing with FlatLaf, a Java2D page engine (hyphenation,
               justification, bidi, ruby, vertical CJK), page curl, PDFBox and SQLite.
build-logic/   Gradle plugin that turns the desktop app into native installers.
```

Both apps read books through the same parsers into the same block model and measure positions in the same text offsets, so a highlight or bookmark means the same thing everywhere.

## Build and run

Needs JDK 21. The Android app also needs the Android SDK with platform 37; without one, the build simply leaves the Android module out.

```bash
./gradlew :desktop:run                     # the desktop app, with a separate development library
./gradlew :desktop:packageDesktop          # installers for this system, in desktop/build/package/dist
./gradlew :app:assembleDebug               # the Android app
```

Installers are built with the JDK's jpackage on each system: dmg on macOS, msi on Windows (needs WiX 3), deb and rpm on Linux (need `fakeroot` and `rpm`). Each build trims a Java runtime to what the app uses, checks the packaged app with `ReadArea --self-test` through its own launcher, and records a class-data archive for faster start-up (on Linux this needs a display, e.g. `xvfb-run`).

## Tests

```bash
./gradlew :core:test                                     # parsers, text model, library rules
xvfb-run -a ./gradlew :desktop:test                      # engine, rendering, UI walkthroughs, security
./gradlew :app:testDebugUnitTest                         # Android
READAREA_BENCH=1 ./gradlew :desktop:test --tests '*PerfBench*' -i   # timings, on demand
```

The desktop UI tests drive the real app on a virtual display, in English and in Arabic, and save screenshots of every screen to `desktop/build/qa-screens` and `qa-screens-rtl`. The security tests attack the parsers with hostile files (decompression bombs, deep nesting, malformed headers) and fuzz every format with thousands of damaged books. Rendering tests write PNGs to `app/build/screens` and `desktop/build/screens`.

`READAREA_TRACE_STARTUP=1` makes the desktop app print its start-up timings.

## Release

Running the **Release** workflow (Actions → Release → Run workflow) or pushing a tag like `v1.2.0` builds the signed APK and the desktop installers for every system, and publishes them with checksums as a GitHub Release. The Android version code is `major * 10000 + minor * 100 + patch`, so every newer version installs as an update.

Repository secrets (Settings → Secrets and variables → Actions):

- Android, required: `KEYSTORE_BASE64` (the keystore, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Keep a backup of the keystore: Android only accepts updates signed with the same key.
- macOS, optional: with `MACOS_CERTIFICATE` (a Developer ID Application certificate as base64 .p12), `MACOS_CERTIFICATE_PASSWORD`, `MACOS_SIGNING_IDENTITY` (e.g. `Jane Doe (TEAMID)`), `APPLE_ID`, `APPLE_TEAM_ID` and `APPLE_APP_PASSWORD`, the Mac apps are signed and notarized, so they open without warnings.
