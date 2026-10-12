import { spawn } from 'node:child_process';

export class CommandFailure extends Error {
  constructor(code = 1, signal = null) {
    super('Standalone command failed.');
    this.code = code;
    this.signal = signal;
  }
}

export function toolEnvironment(source = process.env) {
  const keys = ['PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'TERM'];
  return Object.fromEntries(keys.filter(key => source[key] !== undefined).map(key => [key, source[key]]));
}

// POSIX process groups let Ctrl-C/TERM reach npm/Maven descendants, not only the wrapper.
// Never unref: the CLI owns and waits for its job; a 5-second cancellation grace is bounded.
// https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#optionsdetached
export function runCommand(command, args, { cwd, env = toolEnvironment(), signal } = {}) {
  if (!['darwin', 'linux'].includes(process.platform)) return Promise.reject(new CommandFailure());
  if (signal?.aborted) return Promise.reject(new CommandFailure(1, signal.reason));
  return new Promise((resolve, reject) => {
    let cancelled = null;
    let timer;
    let failed = false;
    const child = spawn(command, args, { cwd, env, stdio: 'inherit', shell: false, detached: true });
    function killGroup(value) {
      if (!child.pid) return;
      try { process.kill(-child.pid, value); }
      catch (error) { if (error.code !== 'ESRCH') child.kill(value); }
    }
    function cancel() {
      cancelled = signal.reason === 'SIGINT' ? 'SIGINT' : 'SIGTERM';
      killGroup(cancelled);
      timer = setTimeout(() => killGroup('SIGKILL'), 5000);
    }
    signal?.addEventListener('abort', cancel, { once: true });
    child.once('error', () => { failed = true; });
    child.once('close', (code, childSignal) => {
      signal?.removeEventListener('abort', cancel);
      clearTimeout(timer);
      // A wrapper may exit before a grandchild. All group members belong to this invocation.
      killGroup('SIGKILL');
      if (cancelled || childSignal || failed || code !== 0)
        reject(new CommandFailure(failed ? 1 : (code || 1), cancelled || childSignal));
      else resolve();
    });
  });
}

export async function runCli(action, failureMessage) {
  const controller = new AbortController();
  const interrupt = () => controller.abort('SIGINT');
  const terminate = () => controller.abort('SIGTERM');
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', terminate);
  let failure;
  try { await action(controller.signal); }
  catch (error) { failure = error; console.error(failureMessage); }
  finally { process.off('SIGINT', interrupt); process.off('SIGTERM', terminate); }
  const signal = controller.signal.aborted ? controller.signal.reason : failure?.signal;
  if (signal) process.kill(process.pid, signal);
  else if (failure) process.exitCode = failure instanceof CommandFailure ? failure.code : 1;
}
