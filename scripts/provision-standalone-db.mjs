import { createHash, createHmac, pbkdf2Sync, randomBytes, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, mkdir, open, readFile, unlink } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const profiles = {
  '.local/standalone': {
    databases: ['atoms_standalone_it', 'atoms_standalone_a', 'atoms_standalone_b'],
    firstPort: 18088,
    description: 'Dedicated standalone fixture',
  },
  '.local/local-runtime': {
    databases: ['atoms_local_build_it', 'atoms_local_app_a', 'atoms_local_app_b'],
    firstPort: 19088,
    description: 'Dedicated daily local application environment',
  },
};
const outputs = ['test.properties', 'runtime-a.properties', 'runtime-b.properties'];
const uuidPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const failure = (code) => new Error(`${code}: stopped; no existing passwords or data were reset.`);

export function parseOptions(args) {
  const pairs = new Map();
  for (let i = 0; i < args.length; i += 2) {
    if (pairs.has(args[i])) throw failure('PROVISION_USAGE');
    pairs.set(args[i], args[i + 1]);
  }
  const container = pairs.get('--container');
  const adminUser = pairs.get('--admin-user');
  const port = pairs.get('--port');
  const configDir = pairs.get('--config-dir');
  if (args.length !== 8 || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}$/.test(container ?? '')
    || !/^[a-z_][a-z0-9_]{0,62}$/.test(adminUser ?? '')
    || !/^[0-9]{1,5}$/.test(port ?? '') || Number(port) < 1 || Number(port) > 65535
    || !Object.hasOwn(profiles, configDir ?? '')) throw failure('PROVISION_USAGE');
  return { container, adminUser, port: Number(port), configDir };
}

async function privateDirectory(path) {
  await mkdir(path, { mode: 0o700 }).catch((error) => {
    if (error.code !== 'EEXIST') throw failure('PROVISION_DIRECTORY_INVALID');
  });
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink()) throw failure('PROVISION_DIRECTORY_INVALID');
}

