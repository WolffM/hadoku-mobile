# History

35 commits over 18 days (first `9446dd3` 2026-10-20, latest `4195e0e`
2026-11-07; `node scripts/fact-sheet.mjs` against this worktree). One author,
three short eras.

## The shell first

`9446dd3` (2026-10-20, 21:39) is the whole repo's start: a Capacitor 8 shell
over the live launcher, with "two spike probes" logged on every page load —
can native code see the session cookie, and does the page know it's running
inside the app. Three more commits that same evening add the CI build
(debug APK on every push) and its docs. That question — can a WebView shell
safely reuse the site's own cookie session — is what the first commit exists
to answer, and every later feature depends on the answer being yes.

## The busiest day, and a release that wasn't

The very next day, 2026-10-21, is the project's busiest by far: 14 of the
repo's 35 commits (`git log --format=%ad --date=short | grep -c
2026-10-21`). In one day: Back now walks WebView history before leaving the
app. Media3 plays a per-language audio spike. A Playwright harness drives
the real WebView inside an installed debug APK. Late that evening,
`0c0f49c` ships Library v1 in full — browse, download with a choice of
audio languages, resumable playback, delete — and that same commit is
where the `v0.1.0` tag lands.

It never shipped as a working build. Eight minutes after `0c0f49c`,
`9d0885e` records that the `v0.1.0` tag's signed build "opened the keystore
but failed reading the key with `HADOKU_MOBILE_KEY_PASSWORD`" — `keytool`
ignores `-keypass` for a PKCS12 file, so the key's password was always the
store's. The `v0.1.0` tag itself was created at 23:53:44. Eight minutes
forty seconds later, at 00:02:24, `v0.1.1` is tagged. `50c10dd` — the
commit that spells out why `v0.1.0` never built — follows four minutes
thirty-two seconds after that, at 00:06:56 (`git for-each-ref
--sort=creatordate --format='%(refname:short) %(creatordate:iso-strict)'
refs/tags`; `git log -1 --format=%ad --date=iso-strict 50c10dd`). So the
gap between a release tag that never built and the one that fixed it is 13
minutes 12 seconds, not a day — it only looks like "the next day" because
it crosses midnight. `50c10dd` is blunter about the outcome than the tag
alone: "v0.1.1 is the first release" — the first tag that is actually a
built, signed APK.

## Four days, five releases, and a borrowed icon

`v0.1.1` through `v0.1.5` ship across the following four days (2026-10-22
through 2026-10-25) — five releases, since `v0.1.2` and `v0.1.3` both land
on 2026-10-23 — each a `release: vX.Y.Z` commit that bumped
`package.json`'s version field directly. In between:

- background audio and lock-screen controls through a `MediaSessionService`
  (`98663fc`);
- the app icon is replaced with its predecessor's — "`icon:
  hadoku-task-mobile`'s fox (v7)," copied from that repo's actual shipped
  resources rather than its older, unused copy (`934e754`);
- the launcher page starts doubling as "home" for the site's own home link
  (`91fd12c`);
- the Library's entry point moves from a bottom tab bar to a link on
  watchparty's own lobby page, visible only inside the app (`e5b9bc2`);
- the cookie flush moves from "on pause" to every page load (`a46b2b0`).

## The quiet week, and the version rewrite

`ca74fa2` (v0.1.5, 2026-10-25) is followed by the project's longest gap: 7
days of nothing, to `f2c0d88` on 2026-11-01 (`node scripts/fact-sheet.mjs`'s
"longest quiet spell"). That commit, "Versions are computed at release
time, never committed," replaces the whole release flow. `pnpm release` now
dispatches the CI workflow directly instead of bumping `package.json`,
committing, and pushing a tag by hand. The workflow reads the newest
`vX.Y.Z` tag from the remote and steps it itself; `package.json` has
carried no version field since. The repo's last two commits, a day apart,
pin third-party CI actions by commit sha (`2294adc`, 2026-11-06) and add the
app-icon "Home" shortcut (`4195e0e`, 2026-11-07, tagged `v0.1.6`).

## Trivia

See [trivia.md](trivia.md) — starting with the release that was tagged but
never actually built.
