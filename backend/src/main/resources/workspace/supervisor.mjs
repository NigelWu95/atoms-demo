import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const keeperFile = fileURLToPath(new URL('./protocol.mjs', import.meta.url));
export function toolEnvironment(source = process.env) {
  return Object.fromEntries(['PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'TERM']
    .filter(key => typeof source[key] === 'string').map(key => [key, source[key]]));
}
class BuildProcessError extends Error {
  constructor(code, settled) { super('Controlled build command did not complete.'); this.code = code; this.settled = settled; }
}

/** Only trusted, audited non-daemonizing tools. A process group is not an OS sandbox. */
export class ToolSupervisor {
  #controller = new AbortController();
  #active = false;
  #closed = false;
  #timer;
  #parent;
  #parentLost = () => this.cancel('BUILD_INTERRUPTED');
  #grace;
  #unsettled = false;

  constructor({ parentInput, timeoutMs = 600000, graceMs = 5000 } = {}) {
    if (!['darwin', 'linux'].includes(process.platform) || !Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 600000
        || !Number.isInteger(graceMs) || graceMs < 1 || graceMs > 5000) throw new Error('Invalid supervision policy.');
    this.#grace = graceMs;
    this.#timer = setTimeout(() => this.cancel('BUILD_TIMEOUT'), timeoutMs);
    this.#parent = parentInput;
    if (parentInput) {
      parentInput.on('end', this.#parentLost);
      parentInput.on('error', this.#parentLost);
      parentInput.resume();
      if (parentInput.readableEnded || parentInput.destroyed) this.#parentLost();
    }
  }
  get signal() { return this.#controller.signal; }
  get unsettled() { return this.#unsettled; }
  cancel(reason = 'BUILD_CANCELLED') {
    if (!['BUILD_CANCELLED', 'BUILD_TIMEOUT', 'BUILD_INTERRUPTED'].includes(reason)) throw new Error('Invalid stop reason.');
    if (!this.signal.aborted) this.#controller.abort(reason);
  }
  close() {
    this.#closed = true;
    clearTimeout(this.#timer);
    this.#parent?.off('end', this.#parentLost);
    this.#parent?.off('error', this.#parentLost);
    this.#parent?.pause();
    if (this.#active) this.cancel();
  }

  async run(command, args, { cwd, env = toolEnvironment() } = {}) {
    if (this.#active || this.#closed || this.signal.aborted || this.#unsettled)
      throw new BuildProcessError(this.signal.reason ?? 'BUILD_INTERRUPTED', !this.#unsettled);
    if (typeof command !== 'string' || !Array.isArray(args) || args.some(arg => typeof arg !== 'string')
        || JSON.stringify({ command, args, cwd, env }).length > 64000) throw new BuildProcessError('BUILD_FAILED', true);
    this.#active = true;
    let stopping = false, ready = false, result = null, keeperExited = false, spawned = false;
    let deadline, unresolved;
    const childEnv = toolEnvironment(env);
    if (command.endsWith('/backend/mvnw') && typeof env.STANDALONE_TEST_CONFIG === 'string')
      childEnv.STANDALONE_TEST_CONFIG = env.STANDALONE_TEST_CONFIG;
    // detached creates a new group; no cached group ID is ever used for destructive signals.
    // https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#optionsdetached
    const keeper = spawn(process.execPath, [keeperFile, String(this.#grace)], {
      env: toolEnvironment(), shell: false, detached: true, stdio: ['ignore', 'ignore', 'ignore', 'ipc'],
    });
    const stop = () => {
      if (stopping) return;
      stopping = true;
      clearTimeout(deadline);
      deadline = setTimeout(() => unresolved?.('unresolved'), this.#grace + 5000);
      if (keeper.connected) keeper.send({ type: 'STOP' }, error => { if (error && keeper.connected) keeper.disconnect(); });
    };
    const abort = () => stop();
    this.signal.addEventListener('abort', abort, { once: true });
    try {
      const exited = new Promise(resolve => {
        keeper.once('spawn', () => { spawned = true; });
        keeper.once('error', () => { if (!spawned) resolve(); else stop(); });
        keeper.once('exit', () => { keeperExited = true; resolve(); });
        keeper.on('message', message => {
          if (message?.type === 'READY' && !ready) {
            ready = true;
            if (this.signal.aborted || stopping) { stop(); return; }
            keeper.send({ type: 'START', command, args, cwd, env: childEnv }, error => { if (error) stop(); });
          } else if (message?.type === 'EXIT' && Number.isInteger(message.code)
              && Number.isInteger(message.outputBytes) && message.outputBytes >= 0 && message.outputBytes <= 1024 * 1024 && result === null) {
            result = message;
            stop();
          } else { this.cancel('BUILD_INTERRUPTED'); }
        });
      });
      // A stuck keeper must never release the execution slot as though it were clean.
      const abandoned = new Promise(resolve => {
        unresolved = resolve;
        if (!stopping) deadline = setTimeout(() => resolve('unresolved'), 600000 + this.#grace + 10000);
      });
      if (this.signal.aborted) stop();
      if (await Promise.race([exited, abandoned]) === 'unresolved') {
        this.#unsettled = true;
      } else if (spawned && keeperExited) {
        // Observation only. A reused group or EPERM is unresolved, never a kill target.
        const until = Date.now() + 3000;
        this.#unsettled = true;
        while (Date.now() < until) {
          try { process.kill(-keeper.pid, 0); }
          catch (error) { if (error.code === 'ESRCH') this.#unsettled = false; break; }
          await delay(20);
        }
        if (!stopping) this.#unsettled = true;
      }
      if (this.#unsettled) throw new BuildProcessError('BUILD_INTERRUPTED', false);
      if (this.signal.aborted) throw new BuildProcessError(this.signal.reason, true);
      if (!result || result.code !== 0) throw new BuildProcessError('BUILD_FAILED', true);
      return { settled: true, outputBytes: result.outputBytes };
    } finally {
      clearTimeout(deadline);
      this.signal.removeEventListener('abort', abort);
      if (keeper.connected) keeper.disconnect();
      this.#active = false;
    }
  }
}
