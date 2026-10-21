# hadoku-mobile

One Android app for every hadoku.me site. A Capacitor 8 WebView shell that opens
`https://hadoku.me/apps/` (the launcher page in hadoku_site, `src/pages/apps.astro`) and
navigates the live site from there; native code only where the web cannot go. Design and
the reasoning behind every choice: `../hadoku_site/docs/planning/mobile-app-2026-10-20.md`
(hadoku_site#470). `hadoku-task-mobile` is the predecessor and stays as it is.

## Layout

| Path                                                 | Purpose                                                        |
| ---------------------------------------------------- | -------------------------------------------------------------- |
| `capacitor.config.json`                              | `server.url`, UA suffix, System Bars insets. The whole product. |
| `android/`                                           | Capacitor-generated Android project (committed, like task-mobile) |
| `android/app/src/main/java/me/hadoku/app/MainActivity.java` | The shell activity. Logs the two spike facts; nothing else yet |
| `www/`                                               | One placeholder page. Never shown: `server.url` replaces it     |

## Commands

```sh
pnpm install
pnpm sync                      # cap sync android — copies capacitor.config.json into the APK
pnpm android:build             # debug APK → android/app/build/outputs/apk/debug/app-debug.apk
pnpm android:release           # signed release (needs the keystore env, see task-mobile's build-apk.yml)
```

Building needs JDK 21 and an Android SDK with `platforms;android-36` + `build-tools;36.0.0`.
On hokon the SDK is at `~/Android/Sdk` (installed 2026-10-20 with the command-line tools);
`android/local.properties` points at it and is gitignored. An emulator AVD named `spike`
(API 36, google_apis, x86_64) exists there for headless checks:
`emulator -avd spike -no-window -no-audio -gpu swiftshader_indirect`.

## How the shell works, and what it must not do

- **The page is the app.** `server.url` makes the WebView first-party on hadoku.me, so `/auth`
  sets `hadoku_session` (`Domain=.hadoku.me; HttpOnly; Secure; SameSite=Strict`, 30 d sliding)
  exactly as in Chrome, and every site behind a tile reads it. There is no sign-in code here.
- **Nothing is stored by the app.** No key, no token. Native code that needs the session reads
  it from `android.webkit.CookieManager.getInstance().getCookie("https://hadoku.me")` at the
  moment of use and sends it as a `Cookie:` header. HttpOnly hides the cookie from page
  JavaScript, not from the native cookie store — which is also why a download must be
  enqueued natively, never from a page-fed plugin.
- **Never log a cookie value.** `MainActivity` logs presence (`hadoku_session=present|absent`)
  and whether `window.Capacitor` exists; that is the only logging, and it stays that way.
- **`server.url` is a works-but-unsupported Capacitor mode** (its docs say "not intended for
  production"). Keep `CapacitorHttp.enabled` and `CapacitorCookies.enabled` at their `false`
  defaults, never set `server.hostname` to the remote domain, keep `allowNavigation` empty.
  The site does not depend on Capacitor, so if a future major breaks this mode the port to a
  bare WebView is this one activity.
- **All browser traffic goes through `hadoku.me/<prefix>/*`**, never `*.hadoku.me` direct
  subdomains (platform rule; see `../hadoku_site/AGENTS.md`).

## Does NOT

- Does NOT bundle any site's code. Everything rendered comes from hadoku.me at run time.
- Does NOT hold secrets. The release keystore lives in the vault and reaches CI as a secret.
- Does NOT publish to npm.
