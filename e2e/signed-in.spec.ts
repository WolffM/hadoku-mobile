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
  // Home then an immediate kill is the worst case: Chromium would otherwise
  // write the cookie to disk only on its own periodic flush.
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
