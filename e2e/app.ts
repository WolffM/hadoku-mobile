import { test as base, expect } from '@playwright/test';
import { _android, type AndroidDevice, type Page } from 'playwright';
import { version } from '../package.json';

export const PKG = 'me.hadoku.app';
export const LAUNCHER = 'https://hadoku.me/apps/';
export const USER_AGENT_SUFFIX = `HadokuApp/${version}`;

/**
 * The installed debug APK on the one attached device or emulator, driven the
 * way a person drives it (launch, Home, Back, killed by the system) while
 * Playwright holds the live hadoku.me page inside its WebView. Debug builds
 * have WebView debugging on (Capacitor's default), which is what lets
 * Playwright attach; a release build cannot be driven this way.
 */
export class App {
  constructor(readonly device: AndroidDevice) {}

  /** Fresh install state: no cookies, no WebView storage, no downloads. */
  async reset(): Promise<void> {
    await this.shell(`pm clear ${PKG}`);
  }

  /** Cold-starts the app (or brings it back) and returns its page. */
  async launch(): Promise<Page> {
    await this.shell('logcat -c');
    await this.shell(`am start -W -n ${PKG}/.MainActivity`);
    const pid = await this.pid();
    // A relaunch is a new process with a new WebView; Playwright may still
    // list the dead one for a moment, so match on the live pid.
    await expect.poll(async () => (await this.device.webViews()).some((w) => w.pid() === pid), { timeout: 30_000 }).toBe(true);
    const webView = (await this.device.webViews()).find((w) => w.pid() === pid)!;
    const page = await webView.page();
    await page.waitForLoadState('domcontentloaded');
    return page;
  }

  /** What Android does to a backgrounded app under memory pressure. */
  async kill(): Promise<void> {
    await this.shell(`am force-stop ${PKG}`);
  }

  async home(): Promise<void> {
    await this.shell('input keyevent KEYCODE_HOME');
  }

  async back(): Promise<void> {
    await this.shell('input keyevent KEYCODE_BACK');
  }

  async isForeground(): Promise<boolean> {
    const out = await this.shell('dumpsys activity activities');
    return /ResumedActivity.*me\.hadoku\.app\//.test(out);
  }

  /**
   * Whether the NATIVE layer sees the session cookie, read from the line
   * MainActivity logs on every page load. That line carries presence only,
   * never the value, so this checks the credential path without exposing it.
   */
  async nativeSession(url = LAUNCHER): Promise<'present' | 'absent'> {
    const line = new RegExp(`page loaded url=${url.replace(/[.?/]/g, '\\$&')} hadoku_session=(present|absent)`);
    let seen: 'present' | 'absent' | undefined;
    await expect
      .poll(async () => (seen = line.exec(await this.shell('logcat -d -s HadokuShell:I'))?.[1] as typeof seen), {
        message: `no HadokuShell probe for ${url}`,
        timeout: 15_000,
      })
      .toBeTruthy();
    return seen!;
  }

  private async pid(): Promise<number> {
    let pid = 0;
    await expect.poll(async () => (pid = Number((await this.shell(`pidof ${PKG}`)).trim()) || 0)).toBeGreaterThan(0);
    return pid;
  }

  private async shell(command: string): Promise<string> {
    return (await this.device.shell(command)).toString();
  }
}

export const test = base.extend<{ app: App }, { device: AndroidDevice }>({
  device: [
    async ({}, use) => {
      const devices = await _android.devices();
      if (devices.length !== 1) throw new Error(`need exactly one adb device, found ${devices.length} (pnpm emulator boots one)`);
      await use(devices[0]);
      await devices[0].close();
    },
    { scope: 'worker' },
  ],
  app: async ({ device }, use) => {
    const app = new App(device);
    await app.reset();
    await use(app);
  },
});

export { expect };
