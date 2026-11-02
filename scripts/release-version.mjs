#!/usr/bin/env node
// The app's version is computed at release time and never committed.
//
//   node scripts/release-version.mjs <patch|minor|major|X.Y.Z>
//
// Reads every `vX.Y.Z` tag on origin, takes the newest, and steps it by the
// requested part (or takes an exact X.Y.Z, which must be newer). Prints
// `name=`, `code=` and `tag=` lines, and appends them to $GITHUB_OUTPUT when
// set. With no argument it prints the development identity a non-release build
// carries.
//
// versionCode is major*1e6 + minor*1e3 + patch, so it rises with every release
// and Android installs the new APK over the old one. It refuses a minor or
// patch of 1000 or more, where that encoding would collide.
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const DEV = { name: '0.0.0-dev', code: 1 };
const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

const parse = (v) => {
  const m = SEMVER.exec(v);
  if (!m) throw new Error(`not an X.Y.Z version: ${v}`);
  return m.slice(1, 4).map(Number);
};

const compare = (a, b) => {
  const [x, y] = [parse(a), parse(b)];
  return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
};

export function versionCode(version) {
  const [major, minor, patch] = parse(version);
  if (minor >= 1000 || patch >= 1000) throw new Error(`${version}: minor and patch must stay below 1000 for versionCode`);
  return major * 1_000_000 + minor * 1_000 + patch;
}

/** The newest strict `vX.Y.Z` tag name among `git ls-remote --tags` lines, without the `v`; null when there is none. */
export function newestTag(lsRemote) {
  const versions = lsRemote
    .split('\n')
    .map((line) => line.split('\t')[1] ?? '')
    .map((ref) => /^refs\/tags\/v(\d+\.\d+\.\d+)$/.exec(ref)?.[1])
    .filter(Boolean);
  return versions.sort(compare).at(-1) ?? null;
}

export function nextVersion(newest, request) {
  const base = newest ?? '0.0.0';
  const [major, minor, patch] = parse(base);
  const next =
    { major: `${major + 1}.0.0`, minor: `${major}.${minor + 1}.0`, patch: `${major}.${minor}.${patch + 1}` }[request] ??
    request;
  parse(next);
  if (newest && compare(next, newest) <= 0) throw new Error(`${next} is not newer than the latest release ${newest}`);
  return { name: next, code: versionCode(next), tag: `v${next}` };
}

function main(request) {
  const out = request
    ? nextVersion(newestTag(execFileSync('git', ['ls-remote', '--tags', 'origin'], { encoding: 'utf8' })), request)
    : { ...DEV, tag: '' };
  const lines = Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n') + '\n';
  process.stdout.write(lines);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, lines);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv[2]);
  } catch (err) {
    console.error(`release-version: ${err.message}`);
    process.exit(1);
  }
}
