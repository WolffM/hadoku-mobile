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
