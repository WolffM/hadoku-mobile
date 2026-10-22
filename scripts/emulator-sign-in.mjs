#!/usr/bin/env node
// Signs the emulator's app in through the site's own form, and leaves it
// signed in (no pm clear), so device tests that need a session can run:
// `pnpm test:download-resume`, the Library later.
//
//   HADOKU_E2E_KEY=… pnpm emulator:sign-in     or just `pnpm emulator:sign-in`
//                                              and type the key (not echoed)
//
// Creates a real 30-day session in production for that key's identity.
import { _android } from 'playwright';
import { createInterface } from 'node:readline';

async function askHidden(prompt) {
  if (!process.stdin.isTTY) throw new Error('HADOKU_E2E_KEY is not set and there is no terminal to ask on');
  const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
  rl._writeToOutput = (s) => rl.output.write(s.startsWith(prompt) ? prompt : '');
  const key = await new Promise((resolve) => rl.question(prompt, resolve));
  rl.close();
  process.stdout.write('\n');
  return key.trim();
}

const key = process.env.HADOKU_E2E_KEY || (await askHidden('hadoku.me access key: '));
const [device, ...others] = await _android.devices();
if (!device || others.length) throw new Error('need exactly one adb device (pnpm emulator boots one)');

await device.shell('am force-stop me.hadoku.app');
await device.shell('am start -W -n me.hadoku.app/.MainActivity');
const page = await (await device.webView({ pkg: 'me.hadoku.app' }, { timeout: 30_000 })).page();
await page.goto('https://hadoku.me/auth?return=/apps/');
await page.locator('#auth-key').fill(key);
await page.locator('#submit-btn').click();
await page.waitForURL('https://hadoku.me/apps/');
const session = page.locator('#session[aria-current="true"]');
await session.waitFor({ timeout: 15_000 });
console.log(`signed in as ${await session.textContent()}`);
// Home, so MainActivity's onPause flushes the cookie to disk before anything
// else touches the process.
await device.shell('input keyevent KEYCODE_HOME');
await device.close();
