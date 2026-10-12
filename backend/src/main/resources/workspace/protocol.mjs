// Private group keeper. Tools never inherit its IPC channel.
import { spawn } from 'node:child_process';

const graceMs = Number(process.argv[2]);
if (!process.send || !process.connected || process.argv.length !== 3 || !Number.isInteger(graceMs)
    || graceMs < 1 || graceMs > 5000) process.exit(2);
process.umask(0o077);
let started = false;
let stopping = false;
let outputBytes = 0;
// Retain the group leader after tool exit; never infer ownership from a cached PID.
setInterval(() => {}, 1000);
function stop() {
  if (stopping) return;
  stopping = true;
  // Signal our CURRENT group, never an identifier that may have been reused.
  setTimeout(() => process.kill(0, 'SIGKILL'), graceMs);
  process.kill(0, 'SIGTERM');
}
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
process.on('disconnect', stop);
process.on('message', message => {
  if (message?.type === 'STOP') { stop(); return; }
  if (stopping) return;
  if (started || message?.type !== 'START' || typeof message.command !== 'string'
      || !Array.isArray(message.args) || message.args.some(arg => typeof arg !== 'string')
      || JSON.stringify(message).length > 65536) { stop(); return; }
  started = true;
  const tool = spawn(message.command, message.args, {
    cwd: message.cwd, env: message.env, shell: false, detached: false, stdio: ['ignore', 'pipe', 'pipe'],
  });
  const drain = bytes => { outputBytes = Math.min(1024 * 1024, outputBytes + bytes.length); };
  tool.stdout.on('data', drain);
  tool.stderr.on('data', drain);
  let reported = false;
  function report(code) {
    if (reported) return;
    reported = true;
    if (process.connected) process.send({ type: 'EXIT', code, outputBytes }, error => { if (error) stop(); });
    else stop();
  }
  tool.once('error', () => report(1));
  // Descendants may retain stdout: waiting for close here could deadlock cleanup.
  tool.once('exit', code => report(Number.isInteger(code) ? code : 1));
});
process.send({ type: 'READY' }, error => { if (error) stop(); });
