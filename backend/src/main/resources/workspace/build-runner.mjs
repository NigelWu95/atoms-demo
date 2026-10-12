// Private platform entry. Java verifies the frozen source before importing this fixed template.
import { lstat, realpath, open, link, unlink } from 'node:fs/promises';
import { basename, isAbsolute, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { ToolSupervisor, toolEnvironment } from './supervisor.mjs';

process.umask(0o077);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const errors = new Set(['BUILD_FAILED', 'BUILD_CANCELLED', 'BUILD_TIMEOUT', 'BUILD_INTERRUPTED', 'BUILD_INPUT_CHANGED']);
let supervisor;
// The owner can disappear during cleanup. Never let an EPIPE prevent settlement recording.
process.stdout.on('error', () => supervisor?.cancel('BUILD_INTERRUPTED'));
function emit(frame) { process.stdout.write(JSON.stringify(frame) + '\n'); }

async function record(task, report) {
  const pending = join(task, 'private/settlement.pending');
  const handle = await open(pending, 'wx', 0o600);
  try { await handle.writeFile(JSON.stringify(report)); await handle.sync(); }
  finally { await handle.close(); }
  // Exclusive publication: an existing execution's proof must never be overwritten.
  await link(pending, join(task, 'private/settlement.json'));
  await unlink(pending);
  const directory = await open(join(task, 'private'), 'r');
  try { await directory.sync(); } finally { await directory.close(); }
}

try {
  const [, , input, executionId, testConfig] = process.argv;
  if (process.argv.length !== 5 || !isAbsolute(input) || !uuid.test(basename(input)) || !uuid.test(executionId)
      || !isAbsolute(testConfig) || !testConfig.endsWith('.properties')) throw new Error();
  const task = await realpath(input);
  if (await realpath(fileURLToPath(import.meta.url)) !== join(task, 'private/build-runner.mjs')) throw new Error();
  for (const directory of [task, join(task, 'private'), join(task, 'work')]) {
    const info = await lstat(directory);
    if (!info.isDirectory() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 || info.uid !== process.getuid()) throw new Error();
  }
  supervisor = new ToolSupervisor({ parentInput: process.stdin });
  let buffer = '';
  process.stdin.on('data', chunk => {
    buffer += chunk.toString('ascii');
    if (buffer === 'CANCEL\n') { supervisor.cancel(); buffer = ''; }
    else if (buffer.length > 7 || !'CANCEL\n'.startsWith(buffer)) supervisor.cancel('BUILD_INTERRUPTED');
  });
  let loadedRuntimeId = null, errorCode = null;
  try {
    const { build } = await import(pathToFileURL(join(task, 'work/scripts/build.mjs')).href);
    supervisor.signal.throwIfAborted();
    const result = await build(join(task, 'work'), {
      env: { ...toolEnvironment(), STANDALONE_TEST_CONFIG: testConfig }, signal: supervisor.signal,
      run: supervisor.run.bind(supervisor),
      onStage: stage => emit({ type: 'stage', executionId, stage }),
    });
    supervisor.signal.throwIfAborted();
    if (!/^[a-f0-9]{64}$/.test(result?.loadedRuntimeId)) throw new Error();
    loadedRuntimeId = result.loadedRuntimeId;
  } catch (error) {
    const reason = supervisor.signal.reason ?? error?.code;
    errorCode = errors.has(reason) ? reason : 'BUILD_FAILED';
  }
  const settled = !supervisor.unsettled;
  if (!settled) { errorCode = 'BUILD_INTERRUPTED'; loadedRuntimeId = null; }
  // No tool can be launched after proof is published, even when the owner is already gone.
  supervisor.close();
  const report = { type: 'complete', executionId, settled, errorCode, loadedRuntimeId };
  await record(task, report);
  emit(report);
  process.exitCode = errorCode ? 1 : 0;
} catch {
  supervisor?.close();
  process.exitCode = 1;
}
