import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { ToolSupervisor, toolEnvironment } from '../backend/src/main/resources/workspace/supervisor.mjs';

const fixture = fileURLToPath(new URL('./fixtures/workspace-child.mjs', import.meta.url));
async function waitFor(action, timeout = 8000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try { const value = await action(); if (value) return value; } catch (error) { if (error.code !== 'ENOENT') throw error; }
    await delay(20);
  }
  throw new Error('Fixture deadline exceeded.');
}
function alive(pid) {
  try { process.kill(pid, 0); return true; } catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}

test('fixed environment excludes inherited secrets and interpreter overrides', () => {
  assert.deepEqual(toolEnvironment({ PATH: '/bin', JAVA_HOME: '/jdk', DEEPSEEK_API_KEY: 'canary', NODE_OPTIONS: 'bad',
    JAVA_TOOL_OPTIONS: 'bad', STANDALONE_TEST_CONFIG: '/private/test.properties', APP_DB_PASSWORD: 'canary' }),
  { PATH: '/bin', JAVA_HOME: '/jdk' });
});

test('drains large output without exposing it and rejects nonzero exit', async () => {
  const supervisor = new ToolSupervisor({ graceMs: 40, timeoutMs: 5000 });
  try {
    const result = await supervisor.run(process.execPath, ['-e', 'process.stdout.write("secret-canary".repeat(200000))']);
    assert.equal(result.settled, true);
    assert.equal(result.outputBytes, 1024 * 1024);
    assert.equal(JSON.stringify(result).includes('secret-canary'), false);
    await assert.rejects(supervisor.run(process.execPath, ['-e', 'process.exit(17)']), error => error.code === 'BUILD_FAILED' && error.settled);
  } finally { supervisor.close(); }
});

for (const reason of ['cancel', 'timeout', 'orphan']) {
  test(`${reason} confirms owned group exit and leaves unrelated process alive`, async () => {
    const directory = await mkdtemp(join(tmpdir(), 'workspace-supervision-'));
    const report = join(directory, 'pids.json');
    const foreign = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
    const supervisor = new ToolSupervisor({ graceMs: 100, timeoutMs: reason === 'timeout' ? 1500 : 8000 });
    const foreignExit = once(foreign, 'exit');
    try {
      const outcome = supervisor.run(process.execPath, [fixture, reason === 'orphan' ? 'orphan' : 'tree', report])
        .then(value => ({ value }), error => ({ error }));
      const pids = await waitFor(async () => JSON.parse(await readFile(report, 'utf8')));
      if (reason === 'cancel') supervisor.cancel('BUILD_CANCELLED');
      const result = await outcome;
      if (reason === 'orphan') assert.equal(result.value?.settled, true);
      else { assert.equal(result.error?.settled, true); assert.equal(result.error.code, reason === 'cancel' ? 'BUILD_CANCELLED' : 'BUILD_TIMEOUT'); }
      await waitFor(() => pids.every(pid => !alive(pid)));
      assert.equal(alive(foreign.pid), true);
      if (reason !== 'orphan') await assert.rejects(supervisor.run(process.execPath, ['-e', 'process.exit(0)']));
    } finally { supervisor.cancel(); supervisor.close(); foreign.kill(); await foreignExit; }
  });
}

test('force-killing the owning parent closes supervisor input and settles the real process tree', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'workspace-parent-death-'));
  const report = join(directory, 'pids.json');
  const parent = spawn(process.execPath, [fixture, 'driver', report], { stdio: ['pipe', 'ignore', 'ignore'] });
  const parentExit = once(parent, 'exit');
  try {
    const pids = await waitFor(async () => JSON.parse(await readFile(report, 'utf8')));
    parent.kill('SIGKILL');
    await parentExit;
    const result = await waitFor(async () => JSON.parse(await readFile(report + '.result', 'utf8')));
    assert.equal(result.settled, true);
    assert.equal(result.code, 'BUILD_INTERRUPTED');
    await waitFor(() => pids.every(pid => !alive(pid)));
  } finally { if (parent.exitCode === null && parent.signalCode === null) { parent.stdin.end(); await parentExit; } }
});

test('cancel before launch never starts a command', async () => {
  const supervisor = new ToolSupervisor({ graceMs: 40 });
  supervisor.cancel();
  try { await assert.rejects(supervisor.run('/no-such-command', []), error => error.code === 'BUILD_CANCELLED' && error.settled); }
  finally { supervisor.close(); }
});

test('cancel immediately after launch does not wait for initialization or start another tool', async () => {
  const supervisor = new ToolSupervisor({ graceMs: 40, timeoutMs: 5000 });
  const started = Date.now();
  const outcome = supervisor.run('/no-such-command', []).catch(error => error);
  supervisor.cancel();
  try {
    const error = await outcome;
    assert.equal(error.code, 'BUILD_CANCELLED');
    assert.equal(error.settled, true);
    assert.ok(Date.now() - started < 2000, 'pre-initialization cancellation must use configured grace');
  } finally { supervisor.close(); }
});
