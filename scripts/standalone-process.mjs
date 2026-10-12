import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { runCommand, toolEnvironment } from '../templates/standalone-v1/scripts/process.mjs';

export async function listenerFromConfig(path) {
  const invalid = () => { throw new Error('Invalid explicit loopback runtime configuration.'); };
  if (!isAbsolute(path) || !path.endsWith('.properties') || /[\x00-\x1f\x7f,;]/.test(path)) invalid();
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > 16384) invalid();
    const buffer = Buffer.alloc(16385);
    const { bytesRead } = await file.read(buffer, 0, buffer.length, 0);
    if (bytesRead > 16384) invalid();
    const lines = buffer.subarray(0, bytesRead).toString('utf8').split(/\r?\n/);
    const value = key => {
      const matches = lines.filter(line => line.startsWith(key + '='));
      if (matches.length !== 1) invalid();
      return matches[0].slice(key.length + 1).trim();
    };
    const port = value('server.port');
    if (value('server.address') !== '127.0.0.1' || value('standalone.test-database') !== 'false'
      || !/^[0-9]{4,5}$/.test(port) || Number(port) < 1024 || Number(port) > 65535) invalid();
    return `http://127.0.0.1:${port}`;
  } finally { await file.close(); }
}

export async function assertFree(baseURL) {
  const url = new URL(baseURL);
  if (url.origin !== baseURL || url.hostname !== '127.0.0.1' || url.protocol !== 'http:' || !url.port)
    throw new Error('Only explicit loopback listeners are allowed.');
  // https://nodejs.org/download/release/v24.20.0/docs/api/net.html#serverlistenoptions-callback
  await new Promise((resolve, reject) => {
    const probe = createServer();
    probe.once('error', () => reject(new Error('Standalone listener is occupied or unavailable; no process was stopped.')));
    probe.listen({ port: Number(url.port), host: '127.0.0.1', exclusive: true }, () => probe.close(resolve));
  });
}

/** Starts only a caller-owned command. Never locates or kills other processes by port. */
export async function startOwned(command, args, { baseURL, cwd, env = toolEnvironment(), signal, timeoutMs = 120000 }) {
  await assertFree(baseURL);
  signal?.throwIfAborted();
  const controller = new AbortController();
  const cancel = () => controller.abort('SIGTERM');
  signal?.addEventListener('abort', cancel, { once: true });
  let exited = false;
  const done = runCommand(command, args, { cwd, env, signal: controller.signal }).then(
    () => { exited = true; return null; }, error => { exited = true; return error; });
  let stopped;
  function stop() {
    if (!stopped) stopped = (async () => {
      cancel(); await done; signal?.removeEventListener('abort', cancel); await assertFree(baseURL);
    })();
    return stopped;
  }
  try {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (exited || signal?.aborted) throw new Error('Owned standalone process exited before readiness.');
      try {
        const response = await fetch(baseURL + '/api/health', { signal: AbortSignal.timeout(1000), redirect: 'error' });
        if (response.status === 200 && (await response.json()).status === 'UP' && !exited) return { stop, done };
      } catch { /* Connection refused while the owned process starts is expected. */ }
      await delay(100);
    }
    throw new Error('Owned standalone process did not reach readiness.');
  } catch (error) { await stop(); throw error; }
}
