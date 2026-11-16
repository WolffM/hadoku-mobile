# Contributing

This is a Capacitor shell around a live site, not a bundled app: most of
what you'd change lives in the website itself, and this repo is mostly
native code for the one thing the web can't do — the offline Library.
`AGENTS.md` is the fuller reference; this is a map into it.

## Running it locally

```sh
pnpm install
pnpm sync              # cap sync android: copies capacitor.config.ts into the APK
pnpm android:build     # debug APK -> android/app/build/outputs/apk/debug/app-debug.apk
```

Building needs JDK 21 and an Android SDK with `platforms;android-36` and
`build-tools;36.0.0`. Gradle tasks should always be qualified with `:app:` —
an unqualified task also runs in the generated
`capacitor-cordova-android-plugins` module, which has no tests of its own
and fails a duplicate-class check on two old Kotlin stdlibs.

## Architecture map

```
capacitor.config.ts                    server.url, the UA suffix, system-bar insets — the whole shell config
android/app/src/main/java/me/hadoku/app/
  MainActivity.java                    the shell: logs the two startup probes, flushes cookies, owns Back,
                                        keeps the launcher as home, opens the Library offline or on its deep link
  Theme.kt                             the Compose theme
  Hadoku.kt                            reads the session cookie at the moment it's needed; online()
  library/                             Library v1: catalog + episode parsing, episode.json records,
                                        DownloadManager orchestration, the Compose screens
  download/EpisodeDownloads.kt         enqueues one file from watchparty's media route via DownloadManager
  player/PlaybackService.kt            the one player: a Media3 MediaSessionService — background audio,
                                        lock-screen controls, saved position
  player/PlayerActivity.kt             the screen that connects to PlaybackService
android/app/src/test/                  JVM unit tests
android/app/src/androidTest/           instrumented tests; assets/episodes/fixture-s01e01 is a synthetic episode
e2e/                                   Playwright, driving the real WebView inside the installed debug APK
scripts/                               emulator.sh, release.mjs (dispatches a release), release-version.mjs (computes one)
```

Nothing a visitor sees is bundled: `www/` is one placeholder page that is
never actually shown, because `server.url` replaces it with the live site.

## Tests and gates

| Layer | Command | Runs where | What it proves |
| --- | --- | --- | --- |
| Typecheck | `pnpm typecheck` | CI | config + e2e suite compile |
| Unit (JVM) | `pnpm test:unit` | CI | episode layout and JSON parsing, the Library's records |
| Instrumented | `pnpm test:device` | emulator | playback, downloads, resume, background audio, system media keys |
| Shell e2e | `pnpm e2e` | emulator | launcher, bridge, UA, locked tiles, Back behaviour, the Home shortcut |
| Signed-in e2e | `pnpm e2e:signed-in` | emulator, by hand | the session cookie reaches native code but not page JS |

CI (`build-apk.yml`) compiles the instrumented tests so they can't rot, but
doesn't run them on an emulator — that needs a runner with KVM, which the
fleet has not been asked for. Signed-in tests leave a real 30-day session in
production, so they never run unattended: without the right key they fail
loudly rather than skip.

## How it deploys

`build-apk.yml` is both the gate and the ship path: every push and PR builds
a debug APK. A release is a separate, manual act — `pnpm release
patch|minor|major|X.Y.Z` dispatches the same workflow with a `release` input;
nothing in the repo is ever edited for it. The version is computed at build
time from the newest `vX.Y.Z` tag on the remote and stepped (never
committed), and the workflow creates the tag and the GitHub Release itself
on the commit it built.

## Trivia

The versioning script refuses to compute a version at all when the minor or
patch number would reach 1000 — that's where its `major*1e6 + minor*1e3 +
patch` versionCode encoding would start colliding with the next major
(`scripts/release-version.mjs:35`, added in `f2c0d88`).
