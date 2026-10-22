import { defineConfig } from '@playwright/test';

// One device, so one worker and no parallelism: every test resets the app's
// state (pm clear) and drives the same screen.
export default defineConfig({
  testDir: '.',
  workers: 1,
  fullyParallel: false,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'report' }]],
  outputDir: 'results',
  projects: [
    { name: 'shell', testMatch: 'shell.spec.ts' },
    { name: 'signed-in', testMatch: 'signed-in.spec.ts' },
  ],
});
