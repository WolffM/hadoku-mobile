import { test, expect, LAUNCHER, type App } from './app';
import type { Page } from 'playwright';

// Needs a real access key in HADOKU_E2E_KEY (`pnpm e2e:signed-in`). Signing in
// creates a real 30-day session for that identity in production, so this
// project never runs unattended and never runs in CI.
const key = process.env.HADOKU_E2E_KEY;

test.beforeAll(() => {
  if (!key) throw new Error('HADOKU_E2E_KEY is not set; the signed-in project needs an access key');
});

async function signIn(app: App): Promise<Page> {
  const page = await app.launch();
  await page.locator('#session').click();
  await page.waitForURL('https://hadoku.me/auth?return=/apps/');
  await page.locator('#auth-key').fill(key!);
  await page.locator('#submit-btn').click();
  await page.waitForURL(LAUNCHER);
  await expect(page.locator('#session')).toHaveAttribute('aria-current', 'true');
  return page;
}

test('the site’s sign-in reaches the native layer, and the launcher unlocks', async ({ app }) => {
  const page = await signIn(app);
  // HttpOnly: page JavaScript must not see it, native code must.
  expect(await page.evaluate(() => document.cookie)).not.toContain('hadoku_session');
  expect(await app.nativeSession()).toBe('present');
  await expect(page.locator('a.app[data-tier="friend"].app--locked')).toHaveCount(0);
});

test('a session survives Android killing the app straight after sign-in', async ({ app }) => {
  await signIn(app);
  // The shell writes cookies to disk on every page load, before this probe
  // line; from then on a kill loses nothing, even one that lands before
  // Android has paused the activity (Chromium alone writes only periodically).
  expect(await app.nativeSession()).toBe('present');
  await app.home();
  await app.kill();
  const page = await app.launch();
  expect(await app.nativeSession()).toBe('present');
  await expect(page.locator('#session')).toHaveAttribute('aria-current', 'true');
});

test('the session follows the visitor into another site', async ({ app }) => {
  const page = await signIn(app);
  await page.goto('https://hadoku.me/watchparty/');
  const whoami = await page.evaluate(async () => (await fetch('/session/whoami', { credentials: 'include' })).json());
  expect(whoami.valid).toBe(true);
});

test('a launcher left in history from before sign-in shows the session when the app goes back to it', async ({ app }) => {
  // The launcher as it was before sign-in: "Sign in", tiles locked.
  const page = await app.launch();
  await expect(page.locator('#session')).toHaveText('Sign in');
  // Sign in somewhere else: an app's own sign-in wall returns to that app.
  await page.goto('https://hadoku.me/auth?return=/watchparty/');
  await page.locator('#auth-key').fill(key!);
  await page.locator('#submit-btn').click();
  await page.waitForURL('https://hadoku.me/watchparty/');
  // A site link home: the shell goes BACK to that old launcher entry, which
  // the WebView may restore from its back/forward cache without a reload.
  await page.evaluate(() => {
    location.href = '/';
  });
  await page.waitForURL(LAUNCHER);
  await expect(page.locator('#session')).toHaveAttribute('aria-current', 'true');
  await expect(page.locator('a.app[data-tier="friend"].app--locked')).toHaveCount(0);
});

test('a launcher that was showing "Sign in" picks up a session when the network comes back', async ({ app }) => {
  const page = await signIn(app);
  // Signed in, but the launcher's whoami at load gets no answer, as with no
  // signal yet: it keeps saying "Sign in" over a live session.
  await page.route('**/session/whoami', (route) => route.abort('internetdisconnected'));
  await page.reload();
  await expect(page.locator('#session')).toHaveText('Sign in');
  await page.unroute('**/session/whoami');
  // The signal drops and returns. (Coming back from Home asks again too, but
  // that cannot be watched from here: while Playwright holds the page, the
  // WebView never reports it hidden.)
  await app.airplane(true);
  try {
    await expect.poll(() => page.evaluate(() => navigator.onLine)).toBe(false);
  } finally {
    await app.airplane(false);
  }
  await expect(page.locator('#session')).toHaveAttribute('aria-current', 'true', { timeout: 30_000 });
  await expect(page.locator('a.app[data-tier="friend"].app--locked')).toHaveCount(0);
});

test('Back after signing in steps past the sign-in page to a launcher that shows the session', async ({ app }) => {
  const page = await app.launch();
  await expect(page.locator('#session')).toHaveText('Sign in');
  await page.locator('#session').click();
  await page.waitForURL('https://hadoku.me/auth?return=/apps/');
  await page.locator('#auth-key').fill(key!);
  await page.locator('#submit-btn').click();
  await page.waitForURL(LAUNCHER);
  await page.goto('https://hadoku.me/watchparty/');
  // Back, as a person presses it, until the launcher: never stuck on /auth.
  await expect(async () => {
    if (page.url() !== LAUNCHER) await app.back();
    expect(page.url()).toBe(LAUNCHER);
  }).toPass({ timeout: 20_000, intervals: [1_500] });
  await expect(page.locator('#session')).toHaveAttribute('aria-current', 'true');
});
