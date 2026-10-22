# Handoff — hadoku-mobile, 2026-10-21

You are picking up the one-app-for-the-ecosystem programme at the point where the shell and
its first native feature, the offline Library, are built, tested against production, and
released as v0.1.1. Everything below was verified on 2026-10-21/22 by the session that built
it; nothing is a guess.

## Read first, in this order

1. `AGENTS.md` in this repo — what the shell is, what it must never do, the CI and secrets
   contract. Short.
2. `../hadoku_site/docs/planning/mobile-app-2026-10-20.md` — the design, the alternatives that
   were rejected and why, the measured facts about watchparty's media, and the phases. It is
   the source for every number in this file.
3. `../hadoku_site/AGENTS.md` — platform rules (traffic through `hadoku.me/<prefix>/*` only,
   secrets model, fleet CI). Long; skim the Critical Rules.

The tracking issue is hadoku_site#470.

## Where things stand

- **Shell: proven and shipped.** Capacitor 8.5.3, `server.url` → `https://hadoku.me/apps/`,
  the icon-grid launcher that lives in hadoku_site (`src/pages/apps.astro`). On an API 36
  emulator the native layer saw the session cookie after the site's own sign-in, and
  `window.Capacitor.isNativePlatform()` was true inside the hadoku.me page. `MainActivity`
  logs exactly those two facts (presence only); that is still its only logging.
- **Repo: enrolled.** CI builds a debug APK on every push and PR and a signed release on a
  `v*` tag, on fleet runners, reporting as `mobile:build-apk`. All four repo secrets exist and
  come from the vault. Branch protection requires `build`. Two green runs so far; the second
  ran on a claw.
- **Player spike: GO (2026-10-21).** The real Frieren s01e01 files play as-is under
  Media3 1.11.1 with `MergingMediaSource`. Details under "1. Player spike" below.
- **Test harness: built (2026-10-21).** JVM unit, instrumented (synthetic fixture + real
  episode), and Playwright `_android` e2e over the app's own WebView. See `AGENTS.md` "Tests".
  CI runs typecheck + unit tests and compiles the instrumented tests; nothing runs on an
  emulator in CI yet (needs a KVM runner).
- **Shell fixes the e2e suite found:** Back on any site left the app (Capacitor core has no
  Back handling; it lives in the optional App plugin), and the session cookie was never
  flushed on pause. Both fixed natively in `MainActivity`. The flush is covered by
  `e2e/signed-in.spec.ts`, which passed 3/3 against production on 2026-10-21 with the
  dedicated `mobile-e2e` friend identity.
- **Download spike: GO (2026-10-21).** Details under "2. Download spike" below.
- **Library v1: DONE (2026-10-22), released in v0.1.1.** Details under "3. Library v1" below.
- **v0.1.1 is the first release**: github.com/WolffM/hadoku-mobile/releases/tag/v0.1.1,
  `hadoku-v0.1.1.apk`, signed with the documented key (SHA-256 `7f88aedf…`), versionCode
  1001; smoke-tested on the emulator. The `v0.1.0` tag exists with no release: its build
  failed reading the key (see `AGENTS.md`, the PKCS12 note). Next: `pnpm release patch|minor`.

## The work, in order

### 1. Player spike: DONE, GO

Result on the real files (`pnpm test:player-real`, API 36 emulator, swiftshader):
- Tracks are exactly 1 video, en + ja audio, and the 8 side-loaded VTTs. Each audio file's
  QuickTime chapter track (`hdlr text`, `tref chap`, from the source MKV) is ignored by
  Media3; it does NOT surface as a phantom subtitle.
- Both audio languages switch live, across the 44.1 kHz (eng) / 48 kHz (jpn) difference.
- All 8 subtitle languages render; the stray `</b>` ASS debris is stripped; VTT
  `line:/position:/align:` placement is honoured (screenshots in the test output dir).
- Seek to 60% resumes there. 180 s of 1080p played 179,998 ms of media in 180,000 ms of
  wall clock, 4,330 frames rendered, 0 dropped: no A/V drift.
- Not yet seen on the Pixel 8a (hardware decoder) or by a human ear/eye in motion.

What was asked:

Prove Media3 ExoPlayer plays watchparty's on-disk layout from local files:

- `adb push` one episode's three parts from hokon
  (`~/repos/hadoku-watchparty/media/output/fmp4/anime/Frieren/frieren-s01e01/`):
  `output_frag.mp4` (video only, H.264 fMP4 written for MSE: `empty_moov+default_base_moof`,
  2 s fragments), `audio/audio_eng_1.m4a` and `audio_jpn_2.m4a` (AAC fMP4, one file per
  language), `subs/sub_eng.vtt`.
- A throwaway Activity: `MergingMediaSource(ProgressiveMediaSource(video),
  ProgressiveMediaSource(audio_eng), ProgressiveMediaSource(audio_jpn))` plus a
  `MediaItem.SubtitleConfiguration` for the VTT, `DefaultTrackSelector`.
