import { constants } from 'node:fs';
import { open, mkdtemp, writeFile, unlink, rmdir } from 'node:fs/promises';
import { dirname, isAbsolute, join } from 'node:path';
import { createServer, createConnection } from 'node:net';
import { once } from 'node:events';
import { spawnSync } from 'node:child_process';
import { toolEnvironment } from '../templates/standalone-v1/scripts/process.mjs';
import { listenerFromConfig } from './standalone-process.mjs';

const invalid = () => new Error('Expected an unchanged private fixture runtime configuration.');
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;

// Intentionally narrower than the application's Java properties parser: only provisioned A/B fixtures.
async function fixtureConfig(path) {
  if (!path || !isAbsolute(path)) throw invalid();
  await listenerFromConfig(path);
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await file.stat();
    if (!info.isFile() || info.size > 16384 || (info.mode & 0o077) !== 0) throw invalid();
    const buffer = Buffer.alloc(16385);
    const { bytesRead } = await file.read(buffer, 0, buffer.length, 0);
    if (bytesRead > 16384) throw invalid();
    const text = buffer.subarray(0, bytesRead).toString('utf8');
    const values = new Map();
    for (const line of text.split(/\r?\n/)) {
      if (!line || line.startsWith('#')) continue;
      const match = /^([A-Za-z_.-]+)=([^\r\n\\]*)$/.exec(line);
      if (!match || values.has(match[1])) throw invalid();
      values.set(match[1], match[2]);
    }
    const allowed = ['STANDALONE_DB_URL', 'STANDALONE_DB_USER', 'STANDALONE_DB_PASSWORD', 'APP_INSTANCE_ID',
      'server.address', 'server.port', 'standalone.origin', 'standalone.test-database'];
    const url = values.get('STANDALONE_DB_URL');
    const target = /^jdbc:postgresql:\/\/127\.0\.0\.1:([0-9]{1,5})\/(atoms_standalone_[ab])$/.exec(url ?? '');
    if (values.size !== allowed.length || allowed.some(key => !values.has(key)) || !target
      || Number(target[1]) < 1 || Number(target[1]) > 65535 || values.get('STANDALONE_DB_USER') !== target[2]
      || !/^[a-f0-9]{64}$/.test(values.get('STANDALONE_DB_PASSWORD')) || !uuid.test(values.get('APP_INSTANCE_ID'))
      || values.get('standalone.origin') !== `http://127.0.0.1:${values.get('server.port')}`) throw invalid();
    return { text, url, port: Number(target[1]), database: target[2], instance: values.get('APP_INSTANCE_ID') };
  } finally { await file.close(); }
}

/** Only caller-owned loopback sockets are interrupted. No PostgreSQL control/admin operation. */
export async function createDatabaseGate(config) {
  const source = await fixtureConfig(config);
  let enabled = true;
  const sockets = new Set();
  const server = createServer(client => {
    if (!enabled || sockets.size >= 64) { client.destroy(); return; }
    const upstream = createConnection({ host: '127.0.0.1', port: source.port });
    sockets.add(client); sockets.add(upstream);
    const close = () => { client.destroy(); upstream.destroy(); sockets.delete(client); sockets.delete(upstream); };
    client.on('error', close); upstream.on('error', close);
    client.on('close', close); upstream.on('close', close);
    client.pipe(upstream).pipe(client);
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  let directory, configPath, closing;
  function disconnect() { enabled = false; for (const socket of sockets) socket.destroy(); }
  function close() {
    if (!closing) closing = (async () => {
      disconnect(); await new Promise(resolve => server.close(resolve));
      // These exact paths were created by this invocation; never remove an original configuration.
      if (configPath) await unlink(configPath);
      if (directory) await rmdir(directory);
    })();
    return closing;
  }
  try {
    directory = await mkdtemp(join(dirname(config), 'db-link-'));
    const path = join(directory, 'runtime.properties');
    const url = `jdbc:postgresql://127.0.0.1:${server.address().port}/${source.database}`;
    await writeFile(path, source.text.replace('STANDALONE_DB_URL=' + source.url, 'STANDALONE_DB_URL=' + url),
      { flag: 'wx', mode: 0o600 });
    configPath = path;
    return { configPath, disconnect, reconnect: () => { if (closing) throw invalid(); enabled = true; }, close };
  } catch (error) { await close(); throw error; }
}

/** Fixed SELECTs as the application role, never admin, against the known local fixture container. */
export async function databaseEvidence(config, guest) {
  if (!uuid.test(guest)) throw new Error('Expected a fixture visitor UUID.');
  const source = await fixtureConfig(config);
  // psql's quoted variables bind UUID literals; none of the SQL shape comes from an application.
  // https://www.postgresql.org/docs/17/sql-set-transaction.html
  const sql = `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '3s';
SELECT json_build_object(
  'installationMatches', instance_id = :'instance'::uuid AND current_user = current_database()
    AND shobj_description((SELECT oid FROM pg_database WHERE datname = current_database()), 'pg_database')
      = 'atoms-standalone:runtime:' || :'instance',
  'revision', COALESCE((SELECT revision FROM standalone_app.app_state WHERE guest_id = :'guest'::uuid), 0),
  'stateRows', (SELECT count(*) FROM standalone_app.app_state WHERE guest_id = :'guest'::uuid),
  'idempotencyRows', (SELECT count(*) FROM standalone_app.idempotency_records WHERE guest_id = :'guest'::uuid)
) FROM standalone_app.installation WHERE singleton;
ROLLBACK;
`;
  const result = spawnSync('docker', ['exec', '-i', 'atoms-demo-postgres', 'psql', '-X', '-qAt', '-w',
    '-U', source.database, '-d', source.database, '-v', 'ON_ERROR_STOP=1',
    '-v', 'instance=' + source.instance, '-v', 'guest=' + guest],
  { input: sql, encoding: 'utf8', timeout: 10000, maxBuffer: 4096, env: toolEnvironment() });
  try {
    if (result.error || result.status !== 0) throw invalid();
    const value = JSON.parse(result.stdout);
    if (value.installationMatches !== true || !Number.isSafeInteger(value.revision) || value.revision < 0
      || ![0, 1].includes(value.stateRows) || !Number.isSafeInteger(value.idempotencyRows) || value.idempotencyRows < 0) throw invalid();
    return { revision: value.revision, stateRows: value.stateRows, idempotencyRows: value.idempotencyRows };
  } catch { throw new Error('Standalone read-only database evidence failed; no credentials or SQL output retained.'); }
}
