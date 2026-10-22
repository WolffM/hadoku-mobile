import { test, expect, LAUNCHER, USER_AGENT_SUFFIX } from './app';

// Signed out, so no credential is needed and nothing in production changes:
// these run anywhere an emulator does. Signed-in behaviour is in signed-in.spec.ts.

test('opens the launcher as the app, with the bridge in the page', async ({ app }) => {
  const page = await app.launch();
  await expect(page).toHaveURL(LAUNCHER);
  await expect(page).toHaveTitle('Apps');
  expect(await page.evaluate(() => (window as any).Capacitor?.isNativePlatform?.())).toBe(true);
  expect(await page.evaluate(() => navigator.userAgent)).toMatch(new RegExp(`${USER_AGENT_SUFFIX.replace('.', '\\.')}$`));
  expect(await app.nativeSession()).toBe('absent');
});

test('signed out, every tile above public is locked and the corner offers sign-in', async ({ app }) => {
  const page = await app.launch();
  const tiles = page.locator('a.app[data-tier]');
  await expect(tiles.first()).toBeVisible();
  const lockedness = await tiles.evaluateAll((as) => as.map((a) => [a.getAttribute('data-tier'), a.classList.contains('app--locked')]));
  expect(lockedness.length).toBeGreaterThan(10);
  for (const [tier, locked] of lockedness) expect(locked, `${tier} tile`).toBe(tier !== 'public');
  await expect(page.locator('#session')).toHaveAttribute('href', '/auth?return=/apps/');
});

test('a tile opens its site inside the app and Back returns to the launcher', async ({ app }) => {
  const page = await app.launch();
  await page.locator('a.app[href="/resume"]').click();
  await page.waitForURL('https://hadoku.me/resume**');
  // The bridge follows the visitor across sites, not just on the launcher.
  expect(await page.evaluate(() => (window as any).Capacitor?.isNativePlatform?.())).toBe(true);

  await app.back();
  await page.waitForURL(LAUNCHER);
  expect(await app.isForeground()).toBe(true);
});

test('Back on the launcher leaves the app instead of stranding a blank page', async ({ app }) => {
  await app.launch();
  await app.back();
  await expect.poll(() => app.isForeground()).toBe(false);
});

test('sign-in is the site’s own form, inside the app', async ({ app }) => {
  const page = await app.launch();
  await page.locator('#session').click();
  await page.waitForURL('https://hadoku.me/auth?return=/apps/');
  await expect(page.locator('#auth-key')).toBeVisible();
});
