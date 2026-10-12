import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { cp, mkdtemp, mkdir, writeFile, readFile, symlink, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { start } from './start.mjs';

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'standalone-start with spaces-'));
  await mkdir(join(root, 'backend/target'), { recursive: true });
  await mkdir(join(root, 'jdk/bin'), { recursive: true });
  await mkdir(join(root, 'scripts'));
  const config = join(root, 'application.properties');
  await writeFile(config, '# Parsed only by the real Java entry point, not Node.\n');
  await writeFile(join(root, 'backend/target/app.jar'), 'FAKE_TEST_JAR');
  await writeFile(join(root, 'jdk/bin/java'), `#!${process.execPath}
    const fs=require('node:fs');
    fs.writeFileSync(${JSON.stringify(join(root, 'invocation.json'))},JSON.stringify({args:process.argv.slice(2),env:process.env}));
    if(fs.existsSync(${JSON.stringify(join(root, 'wait'))})) {
      console.log('OWNED_JAVA_READY'); setInterval(()=>{},1000);
    } else process.exit(29);
  `, { mode: 0o700 });
  for (const name of ['start.mjs', 'process.mjs']) await cp(new URL(name, import.meta.url), join(root, 'scripts', name));
  return { root, config, env: { JAVA_HOME: join(root, 'jdk'), PATH: '/bin',
    JAVA_TOOL_OPTIONS: 'UNSAFE_OVERRIDE', STANDALONE_DB_PASSWORD: 'PRIVATE_CANARY', STANDALONE_TEST_CONFIG: '/test.properties' } };
}

test('starts only the existing JAR with one literal configuration argument and preserves Java failure', async () => {
  const f = await fixture();
  await assert.rejects(start(f.root, ['--config', f.config], { env: f.env }), error => error.code === 29);
  const call = JSON.parse(await readFile(join(f.root, 'invocation.json'), 'utf8'));
  assert.equal(call.args.length, 3);
  assert.equal(call.args[0], '-jar');
  assert.ok(call.args[1].endsWith('/backend/target/app.jar'));
  assert.equal(call.args[2], '--spring.config.location=classpath:/application.yml,file:' + f.config);
  assert.equal(call.env.JAVA_TOOL_OPTIONS, undefined);
  assert.equal(call.env.STANDALONE_DB_PASSWORD, undefined);
  assert.equal(call.env.STANDALONE_TEST_CONFIG, undefined);
});

test('refuses missing, unsafe or additional config arguments and absent JAR without launching anything', async () => {
  const f = await fixture();
  for (const args of [[], ['--config', 'relative.properties'], ['--config', f.config, '-DskipTests'],
    ['--config', f.config + ',more.properties'], ['--config', f.config + ';more.properties'],
    ['--config', join(f.root, 'missing.properties')]]) await assert.rejects(start(f.root, args, { env: f.env }));
  const link = join(f.root, 'link.properties'); await symlink(f.config, link);
  await assert.rejects(start(f.root, ['--config', link], { env: f.env }));
  await unlink(join(f.root, 'backend/target/app.jar'));
  await assert.rejects(start(f.root, ['--config', f.config], { env: f.env }));
  await assert.rejects(readFile(join(f.root, 'invocation.json')));
});

test('real CLI preserves exit code and forwards SIGTERM/SIGINT rather than leaving a background server', async () => {
  const f = await fixture();
  const args = [join(f.root, 'scripts/start.mjs'), '--config', f.config];
  const failed = spawn(process.execPath, args, { env: f.env, stdio: 'ignore' });
  assert.deepEqual(await once(failed, 'close'), [29, null]);
  await writeFile(join(f.root, 'wait'), 'yes');
  for (const signal of ['SIGTERM', 'SIGINT']) {
    const child = spawn(process.execPath, args, { env: f.env, stdio: ['ignore', 'pipe', 'pipe'] });
    const closed = once(child, 'close');
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('owned Java fixture did not start')), 5000);
        child.stdout.on('data', bytes => { if (bytes.toString().includes('OWNED_JAVA_READY')) { clearTimeout(timer); resolve(); } });
      });
      child.kill(signal);
      assert.deepEqual(await closed, [null, signal]);
    } finally { if (child.exitCode === null && child.signalCode === null) { child.kill('SIGTERM'); await closed; } }
  }
});
