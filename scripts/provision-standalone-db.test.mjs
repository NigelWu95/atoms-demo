import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { parseOptions, provision } from './provision-standalone-db.mjs';

const args = ['--container', 'postgres-fixture', '--admin-user', 'postgres', '--port', '5432',
  '--config-dir', '.local/standalone'];

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'standalone-provision-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return { root, directory: join(root, '.local/standalone'), options: parseOptions(args) };
}

test('requires an explicit bounded connection and ignored config directory', () => {
  assert.equal(parseOptions(args).container, 'postgres-fixture');
  for (const input of [[], args.slice(2), [...args, '--force'], [...args, '--port', '1234'],
    args.map((v) => v === '5432' ? '0' : v), args.map((v) => v === '5432' ? '65536' : v),
    args.map((v) => v === 'postgres-fixture' ? '--privileged' : v),
    args.map((v) => v === 'postgres' ? "admin';SELECT 1;--" : v),
    args.map((v) => v === '.local/standalone' ? '/tmp/credentials' : v),
    args.map((v) => v === '.local/standalone' ? '.local/../credentials' : v)]) {
    assert.throws(() => parseOptions(input), /^Error: PROVISION_USAGE:/);
  }
});

test('prepares three independent private configs with stable credentials and identities', async (t) => {
  const { root, directory, options } = await fixture(t);
  const calls = [];
  const runSql = async (sql) => { calls.push(sql); };
  await provision(options, runSql, root);
  const names = ['test.properties', 'runtime-a.properties', 'runtime-b.properties'];
  const files = await Promise.all(names.map((name) => readFile(join(directory, name), 'utf8')));
  const passwords = files.map((text) => text.match(/^STANDALONE_DB_PASSWORD=([a-f0-9]{64})$/m)?.[1]);
  const ids = files.map((text) => text.match(/^APP_INSTANCE_ID=([a-f0-9-]{36})$/m)?.[1]);
  assert.ok(passwords.every(Boolean));
  assert.ok(ids.every(Boolean));
  assert.equal(new Set(passwords).size, 3);
  assert.equal(new Set(ids).size, 3);
  assert.match(files[0], /standalone.test-database=true/);
  assert.match(files[1], /standalone.test-database=false/);
  assert.match(files[2], /standalone.test-database=false/);
  for (const name of [...names, 'provisioning.json']) {
    assert.equal((await stat(join(directory, name))).mode & 0o777, 0o600);
  }
  assert.equal((await stat(directory)).mode & 0o777, 0o700);
  assert.equal(calls.length, 3);
  assert.ok(calls.every((sql) => !passwords.some((password) => sql.includes(password))));
  assert.ok(calls.every((sql) => sql.includes('SCRAM-SHA-256$4096:')));
  assert.ok(calls.every((sql) => !sql.includes('@@')));
  await provision(options, runSql, root);
  assert.deepEqual(await Promise.all(names.map((name) => readFile(join(directory, name), 'utf8'))), files);
  assert.equal(calls.length, 6);
});

test('prepares daily local runtime databases without touching verification configs or databases', async (t) => {
  const { root, directory, options } = await fixture(t);
  await provision(options, async () => {}, root);
  const before = await readFile(join(directory, 'provisioning.json'), 'utf8');
  const local = parseOptions(args.map(value => value === '.local/standalone' ? '.local/local-runtime' : value));
  const calls = [];
  await provision(local, async sql => { calls.push(sql); }, root);
  const localDir = join(root, '.local/local-runtime');
  const testConfig = await readFile(join(localDir, 'test.properties'), 'utf8');
  const runtimeA = await readFile(join(localDir, 'runtime-a.properties'), 'utf8');
  const runtimeB = await readFile(join(localDir, 'runtime-b.properties'), 'utf8');
  assert.match(testConfig, /\/atoms_local_build_it\n/);
  assert.match(runtimeA, /\/atoms_local_app_a\n/);
  assert.match(runtimeB, /\/atoms_local_app_b\n/);
  assert.match(runtimeA, /server.port=19089\n/);
  assert.match(runtimeB, /server.port=19090\n/);
  assert.match(testConfig, /standalone.test-database=true/);
  assert.match(runtimeA, /standalone.test-database=false/);
  assert.equal(calls.length, 3);
  assert.ok(calls.every(sql => !/atoms_standalone_|atoms_demo/.test(sql)));
  assert.equal(await readFile(join(directory, 'provisioning.json'), 'utf8'), before);
  await provision(local, async () => {}, root);
  assert.equal(await readFile(join(localDir, 'runtime-a.properties'), 'utf8'), runtimeA);
  assert.equal((await stat(join(localDir, 'runtime-a.properties'))).mode & 0o777, 0o600);
});

