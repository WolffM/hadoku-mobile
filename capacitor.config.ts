import type { CapacitorConfig } from '@capacitor/cli';
// package.json is the one place the version lives (android/app/build.gradle
// reads it too), so the user-agent suffix request logs key on can't drift
// from the APK's versionName.
import { version } from './package.json';

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
