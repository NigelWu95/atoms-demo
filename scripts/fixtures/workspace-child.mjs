// Test-only programs. None of these modes are part of the production build protocol.
import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const [mode, report] = process.argv.slice(2);
if (mode === 'tree' || mode === 'orphan') {
  const grandchild = spawn(process.execPath, ['-e', 'process.on("SIGTERM",()=>{});setInterval(()=>{},1000)'], { stdio: 'inherit' });
  process.on('SIGTERM', () => {});
  await writeFile(report, JSON.stringify([process.pid, grandchild.pid]));
  if (mode === 'orphan') process.exit(0);
  setInterval(() => {}, 1000);
} else if (mode === 'driver') {
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), 'supervised', report], { stdio: ['pipe', 'ignore', 'ignore'] });
  process.stdin.resume();
  process.stdin.on('end', () => child.stdin.end());
  child.once('exit', code => { process.exitCode = code; process.stdin.destroy(); });
} else if (mode === 'supervised') {
  const { ToolSupervisor } = await import('../../backend/src/main/resources/workspace/supervisor.mjs');
  const supervisor = new ToolSupervisor({ parentInput: process.stdin, timeoutMs: 10000, graceMs: 100 });
  try { await supervisor.run(process.execPath, [fileURLToPath(import.meta.url), 'tree', report]); }
  catch (error) { await writeFile(report + '.result', JSON.stringify({ code: error.code, settled: error.settled })); }
  finally { supervisor.close(); }
} else {
  process.exitCode = 2;
}
