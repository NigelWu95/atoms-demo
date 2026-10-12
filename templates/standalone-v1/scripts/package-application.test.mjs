import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, symlink, utimes, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { packageApplication, runtimeId, RESOURCE_PATHS } from './package-application.mjs';

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'standalone-package-test-'));
  await mkdir(join(root, 'application'));
  await mkdir(join(root, 'backend/src/main/resources/runtime'), { recursive: true });
  for (const path of RESOURCE_PATHS) await writeFile(source(root, path), `content:${path}`);
  for (const path of ['backend/pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
    'backend/src/main/java/dev/atoms/standalone/StandaloneApplication.java',
    'backend/src/main/resources/application.yml', 'frontend/dist/index.html', 'frontend/dist/assets/app.js']) {
    await mkdir(dirname(join(root, path)), { recursive: true });
    await writeFile(join(root, path), `content:${path}`);
  }
  return root;
}
const source = (root, path) => join(root, path.startsWith('application/') ? path : `backend/src/main/resources/${path}`);

test('same bytes in different directories/times produce the same packaged identity', async () => {
  const a = await fixture(), b = await fixture();
  await utimes(source(b, 'application/main.js'), 1, 1);
  const first = await packageApplication(a, tmpdir()), second = await packageApplication(b, tmpdir());
  assert.equal(first.loadedRuntimeId, second.loadedRuntimeId);
  assert.match(first.loadedRuntimeId, /^[a-f0-9]{64}$/);
  assert.equal(await readFile(join(first.directory, 'runtime/content.sha256'), 'utf8'), first.loadedRuntimeId + '\n');
  for (const path of RESOURCE_PATHS) assert.deepEqual(await readFile(join(first.directory, path)), await readFile(source(a, path)));
});

test('each actual application/runtime file changes the identity; historical provenance does not', async () => {
  const root = await fixture();
  const original = await packageApplication(root, tmpdir());
  await writeFile(join(root, 'bundle-manifest.json'), '{"sourceContentHash":"historical"}');
  assert.equal((await packageApplication(root, tmpdir())).loadedRuntimeId, original.loadedRuntimeId);
  for (const path of RESOURCE_PATHS) {
    await writeFile(source(root, path), `modified:${path}`);
    assert.notEqual((await packageApplication(root, tmpdir())).loadedRuntimeId, original.loadedRuntimeId);
    await writeFile(source(root, path), `content:${path}`);
  }
});

test('missing, symlinked, oversized and malformed UTF-8 resources fail closed', async () => {
  const empty = await mkdtemp(join(tmpdir(), 'standalone-missing-test-'));
  await assert.rejects(packageApplication(empty, tmpdir()));
  for (const content of [Buffer.alloc(300 * 1024), Buffer.from([0xc3, 0x28]), Buffer.from('x\0y')]) {
    const root = await fixture(); await writeFile(source(root, 'application/main.js'), content);
    await assert.rejects(packageApplication(root, tmpdir()));
  }
  const linked = await mkdtemp(join(tmpdir(), 'standalone-symlink-test-'));
  const original = await fixture();
  await symlink(join(original, 'application'), join(linked, 'application'));
  await assert.rejects(packageApplication(linked, tmpdir()));
});

test('runtime identity has a fixed cross-language framing, order and input allowlist', () => {
  const files = Object.fromEntries(RESOURCE_PATHS.map(path => [path, Buffer.from(`content:${path}`)]));
  files['runtime/build.txt'] = Buffer.from('0'.repeat(64) + '\nstatic/assets/app.js\nstatic/index.html\n');
  files['static/assets/app.js'] = Buffer.from('content:static/assets/app.js');
  files['static/index.html'] = Buffer.from('content:static/index.html');
  assert.equal(runtimeId(files), '0fa33afd622b8bc672fff7d64aff4a41a67fdebf016bfca4fa4cc04d3c324fbf');
  assert.equal(runtimeId(Object.fromEntries(Object.entries(files).reverse())), runtimeId(files));
  assert.throws(() => runtimeId({ ...files, '../escape': Buffer.from('x') }));
});

test('frontend bytes and controlled source inputs participate in the final identity', async () => {
  const root = await fixture();
  const first = await packageApplication(root, tmpdir());
  for (const path of ['frontend/dist/assets/app.js', 'frontend/dist/index.html', 'backend/pom.xml']) {
    await writeFile(join(root, path), 'changed');
    assert.notEqual((await packageApplication(root, tmpdir())).loadedRuntimeId, first.loadedRuntimeId);
    await writeFile(join(root, path), `content:${path}`);
  }
  const files = {};
  for (const path of [...RESOURCE_PATHS, 'runtime/build.txt', 'static/assets/app.js', 'static/index.html'])
    files[path] = await readFile(join(first.directory, path));
  assert.equal(runtimeId(files), first.loadedRuntimeId);
  assert.throws(() => runtimeId({ ...files, 'static/unlisted.js': Buffer.from('unlisted') }));
  const malformed = { ...files, 'runtime/build.txt': Buffer.from('0'.repeat(64) + '\n../escape\n') };
  assert.throws(() => runtimeId(malformed));
});

test('missing or unexpected frontend outputs cannot become successful packages', async () => {
  for (const path of ['frontend/dist/index.html', 'frontend/dist/assets/app.js']) {
    const root = await fixture(); await unlink(join(root, path));
    await assert.rejects(packageApplication(root, tmpdir()));
  }
  for (const path of ['frontend/dist/.env', 'frontend/dist/assets/app.js.map', 'frontend/dist/secrets.properties']) {
    const root = await fixture(); await writeFile(join(root, path), 'SECRET_CANARY');
    await assert.rejects(packageApplication(root, tmpdir()));
  }
  const root = await fixture();
  await symlink(join(root, 'frontend/package.json'), join(root, 'frontend/dist/assets/link.js'));
  await assert.rejects(packageApplication(root, tmpdir()));
});
