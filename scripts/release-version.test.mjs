import { test } from 'node:test';
import assert from 'node:assert/strict';
import { newestTag, nextVersion, versionCode } from './release-version.mjs';

const lsRemote = [
  'aaa\trefs/tags/v0.1.9',
  'bbb\trefs/tags/v0.1.9^{}',
  'ccc\trefs/tags/v0.1.10',
  'ddd\trefs/tags/v0.1.10^{}',
  'eee\trefs/tags/v0.2.0-rc1',
  'fff\trefs/tags/release/9.9.9',
].join('\n');

test('the newest tag is compared numerically, and only strict vX.Y.Z tags count', () => {
  assert.equal(newestTag(lsRemote), '0.1.10');
  assert.equal(newestTag(''), null);
});

test('each part steps from the newest tag', () => {
  assert.deepEqual(nextVersion('0.1.10', 'patch'), { name: '0.1.11', code: 1011, tag: 'v0.1.11' });
  assert.equal(nextVersion('0.1.10', 'minor').name, '0.2.0');
  assert.equal(nextVersion('0.1.10', 'major').name, '1.0.0');
  assert.equal(nextVersion(null, 'patch').name, '0.0.1');
});

test('an exact version must be newer than the latest release', () => {
  assert.equal(nextVersion('0.1.10', '0.3.0').code, 3000);
  assert.throws(() => nextVersion('0.1.10', '0.1.10'), /not newer/);
  assert.throws(() => nextVersion('0.1.10', '0.1.2'), /not newer/);
  assert.throws(() => nextVersion('0.1.10', 'v0.2.0'), /X\.Y\.Z/);
});

test('versionCode rises with the version and refuses a part that would collide', () => {
  assert.ok(versionCode('0.1.6') > versionCode('0.1.5'));
  assert.ok(versionCode('0.2.0') > versionCode('0.1.999'));
  assert.throws(() => versionCode('0.1000.0'), /below 1000/);
});
