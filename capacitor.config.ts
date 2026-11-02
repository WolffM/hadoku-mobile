import type { CapacitorConfig } from '@capacitor/cli';
// The same APP_VERSION_NAME android/app/build.gradle makes the versionName,
// so the user-agent suffix request logs key on can't drift from the APK. CI
// sets it at release time (scripts/release-version.mjs); nothing commits it.
const version = process.env.APP_VERSION_NAME ?? '0.0.0-dev';

const config: CapacitorConfig = {
  appId: 'me.hadoku.app',
  appName: 'Hadoku',
  webDir: 'www',
  server: {
    url: 'https://hadoku.me/apps/',
    androidScheme: 'https',
  },
  android: {
    allowMixedContent: false,
    appendUserAgent: `HadokuApp/${version}`,
  },
  plugins: {
    SystemBars: {
      insetsHandling: 'native',
      style: 'DEFAULT',
    },
  },
};

export default config;