- Go/no-go: both audio tracks selectable, subtitles render, seek works, no A/V drift over a
  few minutes. If `FragmentedMp4Extractor` chokes on the MSE-flavoured file, that is the
  single most important thing to report back, because the whole offline design assumes the
  bytes play as-is.
- Media3 is at 1.11.x. No maintained Capacitor plugin does audio-track selection, so this is
  Kotlin, not a plugin. Do not spend time evaluating `@capgo/capacitor-video-player`; it is on
  the deprecated ExoPlayer 2.19 line and has no audio-track API.

### 2. Download spike: DONE, GO

Result (`pnpm test:download-resume`, API 36 emulator, signed in as `mobile-e2e`):
- `output_frag.mp4` (485,383,901 bytes), two airplane-mode cuts at ~1/3 and ~2/3, cold edge
  cache: complete in 50 s, sha256 identical to the origin. Warm cache: 40 s, identical.
- `audio/audio_jpn_2.m4a` (38,420,897 bytes), two cuts: identical. One resume landed on a
  socket opened while the network was still coming back; DownloadManager sat on it ~4 min,
  then retried with a fresh Range and finished (origin log shows the retry). Slow, not wrong.
- The hazard did not happen. Cloudflare caches the `.mp4` (`cf-cache-status=HIT`, filled by
  the download's own first Range-less GET) and still answered every resume with `206`; the
  `.m4a` is not cached at all (`DYNAMIC`), so its resumes go to the origin. No
  `ERROR_CANNOT_RESUME`. The edge-router Range bypass is not needed.
- The media id in every URL is the catalog id, the episode's path under the media root
  (`output/fmp4/anime/Frieren/frieren-s01e01`), not its folder name.
- `index.json`'s `content_token` is the sha256 of `output_frag.mp4`.
- `adb emu network speed` does not slow this AVD, so a cut lands mid-file only if the file
  outlasts airplane mode's latency; a small file can finish first and the test says so.

What was asked:

Prove Android's `DownloadManager` can fetch and RESUME through the edge:

- Enqueue `https://hadoku.me/watchparty/media/<id>/output_frag.mp4` with two request headers:
  `Cookie` (the session, read natively — see "Facts") and `X-Buffer-Ahead-Ms: 99999`, which
  is the value watchparty's own background pre-buffer sends so a live party always outranks
  you at the origin.
- Interrupt it (airplane mode) twice, once with a cold edge cache, and confirm it resumes with
  a `206`. The origin is `express.static`, which sends ETag and honours Range, the two things
  DownloadManager needs to resume.
- Known hazard, decide with the result: Cloudflare caches `.mp4`/`.m4a` by extension on the
  Worker's subrequest, and a cold-cache Range request can return a full `200`, which makes
  DownloadManager fail that file with `ERROR_CANNOT_RESUME`. It only applies to files under
  Cloudflare's 512 MB cache cap (this episode is 485 MB). The platform side can bypass the cache
  for Range requests in edge-router's `proxyRequest` if the spike shows it matters; ask the
  operator rather than working around it in the app.
- Do NOT route this through filetransfer. It runs at Gokapi encryption level 1, which ignores
  Range entirely (measured). The design is direct from `/watchparty/media/*`.

### 3. Library v1: DONE

What shipped (see `AGENTS.md` "The Library" for how it is built):
- A two-tab bar under the WebView (Hadoku, Library). A launch with no network opens the
  Library. Back from the Library's first screen returns to the site.
- Browse the catalog from `output/fmp4`; pick audio languages and subtitles (all by default);
  the size and a free-space check (256 MB kept spare) before anything is enqueued.
- Progress, "waiting for network", per-file failures with Retry, delete with confirm, and a
  storage line (Library size, free on the phone).
- Play from the Library, full screen, resuming where the viewer stopped (the last 30 s count
  as finished).
- Acceptance, `pnpm test:library-live` on the API 36 emulator against production: browse to
  Frieren s01e01 through the UI, download 11 files / 562 MB in 39 s, video sha256 equals
  `content_token`, airplane mode, cold launch opens the Library, the episode plays.

Not in v1, in order of value:
- **Background audio and lock-screen controls** (`MediaSessionService`). The player is a
  plain activity: leaving it pauses.
- A "Wi-Fi only" setting: downloads are allowed on metered networks.
- Not yet seen on the Pixel 8a.
- Records are `episode.json` per episode, not Room as the design said: the record lives
  with its files, which is all v1 needs. Revisit if the Library grows queries.

What was asked:

Native Kotlin screens behind a bottom bar beside the WebView:

- Browse via `GET /watchparty/api/catalog?prefix=` and
  `GET /watchparty/api/media/<id>/tracks` (both friend tier; the cookie is the auth). Tracks
  returns `/media/<id>/audio/<file>` and `/media/<id>/subs/<file>` URLs.
- Enqueue one DownloadManager request per file (video, chosen audio, chosen subs) into the
  app-private external files dir. Sizes come from `index.json` (last fragment `end_byte` + 1),
  so the "won't fit" check is `StatFs` against a known total, no HEAD.
- A Room row per episode (`mediaId, title, token, audioLang, subs, bytesTotal, status,
  positionMs, addedAt`). `content_token` from `index.json` is the re-ingest guard: mismatch on
  resume means restart, never splice.
- Play with the Media3 source from spike 1, under a `MediaSessionService` for lock-screen
  controls and background audio. Save position on pause, stop and destroy.
- Offline launch: no connectivity → open the Library, not the WebView's blank page.
- Acceptance: usable on a flight.

## Facts you will need

- **The cookie.** `hadoku_session` is `Domain=.hadoku.me; HttpOnly; Secure; SameSite=Strict`,
  30 days sliding. Page JavaScript cannot see it; native code can:
  `CookieManager.getInstance().getCookie("https://hadoku.me")`. Read it at the moment of use,
  attach it as a `Cookie:` header, never store it, never log its value. This is why downloads
  are enqueued natively and never through a page-fed plugin.
- **Capacitor remote mode** is a works-but-unsupported configuration. Keep `CapacitorHttp`
  and `CapacitorCookies` disabled, never set `server.hostname` to the remote domain, keep
  `allowNavigation` empty. The bridge is injected into GET-loaded documents only.
- **Signing in on an emulator** is `pnpm emulator:sign-in`: the site's own form at
  `/auth?return=/apps/`, driven by Playwright, with the friend-tier `mobile-e2e` key that
  dev-vault fetches with this repo's own identity. Each sign-in leaves a 30-day session for
  that identity in production.
- **Insets.** `plugins.SystemBars.insetsHandling: native` keeps the page below the status bar
  with no site change. Leave it.
- **UA.** `appendUserAgent: HadokuApp/<version>` is how request logs tell app sessions apart.
  It reads `package.json`'s version, so `pnpm release` bumps it with everything else.

## Tooling on hokon

- **Which tree is production.** hadoku_site moved its deployed services to
  `/home/hadoku/srv/<repo>` (2026-10); `~/repos/<repo>` is then a plain checkout that does
  not advance with deploys. Watchparty moved late on 2026-10-21: its live server runs from
  `/home/hadoku/srv/hadoku-watchparty/apps/server` with `MEDIA_ROOT` under that tree, and
  `~/repos/hadoku-watchparty/media` no longer holds the library. Check the live process's
  cwd and `MEDIA_ROOT` (`/proc/<pid>/`) before trusting either. The device-test scripts
  prefer `/srv` when it exists; `WATCHPARTY_MEDIA_ROOT` overrides.

- Android SDK at `~/Android/Sdk` (cmdline-tools, platform 36, build-tools 36 and 35,
  platform-tools, emulator). `android/local.properties` points at it; gitignored.
- AVD `spike`: API 36, google_apis, x86_64, Pixel 8 profile, WebView 133. Headless:
  `emulator -avd spike -no-window -no-audio -gpu swiftshader_indirect -memory 2048`, then
  `adb wait-for-device` and poll `sys.boot_completed`. Boot takes about a minute.
- `pnpm sync` then `pnpm android:build` for a debug APK (about two minutes warm);
  `adb install -r` it; `adb logcat -s HadokuShell:I` shows the two probe lines on every page
  load; `adb exec-out screencap -p > file.png` for screenshots.
- The target device is a Pixel 8a, not usually attached to this host. Releases are sideloaded
  from GitHub Releases.
- JDK 21, Node 24 are on the box. Gradle 8.14.3 comes from the wrapper.

## Rules that will bite

- Commit as `WolffM <8714327+WolffM@users.noreply.github.com>` (already the repo's local git
  config). A gmail author is refused by the account's email-privacy block.
- No AI attribution lines in commits, ever (operator rule).
- Pushing to `main` is the normal path; the required `build` check runs on fleet runners.
  Never write `runs-on: ubuntu-latest`; `vars.CI_RUNNER` is owned by mgmt-api.
- Nothing secret in the repo, no `.env`, no keystore file. The four secrets are documented in
  `AGENTS.md`; changing the signing key is an app-identity change on every installed phone,
  so it is never casual.
- The watchparty website's UI does not change for this feature (operator decision in #470). A
  native-only affordance behind `window.Capacitor?.isNativePlatform()` is allowed later, not
  now.
- Filetransfer and hadoku-task-mobile stay as they are.

## Decisions parked with the operator

- App Links: `public/.well-known/assetlinks.json` on hadoku.me plus an intent filter here, so
  hadoku.me links open the app. Pages already serves `.well-known`.
- A KVM-capable runner label so `pnpm test:device` and `pnpm e2e` can run in CI. hokon has
  `/dev/kvm`; whether the claws do is unchecked.
- iOS: not planned. $99/year, 90-day TestFlight builds, and App Store rule 4.2 against
  repackaged websites.

## When you finish a piece

Update `../hadoku_site/docs/planning/mobile-app-2026-10-20.md` (its "Phases" section marks
what is done and what was learned) and this file's "Where things stand". The platform's
memory for the next session is that doc, not chat.
