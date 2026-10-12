import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, copyFile, writeFile, readFile, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

async function fixture(body, timeoutMs = 600000) {
  const root = await mkdtemp(join(tmpdir(), 'workspace-runner-'));
  const task = join(root, randomUUID()), executionId = randomUUID();
  await mkdir(join(task, 'private'), { recursive: true, mode: 0o700 });
  await mkdir(join(task, 'work/scripts'), { recursive: true, mode: 0o700 });
  for (const name of ['build-runner.mjs', 'supervisor.mjs', 'protocol.mjs'])
    await copyFile(new URL(`../backend/src/main/resources/workspace/${name}`, import.meta.url), join(task, 'private', name));
  await writeFile(join(task, 'work/scripts/build.mjs'), `export async function build(root, options) { ${body} }`, { mode: 0o600 });
  const config = join(task, 'private/test.properties');
  await writeFile(config, 'TEST_ONLY_CANARY', { mode: 0o600 });
  const child = spawn(process.execPath, [join(task, 'private/build-runner.mjs'), task, executionId, config, String(Date.now()+timeoutMs)],
    { stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '', errors = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { errors += chunk; });
  const exited = once(child, 'exit');
  return { task, executionId, child, async result() {
    const [code] = await exited;
    assert.equal(errors, '');
    assert.equal(output.includes('CANARY'), false);
    return { code, frames: output.trim().split('\n').map(line => JSON.parse(line)),
      report: JSON.parse(await readFile(join(task, 'private/settlement.json'), 'utf8')) };
  } };
}

test('trusted runner shares the pipeline and writes a private execution-bound settlement', async () => {
  const f = await fixture(`options.onStage('INSTALLING');
    await options.run(process.execPath, ['-e', 'console.log("TOOL_CANARY")'], { cwd: root, env: options.env });
    options.onStage('VERIFYING'); return { loadedRuntimeId: '${'a'.repeat(64)}' };`);
  const result = await f.result();
  assert.equal(result.code, 0);
  assert.deepEqual(result.frames.map(frame => frame.type), ['stage', 'stage', 'complete']);
  assert.deepEqual(result.report, result.frames.at(-1));
  assert.equal(result.report.executionId, f.executionId);
  assert.equal(result.report.settled, true);
  assert.equal(result.report.loadedRuntimeId, 'a'.repeat(64));
  assert.equal((await stat(join(f.task, 'private/settlement.json'))).mode & 0o777, 0o600);
  assert.equal((await stat(join(f.task, 'private/settlement.json'))).nlink, 1);
});

for (const lost of [false, true]) test(`runner settles a live tool after ${lost ? 'parent EOF' : 'cancellation'}`, async () => {
  const f = await fixture(`options.onStage('INSTALLING');
    await options.run(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { cwd: root, env: options.env });
    throw new Error('MUST_NOT_REACH_CANARY');`);
  await once(f.child.stdout, 'data');
  if (lost) f.child.stdin.end(); else f.child.stdin.write('CANCEL\n');
  const { code, report } = await f.result();
  assert.equal(code, 1);
  assert.equal(report.settled, true);
  assert.equal(report.errorCode, lost ? 'BUILD_INTERRUPTED' : 'BUILD_CANCELLED');
  assert.equal(report.loadedRuntimeId, null);
});

test('runner sanitizes thrown errors and rejects forged runtime identity', async () => {
  for (const body of ['throw new Error("SECRET_CANARY")', 'return { loadedRuntimeId: "FORGED_CANARY" }']) {
    const f = await fixture(body);
    const { code, report } = await f.result();
    assert.equal(code, 1);
    assert.equal(report.errorCode, 'BUILD_FAILED');
    assert.equal(report.settled, true);
  }
});

test('runner enforces the owners remaining deadline without waiting for Java cancellation', async () => {
  const began=Date.now();
  const f=await fixture(`await options.run(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {cwd:root,env:options.env});
    return {loadedRuntimeId:'${'a'.repeat(64)}'};`,500);
  const {report}=await f.result();
  assert.equal(report.settled,true); assert.equal(report.errorCode,'BUILD_TIMEOUT');
  assert.ok(Date.now()-began<15000);
});
