import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, stat, writeFile, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { materializeBundle, validateBundle } from './standalone-bundle.mjs';
import { verificationOptions, verifyStandalone } from './verify-standalone.mjs';
import { extractDownloadedFixture } from './downloaded-fixture.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const allowed = ['README.md', 'backend/mvnw'];
test('verification CLI requires explicit config and output locations without override flags', () => {
  assert.deepEqual(verificationOptions(['--config-dir', '/private/config', '--output-parent', '/private/output']),
    { configDir: '/private/config', outputParent: '/private/output' });
  for (const args of [[], ['--config-dir', 'x'], ['--config-dir', 'x', '--output-parent', 'y', '--skip-tests'],
    ['--output-parent', 'x', '--config-dir', 'y']]) assert.throws(() => verificationOptions(args), /Usage/);
});
test('upgrade CLI selects only the reviewed source variant and still requires all verification gates', async () => {
  const base = ['--config-dir', '/private/config', '--output-parent', '/private/output'];
  assert.deepEqual(verificationOptions([...base, '--fixture', 'upgraded']),
    { configDir: '/private/config', outputParent: '/private/output', fixture: 'upgraded' });
  for (const extra of [['--fixture'], ['--fixture', '../source'], ['--fixture', 'todo'],
    ['--fixture', 'upgraded', '--skip-tests'], ['--skip-tests', 'upgraded']]) {
    assert.throws(() => verificationOptions([...base, ...extra]), /Usage/);
  }
  await assert.rejects(verifyStandalone({ configDir: '/nonexistent', outputParent: '/nonexistent', fixture: '../source' }),
    /Unknown reviewed fixture/);
});

test('download verification accepts only an explicit ZIP option and never combines source modes', () => {
  const base = ['--config-dir', '/private/config', '--output-parent', '/private/output'];
  assert.deepEqual(verificationOptions([...base, '--downloaded-zip', '/private/current-engineering.zip']),
    { configDir: '/private/config', outputParent: '/private/output', downloadedZip: '/private/current-engineering.zip' });
  for (const extra of [['--downloaded-zip'], ['--downloaded-zip', ''],
    ['--downloaded-zip', '/private/current-engineering.zip', '--fixture', 'upgraded']])
    assert.throws(() => verificationOptions([...base, ...extra]), /Usage/);
});

test('download verifier rejects short, oversized and symlink inputs without extracting files', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'download-guard-'));
  const work = await mkdtemp(join(tmpdir(), 'download-output-'));
  const short = join(parent, 'short.zip');
  const large = join(parent, 'large.zip');
  const link = join(parent, 'link.zip');
  await writeFile(short, Buffer.from('PK'));
  await writeFile(large, Buffer.alloc(9 * 1024 * 1024 + 1));
  await symlink(short, link);
  for (const path of [short, large, link])
    await assert.rejects(extractDownloadedFixture(path, work), /Only the current reviewed todo fixture ZIP/);
  assert.deepEqual(await readdir(work), []);
});
function fixture() {
  const files = allowed.map(path => ({ path, content: Buffer.from(path).toString('base64') }));
  const manifest = { bundleFormatVersion: 1, dataMode: 'INITIAL_ONLY', files: files.map(file => ({
    path: file.path, sha256: hash(Buffer.from(file.content, 'base64')), size: Buffer.from(file.content, 'base64').length,
  })), sourceContentHash: 'ab'.repeat(32), sourceRuntime: 'web-static-v1',
  sourceVersionId: '37b594a7-b7af-4b16-b823-f65eacb467ae', templateId: 'vue-spring-postgres', templateVersion: '1.0.0' };
  const bundleId = hash(JSON.stringify(manifest));
  const full = { ...manifest, bundleId };
  files.push({ path: 'bundle-manifest.json', content: Buffer.from(JSON.stringify(full)).toString('base64') });
  return { bundleId, files };
}

test('real file bytes and manifest identities are checked before new-directory materialization', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'bundle-materialization-'));
  const first = await materializeBundle(fixture(), allowed, parent);
  const second = await materializeBundle(fixture(), allowed, parent);
  assert.notEqual(first.directory, second.directory);
  assert.equal(await readFile(join(first.directory, 'README.md'), 'utf8'), 'README.md');
  assert.equal((await stat(join(first.directory, 'backend/mvnw'))).mode & 0o777, 0o755);
  assert.equal(first.fileCount, 3);
  assert.equal(first.bundleId, fixture().bundleId);
});

test('rejects content or identity tampering, missing and additional files without writing anything', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'bundle-rejection-'));
  const changes = [b => { b.bundleId = '00'.repeat(32); }, b => { b.files.pop(); },
    b => { b.files[0].content = Buffer.from('tampered').toString('base64'); },
    b => { b.files.push(b.files[0]); }, b => { b.files.push({ path: '.env', content: '' }); },
    b => { b.files[0].content += '\n'; }, b => { b.files[0].mode = 'symlink'; }];
  for (const change of changes) {
    const bundle = fixture(); change(bundle);
    await assert.rejects(materializeBundle(bundle, allowed, parent), /Invalid engineering bundle/);
  }
  assert.deepEqual(await readdir(parent), []);
});

test('rejects unsafe or colliding paths even in an erroneous trusted allowlist and enforces budgets', () => {
  for (const path of ['../escape', '/absolute', 'a\\b', 'a//b', '.env', 'a/../b', 'a\0b']) {
    const bundle = fixture(); bundle.files[0].path = path;
    assert.throws(() => validateBundle(bundle, [path, allowed[1]]), /Invalid engineering bundle/);
  }
  const bundle = fixture(); bundle.files[0].content = Buffer.alloc(2 * 1024 * 1024 + 1).toString('base64');
  assert.throws(() => validateBundle(bundle, allowed), /Invalid engineering bundle/);
  assert.throws(() => validateBundle({ bundleId: '00'.repeat(32), files: Array(129).fill({}) }, allowed), /Invalid engineering bundle/);
});

test('rejects a coherently replaced manifest ID and malformed transport entries', () => {
  const bundle = fixture();
  const entry = bundle.files.at(-1);
  const manifest = JSON.parse(Buffer.from(entry.content, 'base64'));
  bundle.bundleId = manifest.bundleId = '00'.repeat(32);
  entry.content = Buffer.from(JSON.stringify(manifest)).toString('base64');
  assert.throws(() => validateBundle(bundle, allowed), /Invalid engineering bundle/);
  for (const malformed of [null, 1, 'README.md']) {
    const invalid = fixture(); invalid.files[0] = malformed;
    assert.throws(() => validateBundle(invalid, allowed), /Invalid engineering bundle/);
  }
});
