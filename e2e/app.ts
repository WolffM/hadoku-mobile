import { test as base, expect } from '@playwright/test';
import { _android, type AndroidDevice, type Page } from 'playwright';

export const PKG = 'me.hadoku.app';
export const LAUNCHER = 'https://hadoku.me/apps/';
// The installed APK's suffix: capacitor.config.ts falls back to the same
// development identity when APP_VERSION_NAME is unset, as it is for a local build.
export const USER_AGENT_SUFFIX = `HadokuApp/${process.env.APP_VERSION_NAME ?? '0.0.0-dev'}`;

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

  /** Airplane mode, as the quick-settings tile sets it. */
  async airplane(on: boolean): Promise<void> {
    await this.shell(`cmd connectivity airplane-mode ${on ? 'enable' : 'disable'}`);
  }

  async home(): Promise<void> {
    await this.shell('input keyevent KEYCODE_HOME');
  }

  /** The app icon's long-press "Home" shortcut, as res/xml/shortcuts.xml sends it. */
  async homeShortcut(): Promise<void> {
    await this.shell(`am start -W -a ${PKG}.HOME -n ${PKG}/.MainActivity`);
  }

  async back(): Promise<void> {
    await this.shell('input keyevent KEYCODE_BACK');
  }

  /** The power key: the screen off (and the device free to doze), or back on and unlocked. */
  async screen(on: boolean): Promise<void> {
    await this.shell(`input keyevent ${on ? 'KEYCODE_WAKEUP' : 'KEYCODE_SLEEP'}`);
    if (on) await this.shell('wm dismiss-keyguard');
  }

  /** A system media key, as the lock screen or a headset sends it to the active session. */
  async mediaKey(key: 'play' | 'pause'): Promise<void> {
    await this.shell(`cmd media_session dispatch ${key}`);
  }

  /** Whether the cached-apps freezer holds the app's process right now. */
  async frozen(): Promise<boolean> {
    const pid = (await this.shell(`pidof ${PKG}`)).trim();
    const out = await this.shell('dumpsys activity');
    const list = out.slice(out.indexOf('Apps frozen:'), out.indexOf('CacheOomRanker'));
    return pid !== '' && new RegExp(`\\b${pid} ${PKG.replaceAll('.', '\\.')}\\b`).test(list);
  }

  /** The app's own lines in logcat since launch (MainActivity and the page-media keep-alive). */
  async log(): Promise<string> {
    return this.shell('logcat -d -s HadokuShell:I');
  }

  /** The app's notifications, as the shade holds them. */
  async notification(): Promise<string> {
    const out = await this.shell('dumpsys notification --noredact');
    return out
      .split(/\n(?=\s*NotificationRecord\()/)
      .filter((r) => r.includes(`pkg=${PKG}`))
      .join('\n');
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
    const line = new RegExp(`page loaded url=${url.replace(/[.?/]/g, '\\$&')} hadoku_session=(present|absent)`, 'g');
    let seen: 'present' | 'absent' | undefined;
    // The latest load of that url: the log also holds loads from before a sign-in.
    const latest = (log: string) => [...log.matchAll(line)].at(-1)?.[1] as typeof seen;
    await expect
      .poll(async () => (seen = latest(await this.shell('logcat -d -s HadokuShell:I'))), {
        message: `no HadokuShell probe for ${url}`,
        timeout: 15_000,
      })
      .toBeTruthy();
    return seen!;
  }

  private async pid(): Promise<number> {
    let pid = 0;
    // A cold start took 25 s on hokon at a load of 115, and missed 15 s at 160.
    await expect
      .poll(async () => (pid = Number((await this.shell(`pidof ${PKG}`)).trim()) || 0), { timeout: 60_000 })
      .toBeGreaterThan(0);
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
