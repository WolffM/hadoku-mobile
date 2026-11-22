import type { Page } from 'playwright';
import { test, expect, LAUNCHER } from './app';

// A page's own <audio>, played inside the app, keeps playing with the app in the
// background and the screen off, as it does in Chrome. Without a foreground
// service Android caches the process once the screen goes off and the freezer
// stops it about a minute later, audio and all (first user report: a music
// player page, 2026-11-21). Minutes long, so it is its own project.

const BACKGROUND_MS = 4 * 60_000;

test('page audio plays on after Home and screen-off, and the keep-alive follows it', async ({ app }) => {
  test.setTimeout(BACKGROUND_MS + 4 * 60_000);
  const page = await app.launch();
  await expect(page).toHaveURL(LAUNCHER);

  // A detached `new Audio()`, like a music player page's: no element in the
  // document, so only the play() hook can see it.
  await playTone(page, { detached: true });
  const position = () => page.evaluate(() => (window as any).probe.currentTime as number);
  await expect.poll(position).toBeGreaterThan(1);
  await expect.poll(() => app.log(), { message: 'keep-alive started' }).toMatch(/page media keep-alive started .*title=Probe tone/);
  expect(await app.notification()).toContain('Probe tone');

  const from = await position();
  const started = Date.now();
  await app.home();
  await app.screen(false);
  const frozen: number[] = [];
  try {
    // Sampled, not slept: a frozen sample names the moment it stopped.
    while (Date.now() - started < BACKGROUND_MS) {
      await new Promise((r) => setTimeout(r, 10_000));
      if (await app.frozen()) frozen.push(Math.round((Date.now() - started) / 1000));
    }
  } finally {
    await app.screen(true);
  }
  expect(frozen, `frozen at these seconds in the background: ${frozen.join(', ')}`).toEqual([]);
  // Read before the app returns to the foreground, which would thaw it.
  const elapsed = (Date.now() - started) / 1000;
  const played = (await withTimeout(position(), 10_000, 'the page did not answer: the app is frozen')) - from;
  // Frozen, it stops a minute in (66 s of 240 before the fix). Alive, it keeps the wall
  // clock's pace less what a starved emulator drops: 232.5 s of 250 at a host load of 160.
  expect(played, `played ${played.toFixed(1)} s of ${elapsed.toFixed(1)} s in the background`).toBeGreaterThan(elapsed * 0.8);

  console.log(`played ${played.toFixed(1)} s of ${elapsed.toFixed(1)} s in the background, never frozen`);

  // Leaving the page ends the keep-alive at once; nothing is left behind. The app is
  // still behind Home, where a page's load event can take minutes: the start is what counts.
  await page.goto(LAUNCHER, { waitUntil: 'commit' });
  await expect.poll(() => app.log(), { message: 'keep-alive stopped' }).toMatch(/page media keep-alive stopped reason=navigation/);
  await expect.poll(() => app.notification()).not.toContain('Probe tone');
});

test('the system media keys pause and play the page, and muted media never holds the app', async ({ app }) => {
  // A launch alone took over a minute on hokon at a load of 160.
  test.setTimeout(4 * 60_000);
  const page = await app.launch();
  // An autoplaying element in the document this time: the capture listener's path.
  await playTone(page, { detached: false });
  await expect.poll(() => app.log()).toMatch(/page media keep-alive started .*title=Probe tone/);
  const paused = () => page.evaluate(() => (window as any).probe.paused as boolean);

  // What the lock screen and a headset send, routed to the keep-alive's session.
  await app.mediaKey('pause');
  await expect.poll(paused).toBe(true);
  await expect.poll(() => app.log()).toMatch(/page media keep-alive paused/);
  await expect.poll(() => app.notification()).toContain('Paused');

  // A muted looping video, like a player's card art, is not sound.
  await page.evaluate(() => {
    const video = document.createElement('video');
    video.muted = true;
    video.loop = true;
    video.src = (window as any).probe.src;
    document.body.append(video);
    return video.play();
  });
  await new Promise((r) => setTimeout(r, 2_000));
  expect(await app.log()).not.toMatch(/keep-alive resumed/);

  await app.mediaKey('play');
  await expect.poll(paused).toBe(false);
  await expect.poll(() => app.log()).toMatch(/page media keep-alive resumed/);
});

/** Ten minutes of a quiet tone, made in the page so no media file is needed, played as `window.probe`. */
async function playTone(page: Page, { detached }: { detached: boolean }): Promise<void> {
  await page.evaluate(async (detached) => {
    const rate = 8000;
    const seconds = 600;
    const bytes = new Uint8Array(44 + rate * seconds);
    const view = new DataView(bytes.buffer);
    const text = (at: number, s: string) => [...s].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)));
    text(0, 'RIFF');
    view.setUint32(4, bytes.length - 8, true);
    text(8, 'WAVEfmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, rate, true);
    view.setUint32(28, rate, true);
    view.setUint16(32, 1, true);
    view.setUint16(34, 8, true);
    text(36, 'data');
    view.setUint32(40, rate * seconds, true);
    for (let i = 0; i < rate * seconds; i++) bytes[44 + i] = 128 + Math.round(8 * Math.sin((2 * Math.PI * 440 * i) / rate));
    const audio = new Audio(URL.createObjectURL(new Blob([bytes], { type: 'audio/wav' })));
    (window as any).probe = audio;
    // Android WebView has no Media Session API, so the document's title is what names it.
    document.title = 'Probe tone';
    if (detached) return audio.play();
    // Autoplay in the document never calls play(): only the capture listener sees it.
    const playing = new Promise((r) => audio.addEventListener('playing', r, { once: true }));
    audio.autoplay = true;
    document.body.append(audio);
    await playing;
  }, detached);
}

async function withTimeout<T>(p: Promise<T>, ms: number, message: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => (timer = setTimeout(() => reject(new Error(message)), ms)));
  try {
    return await Promise.race([p, timeout]);
  } finally {
    clearTimeout(timer);
  }
}
