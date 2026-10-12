import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { runCommand, toolEnvironment } from './process.mjs';

test('commands preserve argv without shell interpretation and propagate failures', async () => {
  const literal = 'value with spaces; $(exit 22)';
  await runCommand(process.execPath, ['-e', `process.exit(process.argv[1] === ${JSON.stringify(literal)} ? 0 : 22)`, literal]);
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(23)']), error => error.code === 23);
  await assert.rejects(runCommand('/definitely/missing/standalone-tool', []), error => error.code === 1);
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.kill(process.pid,"SIGTERM")']),
    error => error.signal === 'SIGTERM');
});

test('only tool environment is inherited; runtime credentials and override flags never reach children', () => {
  assert.deepEqual(toolEnvironment({ PATH: '/bin', JAVA_HOME: '/jdk', HOME: '/operator',
    NODE_OPTIONS: '--import=evil', JAVA_TOOL_OPTIONS: 'evil', MAVEN_ARGS: '-DskipTests',
    STANDALONE_DB_PASSWORD: 'PRIVATE_CANARY', DEEPSEEK_API_KEY: 'PRIVATE_CANARY',
    STANDALONE_TEST_CONFIG: '/private/test.properties' }), { PATH: '/bin', JAVA_HOME: '/jdk', HOME: '/operator' });
});

test('cancellation terminates this job and its descendants, never starts an already-cancelled command', async () => {
  const root = await mkdtemp(join(tmpdir(), 'standalone-process-'));
  const pidFile = join(root, 'child.pid');
  const abort = new AbortController();
  const childSource = 'setInterval(()=>{},1000)';
  const result = runCommand(process.execPath, ['--input-type=module', '-e', `
    import {spawn} from 'node:child_process'; import {writeFileSync} from 'node:fs';
    const child=spawn(process.execPath,['-e',${JSON.stringify(childSource)}],{stdio:'ignore'});
    child.on('spawn',()=>writeFileSync(${JSON.stringify(pidFile)},String(child.pid)));
    setInterval(()=>{},1000);
  `], { signal: abort.signal });
  // Attach rejection handling immediately; do not leave failed children or unhandled promises.
  const rejection = assert.rejects(result, error => error.signal === 'SIGTERM');
  let pid;
  try {
    for (let attempt = 0; attempt < 100; attempt++) {
      try { pid = Number(await readFile(pidFile, 'utf8')); break; } catch { await delay(20); }
    }
    assert.ok(pid > 0, 'owned descendant started');
  } finally { abort.abort('SIGTERM'); await rejection; }
  for (let attempt = 0; attempt < 100; attempt++) {
    try { process.kill(pid, 0); } catch (error) { assert.equal(error.code, 'ESRCH'); pid = null; break; }
    await delay(20);
  }
  assert.equal(pid, null, 'owned descendant exited');
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(99)'], { signal: abort.signal }),
    error => error.signal === 'SIGTERM');
});
