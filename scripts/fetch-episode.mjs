#!/usr/bin/env node
// Fetches one REAL watchparty episode from production into a local cache, for
// the device tests that play real media (scripts/test-player-real.sh). Prints
// the episode's directory on stdout; everything else goes to stderr.
//
//   HADOKU_E2E_KEY=… node scripts/fetch-episode.mjs output/fmp4/anime/Frieren/frieren-s01e01
//
// It takes the files the way the app's own download does, and no other way:
//   - one whole-file GET per file, one file at a time, with the header that
//     ranks a download below a live party at the origin, and a rate limit;
//   - never a Range request. Through the edge a Range on a large file is
//     answered with the WHOLE file often enough to matter (hadoku-watchparty
//     AGENTS.md, "Edge Range collapsing"), so an interrupted file starts over
//     instead of resuming;
//   - every file is checked against the sha256 index.json records for it
//     (`part_sha256`; the video's is also the episode's content_token) before
//     it counts. A cached episode whose token no longer matches production was
//     re-encoded there, and is fetched again.
//
// The cache is ~/.cache/hadoku-mobile/episodes (HADOKU_EPISODE_CACHE to move
// it). An episode is 300-800 MB: this is one phone's worth of download, so do
// not run it in a loop over the library.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

const SITE = 'https://hadoku.me';
const RATE = process.env.HADOKU_FETCH_RATE || '25M';
const AUDIO = /^audio\/audio_[a-z]{3}_\d+\.m4a$/i;
const SUBTITLE = /^sub_[a-zA-Z][a-zA-Z0-9-]*\.vtt$/;

const mediaId = process.argv[2];
const key = process.env.HADOKU_E2E_KEY;
if (!mediaId || !/^[\w./ -]+$/.test(mediaId) || mediaId.includes('..')) fail('usage: fetch-episode.mjs <media id, e.g. output/fmp4/anime/Show/show-s01e01>');
if (!key) fail('HADOKU_E2E_KEY is not set: run under dev-vault (node ../hadoku_site/scripts/secrets/dev-vault.mjs -- …)');

const cache = process.env.HADOKU_EPISODE_CACHE || join(homedir(), '.cache', 'hadoku-mobile', 'episodes');
const dir = join(cache, mediaId);
const url = (rel) => `${SITE}/watchparty/media/${mediaId.split('/').map(encodeURIComponent).join('/')}/${rel}`;

function fail(message) {
  console.error(`fetch-episode: ${message}`);
  process.exit(1);
}

/** One whole-file GET to [to]. The key goes to curl on stdin, never on a command line. */
function get(rel, to, { limit } = {}) {
  mkdirSync(dirname(to), { recursive: true });
  const part = `${to}.part`;
  rmSync(part, { force: true });
  const config = [
    `url = "${url(rel)}"`,
    `header = "X-User-Key: ${key}"`,
    'header = "X-Buffer-Ahead-Ms: 99999"',
    'user-agent = "Mozilla/5.0 hadoku-mobile-test-fetch"',
    `output = "${part}"`,
    'fail', 'silent', 'show-error', 'location',
    ...(limit ? [`limit-rate = "${RATE}"`] : []),
  ].join('\n');
  const run = spawnSync('curl', ['--config', '-'], { input: config, stdio: ['pipe', 'inherit', 'inherit'] });
  if (run.status !== 0) {
    rmSync(part, { force: true });
    fail(`${rel}: curl exited ${run.status}`);
  }
  renameSync(part, to);
}

function sha256(path) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256');
    createReadStream(path).on('data', (d) => hash.update(d)).on('end', () => resolve(hash.digest('hex'))).on('error', reject);
  });
}

// index.json first, to a temp name: it decides whether the cached episode is still production's.
const freshIndex = join(cache, `.index-${process.pid}.json`);
get('index.json', freshIndex);
const index = JSON.parse(readFileSync(freshIndex, 'utf8'));
const token = index.content_token;
if (!token) fail(`${mediaId}: index.json has no content_token`);
const cachedIndex = join(dir, 'index.json');
if (existsSync(cachedIndex) && JSON.parse(readFileSync(cachedIndex, 'utf8')).content_token !== token) {
  console.error(`fetch-episode: ${mediaId} was re-encoded on the server; dropping the cached copy`);
  rmSync(dir, { recursive: true, force: true });
}
mkdirSync(dir, { recursive: true });
renameSync(freshIndex, cachedIndex);

get('tracks.json', join(dir, 'tracks.json'));
const tracks = JSON.parse(readFileSync(join(dir, 'tracks.json'), 'utf8'));
const wanted = [
  'output_frag.mp4',
  ...(index.audio_tracks ?? []).map((a) => a.path).filter((p) => AUDIO.test(p)),
  ...(tracks.subtitles ?? []).filter((n) => SUBTITLE.test(n)).map((n) => `subs/${n}`),
];
const shas = index.part_sha256 ?? {};
if (shas['output_frag.mp4'] !== token) fail(`${mediaId}: index.json's video sha256 is not its content_token`);

for (const rel of wanted) {
  const to = join(dir, rel);
  const want = shas[rel];
  if (!want) fail(`${mediaId}: index.json records no sha256 for ${rel}`);
  if (existsSync(to) && (await sha256(to)) === want) continue;
  console.error(`fetch-episode: ${rel}`);
  get(rel, to, { limit: true });
  const got = await sha256(to);
  if (got !== want) {
    rmSync(to, { force: true });
    fail(`${rel}: sha256 ${got}, index.json says ${want}`);
  }
}
writeFileSync(join(dir, '.files'), wanted.join('\n') + '\n');
console.log(dir);
