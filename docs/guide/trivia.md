# Trivia

- `v0.1.0` is a real tag on a real commit, but it was never a working
  release. Its signed build "opened the keystore but failed reading the
  key with `HADOKU_MOBILE_KEY_PASSWORD`," because `keytool` ignores
  `-keypass` for a PKCS12 file, so the key's password was always the
  store's (`9d0885e`). `v0.1.1`, tagged 8 minutes 40 seconds later, is the
  first release that exists as a built, signed APK (`git for-each-ref
  --sort=creatordate --format='%(refname:short) %(creatordate:iso-strict)'
  refs/tags`; `50c10dd`).
- The app's busiest day by far is its second: 2026-10-21 has 14 of the
  repo's 35 commits (`git log --format=%ad --date=short | grep -c
  2026-10-21`), landing the whole of Library v1 — browse, download,
  resumable playback — in one day.
- For its first twelve days the release process bumped `package.json`'s
  version field directly and committed it; one commit replaced the whole
  flow with a version computed at release time from the newest git tag, and
  `package.json` has carried no version field since (`f2c0d88`).
- The versionCode scheme (`major*1e6 + minor*1e3 + patch`) is documented
  with its own worked example in the commit that introduced it: "v0.1.5 was
  1005, the next patch 1006" (`f2c0d88`).
- The app icon was never drawn for this app: it was copied straight from
  `hadoku-task-mobile`'s shipped resources — "the set its CI actually
  ships," not that repo's older, unused copy — fox and all (`934e754`).
