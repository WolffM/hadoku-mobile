---
title: Hadoku (mobile)
summary: >-
  One Android app for every hadoku.me site: a thin shell that opens the live
  launcher page and carries your sign-in into it, plus one native screen —
  a Library that can download a watchparty episode for offline playback with
  background audio and lock-screen controls.
features:
  - name: App shell
    summary: 'Wraps the live hadoku.me launcher; the app icon''s long-press "Home" shortcut returns to it from anywhere.'
    anchor: app-shell
  - name: Library
    summary: 'Browse the watchparty catalog and download an episode, choosing which audio languages and subtitles to save.'
    anchor: library
  - name: Offline playback
    summary: 'Play a downloaded episode with background audio, lock-screen controls, and a saved resume position.'
    anchor: offline-playback
trivia:
  - 'The first commit already logs two things: whether the native layer can see the session cookie, and whether the page thinks it is running inside the app (`9446dd3`).'
  - 'The first release tag, v0.1.0, was never actually shipped — its signed build failed over a keystore password `keytool` silently ignores, so v0.1.1 is the first release that exists as a built APK (`50c10dd`).'
  - 'Versions are never written into a file: a release computes the version at build time from the newest `v*` tag and steps it (`f2c0d88`).'
  - 'The app icon is borrowed, not drawn for this app: it was copied straight from `hadoku-task-mobile`''s shipped resources, fox and all (`934e754`).'
---

# Hadoku (mobile)

This is a [Capacitor](https://capacitorjs.com/) WebView shell: everything a
visitor sees is the live hadoku.me site, loaded over HTTPS as if from Chrome,
so signing in and every tile behave exactly as they do in a browser. Native
code exists only where the web cannot go — right now, that is exactly one
screen: an offline Library for watchparty episodes.

- [Features](features.md): the shell and the Library, what they do and don't.
- [Contributing](contributing.md): building it, the test layers, and how a
  release ships.
- [History](history.md): five releases in four days, and the one before
  them that never shipped.
- [Trivia](trivia.md)

## Trivia

The app's very first commit set up logging for two facts and nothing else:
whether the native layer can see the session cookie the site had set, and
whether the page itself could tell it was running inside the
app ([history](history.md#the-shell-first)).
