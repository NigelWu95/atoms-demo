import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { verifiedProject, startVerifiedRuntime } from './serve-standalone.mjs';

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
  const config = join(work, 'runtime.properties');
  await writeFile(config, 'server.address=127.0.0.1\nserver.port=18090\nstandalone.test-database=false\n');
  await assert.rejects(startVerifiedRuntime(path, config), /matching runtime config/);
  await assert.rejects(startVerifiedRuntime(path, undefined), /matching runtime config/);
  await writeFile(jar, 'changed');
  await assert.rejects(verifiedProject(path), /verification/);
  await writeFile(path, JSON.stringify({ ...report, directory: work }));
  await assert.rejects(verifiedProject(path), /verification/);
});

test('an explicit second listener must match its config and never reuses an occupied port', async () => {
  const work = await mkdtemp(join(tmpdir(), 'second-listener-'));
  const directory = await mkdtemp(join(work, 'engineering-bundle-'));
  await mkdir(join(directory, 'backend/target'), { recursive: true });
  await writeFile(join(directory, 'backend/target/app.jar'), 'reviewed fixture bytes');
  const report = join(work, 'verification.json');
  await writeFile(report, JSON.stringify({ directory, baseURL: 'http://127.0.0.1:18089',
    jarSha256: createHash('sha256').update('reviewed fixture bytes').digest('hex') }));
  const occupied = createServer();
  occupied.listen({ host: '127.0.0.1', port: 0 });
  await once(occupied, 'listening');
  try {
    const port = occupied.address().port;
    const baseURL = `http://127.0.0.1:${port}`;
    const config = join(work, 'runtime.properties');
    await writeFile(config, `server.address=127.0.0.1\nserver.port=${port}\nstandalone.test-database=false\n`);
    await assert.rejects(startVerifiedRuntime(report, config, { baseURL }), /occupied or unavailable/);
    for (const other of ['http://localhost:' + port, baseURL + '/', 'https://127.0.0.1:' + port])
      await assert.rejects(startVerifiedRuntime(report, config, { baseURL: other }), /matching runtime config/);
    assert.equal(occupied.listening, true);
  } finally {
    await new Promise((resolve, reject) => occupied.close(error => error ? reject(error) : resolve()));
  }
});
