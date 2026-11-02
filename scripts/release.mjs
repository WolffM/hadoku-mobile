#!/usr/bin/env node
// Cuts a release without editing anything in the repo:
//
//   pnpm release patch|minor|major|X.Y.Z
//
// Dispatches build-apk.yml on main with `release=<arg>`. That run computes the
// version from the newest v* tag (scripts/release-version.mjs), runs the full
// gate, builds the signed APK with that versionName/versionCode, and creates
// the tag and the GitHub Release on the sha it built. No version is committed.
import { execFileSync } from 'node:child_process';

const request = process.argv[2];
if (!/^(patch|minor|major|\d+\.\d+\.\d+)$/.test(request ?? '')) {
  console.error('usage: pnpm release patch|minor|major|X.Y.Z');
  process.exit(1);
}

execFileSync('gh', ['workflow', 'run', 'build-apk.yml', '--repo', 'WolffM/hadoku-mobile', '--ref', 'main', '-f', `release=${request}`], {
  stdio: 'inherit',
});
console.log('dispatched: `gh run list --repo WolffM/hadoku-mobile --workflow build-apk.yml --limit 1` shows the run');
