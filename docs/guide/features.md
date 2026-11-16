# Features

What the app does, for someone using it. No feature here has a tier: the
app itself enforces nothing — it reads whatever cookie the site already set
and forwards it, and access to a site or a watchparty episode is decided
by the site you're visiting, not by this shell.

## App shell

**What it does.** Opens the live hadoku.me launcher in a full-screen WebView
and lets you navigate the real site from there — every tile, every sign-in
form, every page is the actual website, not a copy bundled into the app.
Signing in once sets the same session cookie a browser would get, so every
site behind a tile already sees you as signed in.

**How to use it.** Open the app; it starts on the launcher. Tapping a tile
goes to that site, and Back returns the way a browser's Back would — through
that site's own history first, then back to the launcher. From anywhere, a
long-press on the app's icon offers a "Home" shortcut that jumps straight
back to the launcher, whether the app was already open or not.

**What it does not do.** The shell stores no credential of its own — no key,
no token, nothing written to disk by the app. It reads the session cookie
from the WebView's cookie store only at the moment something native needs
it (a download), and never logs its value, only whether one is present.

## Library

**What it does.** A native screen for browsing watchparty's episode catalog
and saving an episode to the phone, picking which audio languages and
subtitle tracks to include before it downloads.

**How to use it.** The Library opens from a link that only appears inside
the app, on watchparty's own lobby page; it also opens automatically if the
app is launched with no network at all. From its home screen, "Browse
watchparty" lists the catalog; picking an episode shows the audio and
subtitle choices, the download size, and how much free space the download
needs versus how much the phone has. Downloads keep going through app kills,
connectivity changes and reboots — they are handed to Android's own
download manager, not held in the app's memory.

**What it does not do.** Browsing and starting a download both need a live
network and a signed-in session; everything else in the Library (seeing
what's already saved, playing it, deleting it) works offline. A failed
download is retried automatically only if the server's copy hasn't changed
underneath it; if it has, the Library says to delete and download again
rather than splice two different encodes together.

## Offline playback

**What it does.** Plays a downloaded episode with background audio (the
video keeps playing as audio-only once you leave the screen), lock-screen
and notification controls, and a position that's remembered automatically
so resuming later picks up where you left off.

**How to use it.** Tap a ready episode in the Library to start it. Leaving
the screen with Back stops playback; leaving any other way (Home, switching
apps, the screen turning off) keeps the audio going, controllable from the
lock screen, a Bluetooth headset, or the notification. Returning to the
episode screen, including by tapping the notification, reconnects to the
still-running playback. Finishing an episode (reaching its last ~30 seconds,
or 1/10 of its length for a short one) resets its resume position, so the
next play starts over instead of on the credits.

**What it does not do.** There's only ever one player: starting a second
episode replaces whatever was already playing rather than queuing it.

## Trivia

The app only has one screen that is not simply a page of the live site —
the Library — and it exists because remote mode has no offline page of its
own, only a browser error, so a launch with no network opens the Library
instead (`0c0f49c`).
