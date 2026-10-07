import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, readdir, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { assembleRelease } from './release.mjs';

test('systemd accepts the JVM graceful SIGTERM exit without masking other failures', async () => {
  const service = await readFile(new URL('../deploy/server/atoms-demo.service', import.meta.url), 'utf8');
  assert.match(service, /^SuccessExitStatus=143$/m);
  assert.match(service, /^Restart=on-failure$/m);
  assert.doesNotMatch(service, /^SuccessExitStatus=.*\b(?:1|137)\b/m);
});

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'atoms-release-test-'));
  const files = {
    'backend/target/atoms-demo-0.1.0-SNAPSHOT.jar': 'test-only artifact',
    'frontend/dist/index.html': '<html>fixture</html>',
    'frontend/dist/favicon.svg': '<svg xmlns="http://www.w3.org/2000/svg"/>',
    'frontend/dist/assets/index-abc.js': 'console.info("fixture")',
    'frontend/dist/assets/index-abc.css': 'body {}',
    'deploy/server/start.sh': '#!/bin/sh\nexit 0\n',
    'deploy/server/application.properties.example': 'GENERATION_MAX_MODEL_CALLS=0\n',
    'deploy/server/atoms-demo.service': '[Service]\nUser=atoms\n',
    'deploy/server/nginx-site.conf': 'server {}',
    'deploy/server/nginx-routes.conf': 'location / {}',
    'deploy/server/nginx-static-headers.conf': 'add_header X-Frame-Options DENY;',
    'deploy/server/init-db.sql': 'SELECT 1;',
    'deploy/server/README.md': 'test runbook',
    '.env.local': 'SENTINEL_PRIVATE_SECRET',
    'frontend/node_modules/private.txt': 'SENTINEL_PRIVATE_SECRET',
    'backend/src/test/resources/private.txt': 'SENTINEL_PRIVATE_SECRET',
    'deploy/server/application.properties': 'SENTINEL_PRIVATE_SECRET',
  };
  for (const [path, contents] of Object.entries(files)) {
    await mkdir(join(root, path, '..'), { recursive: true });
    await writeFile(join(root, path), contents);
  }
  return root;
}

test('packages only allowlisted production files with verifiable hashes and unique outputs', async () => {
  const root = await fixture();
  const metadata = { revision: 'a'.repeat(40), dirty: true };
  const first = await assembleRelease(root, join(root, 'dist'), metadata);
  const second = await assembleRelease(root, join(root, 'dist'), metadata);
  assert.notEqual(first.directory, second.directory);
  const manifest = JSON.parse(await readFile(join(first.directory, 'release.json'), 'utf8'));
  assert.equal(manifest.revision, metadata.revision);
  assert.equal(manifest.dirty, true);
  assert.equal(manifest.files.length, 13);
  for (const file of manifest.files) {
    const content = await readFile(join(first.directory, file.path));
    assert.equal(createHash('sha256').update(content).digest('hex'), file.sha256);
    assert.ok(!content.includes('SENTINEL_PRIVATE_SECRET'));
  }
  const listing = spawnSync('tar', ['-tzf', first.archive], { encoding: 'utf8' });
  assert.equal(listing.status, 0);
  assert.match(listing.stdout, /backend\/atoms-demo\.jar/);
  assert.doesNotMatch(listing.stdout, /node_modules|\.env|src\/test|application\.properties\n/);
  assert.match(await readFile(first.archive + '.sha256', 'utf8'), /^[a-f0-9]{64}  atoms-demo-/);
});

test('fails closed on source maps, symlinks and incomplete artifacts without emitting an archive', async () => {
  for (const variant of ['map', 'symlink', 'missing']) {
    const root = await fixture();
    if (variant === 'map') await writeFile(join(root, 'frontend/dist/assets/index.js.map'), '{}');
    if (variant === 'symlink') await symlink(join(root, '.env.local'), join(root, 'frontend/dist/assets/leak.js'));
    const output = join(root, 'out');
    await assert.rejects(assembleRelease(variant === 'missing' ? join(root, 'absent') : root, output, {}));
    assert.equal((await readdir(output).catch(() => [])).filter((name) => name.endsWith('.tar.gz')).length, 0);
  }
});