test('database failures are redacted and retry preserves installation credentials', async (t) => {
  const { root, directory, options } = await fixture(t);
  await assert.rejects(provision(options, async () => { throw new Error('private-sql-canary'); }, root),
    /^Error: PROVISION_DATABASE_FAILED:/);
  const before = await readFile(join(directory, 'provisioning.json'), 'utf8');
  await provision(options, async () => {}, root);
  assert.equal(await readFile(join(directory, 'provisioning.json'), 'utf8'), before);
});

test('rejects a copied verification state in the daily profile before executing SQL', async (t) => {
  const { root, directory, options } = await fixture(t);
  await provision(options, async () => {}, root);
  const local = parseOptions(args.map(value => value === '.local/standalone' ? '.local/local-runtime' : value));
  await provision(local, async () => {}, root);
  await writeFile(join(root, '.local/local-runtime/provisioning.json'),
    await readFile(join(directory, 'provisioning.json')));
  let calls = 0;
  await assert.rejects(provision(local, async () => { calls++; }, root), /PROVISION_STATE_INVALID/);
  assert.equal(calls, 0);
});

test('changed connection or private state is rejected before any database commands', async (t) => {
  const { root, directory, options } = await fixture(t);
  await provision(options, async () => {}, root);
  let calls = 0;
  const runSql = async () => { calls++; };
  await assert.rejects(provision({ ...options, port: 5433 }, runSql, root), /PROVISION_STATE_INVALID/);
  const statePath = join(directory, 'provisioning.json');
  const state = JSON.parse(await readFile(statePath, 'utf8'));
  state.installations[0].database = 'atoms_demo';
  await writeFile(statePath, JSON.stringify(state));
  await assert.rejects(provision(options, runSql, root), /PROVISION_STATE_INVALID/);
  assert.equal(calls, 0);
});

test('never overwrites existing output or follows config links', async (t) => {
  const { root, directory, options } = await fixture(t);
  await provision(options, async () => {}, root);
  const file = join(directory, 'test.properties');
  await writeFile(file, 'private-existing-canary');
  await assert.rejects(provision(options, async () => {}, root), /PROVISION_CONFIG_CONFLICT/);
  assert.equal(await readFile(file, 'utf8'), 'private-existing-canary');
  await rm(file);
  await symlink(join(directory, 'provisioning.json'), file);
  await assert.rejects(provision(options, async () => {}, root), /PROVISION_CONFIG_CONFLICT/);
});

test('refuses symlinked output directories and simultaneous provisioning', async (t) => {
  const { root, directory, options } = await fixture(t);
  let release;
  const ready = new Promise((resolve) => { release = resolve; });
  let entered;
  const started = new Promise((resolve) => { entered = resolve; });
  const first = provision(options, async () => { entered(); await ready; }, root);
  await started;
  try {
    await assert.rejects(provision(options, async () => {}, root), /PROVISION_LOCKED/);
  } finally {
    release();
    await first;
  }
  await rm(directory, { recursive: true });
  await symlink(tmpdir(), directory);
  await assert.rejects(provision(options, async () => {}, root), /PROVISION_DIRECTORY_INVALID/);
});
