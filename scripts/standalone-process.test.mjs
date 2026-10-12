import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { assertFree, listenerFromConfig, startOwned } from './standalone-process.mjs';

async function listener() {
  const socket = createServer(); socket.listen(0, '127.0.0.1'); await once(socket, 'listening');
  const url = `http://127.0.0.1:${socket.address().port}`;
  return { url, close: () => new Promise(resolve => socket.close(resolve)) };
}
test('occupied ports reject without reusing or stopping another listener', async () => {
  const foreign = await listener();
  try {
    await assert.rejects(startOwned(process.execPath, ['-e', 'process.exit(17)'], { baseURL: foreign.url }), /occupied/);
    await assert.rejects(assertFree(foreign.url), /occupied/);
  } finally { await foreign.close(); }
});
test('owns a real server until stop, then releases its port; repeated stop is harmless', async () => {
  const free = await listener(); await free.close();
  const code = `require('node:http').createServer((q,r)=>r.end('{"status":"UP"}')).listen(${new URL(free.url).port},'127.0.0.1')`;
  const owned = await startOwned(process.execPath, ['-e', code], { baseURL: free.url, timeoutMs: 5000 });
  try { assert.equal((await fetch(free.url)).status, 200); }
  finally { await owned.stop(); }
  await owned.stop(); await assertFree(free.url);
});
test('early child failure and readiness deadline never produce a ready server', async () => {
  const free = await listener(); await free.close();
  await assert.rejects(startOwned(process.execPath, ['-e', 'process.exit(17)'], { baseURL: free.url, timeoutMs: 5000 }), /exited/);
  await assert.rejects(startOwned(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { baseURL: free.url, timeoutMs: 100 }), /readiness/);
  await assertFree(free.url);
});
test('only explicit bounded loopback runtime configs select listeners; secrets are not returned', async () => {
  const root = await mkdtemp(join(tmpdir(), 'runtime-listener-'));
  const path = join(root, 'runtime.properties');
  const valid = 'server.address=127.0.0.1\nserver.port=18089\nstandalone.test-database=false\nSTANDALONE_DB_PASSWORD=PRIVATE_CANARY\n';
  await writeFile(path, valid);
  assert.equal(await listenerFromConfig(path), 'http://127.0.0.1:18089');
  for (const contents of [valid.replace('127.0.0.1', '0.0.0.0'), valid.replace('18089', '0'),
    valid.replace('database=false', 'database=true'), valid + 'server.port=18090\n', 'x'.repeat(16385)]) {
    await writeFile(path, contents); await assert.rejects(listenerFromConfig(path), /configuration/);
  }
});