async function readPrivate(path) {
  let file;
  try {
    file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await file.stat();
    if (!info.isFile() || info.size > 16384 || (info.mode & 0o077) !== 0) throw failure('PROVISION_FILE_INVALID');
    return await file.readFile('utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw failure('PROVISION_FILE_INVALID');
  } finally {
    await file?.close();
  }
}

async function createPrivate(path, content) {
  const file = await open(path, 'wx', 0o600);
  try { await file.writeFile(content, 'utf8'); } finally { await file.close(); }
}

function validateState(state, options) {
  const { databases } = profiles[options.configDir];
  if (state.version !== 1 || state.container !== options.container || state.adminUser !== options.adminUser
    || state.port !== options.port || state.configDir !== options.configDir
    || !Array.isArray(state.installations) || state.installations.length !== 3
    || state.installations.some((entry, index) => entry?.database !== databases[index]
      || !uuidPattern.test(entry.id ?? '') || !/^[a-f0-9]{64}$/.test(entry.password ?? ''))
    || new Set(state.installations.map((entry) => entry.id)).size !== 3
    || new Set(state.installations.map((entry) => entry.password)).size !== 3) {
    throw failure('PROVISION_STATE_INVALID');
  }
}

function configText(entry, index, options) {
  const { firstPort, description } = profiles[options.configDir];
  return `# ${description}. Keep private; do not commit or copy into an export.
STANDALONE_DB_URL=jdbc:postgresql://127.0.0.1:${options.port}/${entry.database}
STANDALONE_DB_USER=${entry.database}
STANDALONE_DB_PASSWORD=${entry.password}
APP_INSTANCE_ID=${entry.id}
server.address=127.0.0.1
server.port=${firstPort + index}
standalone.origin=http://127.0.0.1:${firstPort + index}
standalone.test-database=${index === 0}
`;
}

function scramVerifier(password) {
  // Only generated ASCII hex passwords reach here (no Unicode SASLprep ambiguity).
  // https://www.postgresql.org/docs/17/catalog-pg-authid.html
  const salt = randomBytes(16);
  const salted = pbkdf2Sync(password, salt, 4096, 32, 'sha256');
  const client = createHmac('sha256', salted).update('Client Key').digest();
  const stored = createHash('sha256').update(client).digest('base64');
  const server = createHmac('sha256', salted).update('Server Key').digest('base64');
  return `SCRAM-SHA-256$4096:${salt.toString('base64')}$${stored}:${server}`;
}

/** Repository-only administrator helper; not part of exported applications. */
export async function provision(options, runSql, root = repoRoot) {
  // Revalidate even when called programmatically; no SQL accepts arbitrary identifiers.
  options = parseOptions(['--container', options.container, '--admin-user', options.adminUser,
    '--port', String(options.port), '--config-dir', options.configDir]);
  const { databases } = profiles[options.configDir];
  const directory = join(root, options.configDir);
  await privateDirectory(join(root, '.local'));
  await privateDirectory(directory);
  if (((await lstat(directory)).mode & 0o077) !== 0) throw failure('PROVISION_DIRECTORY_INVALID');
  const lockPath = join(directory, '.provision.lock');
  let lock;
  try { lock = await open(lockPath, 'wx', 0o600); } catch { throw failure('PROVISION_LOCKED'); }
  try {
    const statePath = join(directory, 'provisioning.json');
    let state;
    try {
      const saved = await readPrivate(statePath);
      state = saved === null ? { version: 1, ...options,
        installations: databases.map((database) => ({ database, id: randomUUID(), password: randomBytes(32).toString('hex') }))
      } : JSON.parse(saved);
      validateState(state, options);
      if (saved === null) await createPrivate(statePath, JSON.stringify(state) + '\n');
    } catch { throw failure('PROVISION_STATE_INVALID'); }
    const configs = state.installations.map((entry, index) => configText(entry, index, options));
    // Refuse changed/linked outputs before any database commands, not after mutation.
    const missing = [];
    for (let i = 0; i < outputs.length; i++) {
      try {
        const saved = await readPrivate(join(directory, outputs[i]));
        if (saved !== null && saved !== configs[i]) throw failure('PROVISION_CONFIG_CONFLICT');
        missing.push(saved === null);
      } catch { throw failure('PROVISION_CONFIG_CONFLICT'); }
    }
    const template = await readFile(new URL('./provision-standalone-db.sql', import.meta.url), 'utf8');
    for (const [index, entry] of state.installations.entries()) {
      const tokens = { DATABASE: entry.database, ADMIN: options.adminUser,
        MARKER: `atoms-standalone:${index === 0 ? 'test' : 'runtime'}:${entry.id}`,
        VERIFIER: scramVerifier(entry.password) };
      const sql = template.replace(/@@([A-Z]+)@@/g, (_, token) => {
        if (!Object.hasOwn(tokens, token)) throw failure('PROVISION_TEMPLATE_INVALID');
        return tokens[token];
      });
      try { await runSql(sql, options); } catch { throw failure('PROVISION_DATABASE_FAILED'); }
    }
    for (let i = 0; i < outputs.length; i++) {
      if (missing[i]) await createPrivate(join(directory, outputs[i]), configs[i]);
    }
  } finally {
    await lock.close();
    await unlink(lockPath);
  }
}

function dockerSql(sql, options) {
  // SQL (including SCRAM verifier) travels through stdin, never argv or shell.
  const result = spawnSync('docker', ['exec', '-i', options.container, 'psql', '-X', '-qAt', '-w',
    '-U', options.adminUser, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'],
  { input: sql, encoding: 'utf8', timeout: 30000, maxBuffer: 64 * 1024 });
  if (result.error || result.status !== 0 || !result.stdout.includes('STANDALONE_READY')) {
    throw failure('PROVISION_DATABASE_FAILED');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const options = parseOptions(process.argv.slice(2));
    await provision(options, dockerSql);
    console.log(`PROVISION_READY: three dedicated databases verified; private configs are in ${options.configDir}.`);
  } catch (error) {
    console.error(/^PROVISION_[A-Z_]+:/.test(error.message) ? error.message : failure('PROVISION_FAILED').message);
    process.exitCode = 1;
  }
}
