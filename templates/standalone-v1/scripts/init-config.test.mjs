import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = fileURLToPath(new URL('./init-config.mjs', import.meta.url));
const uuid = /APP_INSTANCE_ID=([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/;

function directory(t) {
  const path = mkdtempSync(join(tmpdir(), 'standalone-config-test-'));
  t.after(() => rmSync(path, { recursive: true, force: true }));
  return path;
}

function run(...args) {
  return spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', timeout: 10_000 });
}

test('creates private editable configuration with a different stable ID per installation', (t) => {
  const dir = directory(t);
  const first = join(dir, 'first config.properties');
  const second = join(dir, 'second.properties');
  const created = run('--output', first);
  assert.equal(created.status, 0, created.stderr);
  assert.match(created.stdout, /^CONFIG_CREATED:/);
  assert.equal(created.stderr, '');
  const content = readFileSync(first, 'utf8');
  assert.match(content, uuid);
  assert.match(content, /STANDALONE_DB_URL=REPLACE_WITH_DATABASE_URL/);
  assert.match(content, /STANDALONE_DB_USER=REPLACE_WITH_DATABASE_USER/);
  assert.match(content, /STANDALONE_DB_PASSWORD=REPLACE_WITH_DATABASE_PASSWORD/);
  assert.match(content, /server.address=127\.0\.0\.1/);
  assert.match(content, /server.port=8088/);
  assert.match(content, /standalone.origin=http:\/\/127\.0\.0\.1:8088/);
  assert.equal(statSync(first).mode & 0o777, 0o600);
  assert.equal(run('--output', second).status, 0);
  assert.notEqual(content.match(uuid)[1], readFileSync(second, 'utf8').match(uuid)[1]);
  const repeated = run('--output', first);
  assert.equal(repeated.status, 1);
  assert.match(repeated.stderr, /^CONFIG_EXISTS:/);
  assert.equal(readFileSync(first, 'utf8'), content);
});

test('existing configuration and symlink targets are never overwritten or printed', (t) => {
  const dir = directory(t);
  const file = join(dir, 'private-canary.properties');
  const secret = 'private-config-canary=never-print-this';
  writeFileSync(file, secret);
  const link = join(dir, 'link.properties');
  symlinkSync(file, link);
  const broken = join(dir, 'broken.properties');
  symlinkSync(join(dir, 'absent'), broken);
  for (const path of [file, link, broken]) {
    const result = run('--output', path);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /^CONFIG_EXISTS:/);
    assert.equal(result.stdout, '');
    assert.ok(!result.stderr.includes(path));
    assert.ok(!result.stderr.includes(secret));
  }
  assert.equal(readFileSync(file, 'utf8'), secret);
});

test('two simultaneous initializers have exactly one winner', async (t) => {
  const output = join(directory(t), 'application.properties');
  const { execFile } = await import('node:child_process');
  const start = () => new Promise((resolve) => {
    execFile(process.execPath, [script, '--output', output], { timeout: 10_000 }, (error) => {
      resolve(error?.code ?? 0);
    });
  });
  assert.deepEqual((await Promise.all([start(), start()])).sort(), [0, 1]);
  assert.match(readFileSync(output, 'utf8'), uuid);
});

test('rejects missing, relative, duplicate and unknown arguments with no path disclosure', (t) => {
  const output = join(directory(t), 'application.properties');
  for (const args of [[], ['--output'], ['--output', 'relative.properties'],
    ['--output', output, '--output', output], ['--unknown', output],
    ['--output', output, '--force'], ['--output', output.replace('.properties', '.env')],
    ['--output', output.replace('.properties', ',extra.properties')],
    ['--output', output.replace('.properties', ';extra.properties')]]) {
    const result = run(...args);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /^CONFIG_USAGE:/);
    assert.equal(result.stdout, '');
    assert.ok(!result.stderr.includes(output));
  }
});

test('reports filesystem errors without content or path and does not create parent directories', (t) => {
  const output = join(directory(t), 'private-path-canary', 'application.properties');
  const result = run('--output', output);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /^CONFIG_INIT_FAILED:/);
  assert.ok(!result.stderr.includes('private-path-canary'));
  assert.equal(result.stdout, '');
});
