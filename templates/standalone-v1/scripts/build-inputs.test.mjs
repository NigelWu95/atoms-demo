import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { buildInputsHash, readBounded, listFiles } from './build-inputs.mjs';

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'standalone-inputs-'));
  for (const path of ['backend/pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
    'backend/src/main/java/dev/atoms/standalone/StandaloneApplication.java',
    'backend/src/main/resources/application.yml']) {
    await mkdir(dirname(join(root, path)), { recursive: true });
    await writeFile(join(root, path), `content:${path}`);
  }
  return root;
}

test('fingerprints controlled compile inputs, independent of directories and private files', async () => {
  const a = await fixture(), b = await fixture();
  const original = await buildInputsHash(a);
  assert.equal(await buildInputsHash(b), original);
  await writeFile(join(a, '.env'), 'SECRET_CANARY');
  await writeFile(join(a, 'bundle-manifest.json'), 'old provenance');
  assert.equal(await buildInputsHash(a), original);
  for (const path of ['backend/pom.xml', 'frontend/package-lock.json',
    'backend/src/main/java/dev/atoms/standalone/StandaloneApplication.java',
    'backend/src/main/resources/application.yml']) {
    await writeFile(join(a, path), 'modified');
    assert.notEqual(await buildInputsHash(a), original);
    await writeFile(join(a, path), `content:${path}`);
  }
  await writeFile(join(a, 'backend/src/main/java/dev/atoms/standalone/NewClass.java'), 'class NewClass {}');
  assert.notEqual(await buildInputsHash(a), original);
});

test('bounded tree refuses symlinks, hidden files, excessive files and unsupported input types', async () => {
  const root = await fixture();
  await assert.rejects(readBounded(root, '../outside', 10));
  await assert.rejects(readBounded(root, 'backend/pom.xml', 1));
  await assert.rejects(readBounded(root, 'backend', 100));
  await assert.rejects(listFiles(root, 'backend', 1));
  await symlink(join(root, 'backend/pom.xml'), join(root, 'backend/src/main/resources/link.yml'));
  await assert.rejects(buildInputsHash(root));
  const hidden = await fixture();
  await writeFile(join(hidden, 'backend/src/main/resources/.env'), 'SECRET_CANARY');
  await assert.rejects(buildInputsHash(hidden));
  await assert.rejects(buildInputsHash(await mkdtemp(join(tmpdir(), 'standalone-empty-'))));
});
