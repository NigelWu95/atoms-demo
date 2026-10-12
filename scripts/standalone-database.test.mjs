import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer, createConnection } from 'node:net';
import { mkdtemp, writeFile, readFile, stat, symlink, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createDatabaseGate, databaseEvidence, withMismatchedInstallation } from './standalone-database.mjs';

function config(port) {
  return `STANDALONE_DB_URL=jdbc:postgresql://127.0.0.1:${port}/atoms_standalone_a
STANDALONE_DB_USER=atoms_standalone_a
STANDALONE_DB_PASSWORD=${'a'.repeat(64)}
APP_INSTANCE_ID=12345678-1234-4234-9234-123456789abc
server.address=127.0.0.1
server.port=18089
standalone.origin=http://127.0.0.1:18089
standalone.test-database=false
`;
}
async function echo(port) {
  const socket = createConnection({ host: '127.0.0.1', port });
  try {
    await once(socket, 'connect');
    socket.write('only-owned-connection');
    return (await once(socket, 'data'))[0].toString();
  } finally { socket.destroy(); }
}

test('database gate drops only its own connections, recovers, and preserves the original private config', async () => {
  const upstream = createServer(socket => socket.pipe(socket));
  upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening');
  const port = upstream.address().port;
  const work = await mkdtemp(join(tmpdir(), 'db-gate-test-'));
  const original = join(work, 'runtime.properties');
  await writeFile(original, config(port), { mode: 0o600 });
  let gate;
  try {
    gate = await createDatabaseGate(original);
    const text = await readFile(gate.configPath, 'utf8');
    const forwardedPort = Number(text.match(/postgresql:\/\/127\.0\.0\.1:(\d+)/)[1]);
    assert.notEqual(forwardedPort, port);
    assert.equal(text.replace(`:${forwardedPort}/atoms_`, `:${port}/atoms_`), config(port));
    assert.equal((await stat(gate.configPath)).mode & 0o777, 0o600);
    assert.equal(await echo(forwardedPort), 'only-owned-connection');
    const connected = createConnection({ host: '127.0.0.1', port: forwardedPort });
    await once(connected, 'connect');
    const closed = once(connected, 'close');
    gate.disconnect(); await closed;
    const refused = createConnection({ host: '127.0.0.1', port: forwardedPort });
    await once(refused, 'close');
    assert.equal(await echo(port), 'only-owned-connection');
    gate.reconnect();
    assert.equal(await echo(forwardedPort), 'only-owned-connection');
    await gate.close(); await gate.close();
    await assert.rejects(stat(gate.configPath), { code: 'ENOENT' });
    assert.equal(await readFile(original, 'utf8'), config(port));
    assert.deepEqual(await readdir(work), ['runtime.properties']);
  } finally {
    await gate?.close();
    await new Promise(resolve => upstream.close(resolve));
  }
});

test('fixture gate rejects platform DBs, public hosts, duplicate keys, linked or exposed configs before connecting', async () => {
  const work = await mkdtemp(join(tmpdir(), 'db-gate-invalid-'));
  const path = join(work, 'runtime.properties');
  for (const text of [config(5432).replaceAll('atoms_standalone_a', 'atoms_demo'),
    config(5432).replace('127.0.0.1:5432', 'example.com:5432'),
    config(5432) + 'STANDALONE_DB_URL=PRIVATE_CANARY\n', config(5432).replace('database=false', 'database=true'),
    config(5432).replace('DB_USER=atoms_standalone_a', 'DB_USER=atoms_standalone_b'), 'x'.repeat(16385)]) {
    await writeFile(path, text, { mode: 0o600 });
    await assert.rejects(createDatabaseGate(path).then(gate => gate.close()), /configuration/);
  }
  const link = join(work, 'linked.properties'); await symlink(path, link);
  await assert.rejects(createDatabaseGate(link));
  const exposed = join(work, 'exposed.properties');
  await writeFile(exposed, config(5432), { mode: 0o644 });
  await assert.rejects(createDatabaseGate(exposed), /private fixture/);
  assert.deepEqual((await readdir(work)).sort(), ['exposed.properties', 'linked.properties', 'runtime.properties']);
});

test('read-only evidence refuses application input and non-fixture targets before Docker', async () => {
  await assert.rejects(databaseEvidence('/missing.properties', "'; DROP TABLE app_state;--"), /visitor UUID/);
  const work = await mkdtemp(join(tmpdir(), 'db-evidence-invalid-'));
  const path = join(work, 'runtime.properties');
  await writeFile(path, config(5432).replaceAll('atoms_standalone_a', 'atoms_demo'), { mode: 0o600 });
  await assert.rejects(databaseEvidence(path, '12345678-1234-4234-9234-123456789abc'), /private fixture/);
});

test('installation mismatch changes only an owned private copy and cleans it after failure', async () => {
  const work = await mkdtemp(join(tmpdir(), 'installation-config-'));
  const path = join(work, 'runtime.properties');
  const original = '# Keep APP_INSTANCE_ID=12345678-1234-4234-9234-123456789abc in comments unchanged\n' + config(5432);
  await writeFile(path, original, { mode: 0o600 });
  let temporary;
  await assert.rejects(withMismatchedInstallation(path, async copy => {
    temporary = copy;
    const text = await readFile(copy, 'utf8');
    const instance = text.match(/^APP_INSTANCE_ID=(.*)$/m)[1];
    assert.match(instance, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
    assert.notEqual(instance, '12345678-1234-4234-9234-123456789abc');
    assert.equal(text.replace(instance, '12345678-1234-4234-9234-123456789abc'), original);
    assert.equal((await stat(copy)).mode & 0o777, 0o600);
    throw new Error('expected verification failure');
  }), /expected verification failure/);
  await assert.rejects(stat(temporary), { code: 'ENOENT' });
  assert.deepEqual(await readdir(work), ['runtime.properties']);
  assert.equal(await readFile(path, 'utf8'), original);
  assert.equal(await withMismatchedInstallation(path, async () => 'verified'), 'verified');
  assert.deepEqual(await readdir(work), ['runtime.properties']);
  await writeFile(path, original.replaceAll('atoms_standalone_a', 'atoms_demo'), { mode: 0o600 });
  await assert.rejects(withMismatchedInstallation(path, () => assert.fail('must reject before action')), /private fixture/);
});
