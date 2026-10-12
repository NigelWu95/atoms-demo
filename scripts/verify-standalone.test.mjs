import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { materializeBundle, validateBundle } from './standalone-bundle.mjs';
import { verificationOptions } from './verify-standalone.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const allowed = ['README.md', 'backend/mvnw'];
test('verification CLI requires explicit config and output locations without override flags', () => {
  assert.deepEqual(verificationOptions(['--config-dir', '/private/config', '--output-parent', '/private/output']),
    { configDir: '/private/config', outputParent: '/private/output' });
  for (const args of [[], ['--config-dir', 'x'], ['--config-dir', 'x', '--output-parent', 'y', '--skip-tests'],
    ['--output-parent', 'x', '--config-dir', 'y']]) assert.throws(() => verificationOptions(args), /Usage/);
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
