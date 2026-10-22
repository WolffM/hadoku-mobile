#!/usr/bin/env node
// Cuts a release: bumps package.json (the only version anywhere — Gradle and
// the user-agent suffix read it), commits, tags v<version>, pushes both. The
// tag is what makes CI build the signed APK and attach it to a GitHub Release.
//
//   pnpm release patch|minor|major
//
// Refuses unless the tree is clean and HEAD is exactly origin/main, so a tag
// always names a commit that has already been through the `build` gate.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const fail = (msg) => {
  console.error(`release: ${msg}`);
  process.exit(1);
};

const part = process.argv[2];
if (!['patch', 'minor', 'major'].includes(part)) fail('usage: pnpm release patch|minor|major');

if (git('status', '--porcelain')) fail('working tree is not clean');
git('fetch', '--quiet', 'origin', 'main');
if (git('rev-parse', 'HEAD') !== git('rev-parse', 'origin/main')) fail('HEAD is not origin/main; integrate first');

const pkgPath = new URL('../package.json', import.meta.url);
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
const [major, minor, patch] = pkg.version.split('.').map(Number);
const next = { major: `${major + 1}.0.0`, minor: `${major}.${minor + 1}.0`, patch: `${major}.${minor}.${patch + 1}` }[part];
const tag = `v${next}`;
if (git('tag', '--list', tag)) fail(`${tag} already exists`);

pkg.version = next;
writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`);
git('add', 'package.json');
git('commit', '--quiet', '-m', `release: ${tag}`);
git('tag', '-a', tag, '-m', `hadoku-mobile ${tag}`);
git('push', '--quiet', 'origin', 'HEAD:main');
git('push', '--quiet', 'origin', tag);
console.log(`released ${tag}: CI builds the signed APK and attaches it to the GitHub Release`);
