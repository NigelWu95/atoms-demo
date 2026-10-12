import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { verifiedProject } from './serve-standalone.mjs';

test('browser launcher accepts only the unchanged JAR belonging to its explicit verification run', async () => {
  const work = await mkdtemp(join(tmpdir(), 'browser-evidence-'));
  const directory = await mkdtemp(join(work, 'engineering-bundle-'));
  await mkdir(join(directory, 'backend/target'), { recursive: true });
  const jar = join(directory, 'backend/target/app.jar');
  await writeFile(jar, 'reviewed fixture bytes');
  const report = { directory, baseURL: 'http://127.0.0.1:18089',
    jarSha256: createHash('sha256').update('reviewed fixture bytes').digest('hex') };
  const path = join(work, 'verification.json');
  await writeFile(path, JSON.stringify(report));
  assert.equal((await verifiedProject(path)).directory, directory);
  await writeFile(jar, 'changed');
  await assert.rejects(verifiedProject(path), /verification/);
  await writeFile(path, JSON.stringify({ ...report, directory: work }));
  await assert.rejects(verifiedProject(path), /verification/);
});
