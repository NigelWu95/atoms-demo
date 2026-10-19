import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, realpath, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { start } from './start.mjs';

async function fixture() {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'full-project-start-test-')));
  const root = join(directory, 'project');
  await mkdir(join(root, 'backend/target'), { recursive: true });
  await writeFile(join(root, 'backend/target/app.jar'), 'fixture-only-not-executed');
  const config = join(directory, 'runtime config.properties');
  await writeFile(config, 'APP_PORT=8088\n', { mode: 0o600 });
  return { root, config, directory };
}

test('runtime receives explicit external config but no platform environment', async () => {
  const { root, config } = await fixture();
  const calls = [];
  await start(root, ['--config', config], {
    env: { PATH: '/usr/bin:/bin', JAVA_HOME: '/trusted-jdk', DEEPSEEK_API_KEY: 'MODEL_CANARY', APP_DB_PASSWORD: 'RUNTIME_CANARY' },
    run: async (command, args, options) => calls.push({ command, args, options }),
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].command, '/trusted-jdk/bin/java');
  assert.deepEqual(calls[0].args, ['-jar', join(root, 'backend/target/app.jar'),
    '--spring.config.location=classpath:/application.yml,file:' + config, '--server.address=127.0.0.1']);
  assert.deepEqual(calls[0].options.env, { PATH: '/usr/bin:/bin', JAVA_HOME: '/trusted-jdk' });
});

test('extra arguments, relative paths, embedded configuration and readable secrets are rejected before execution', async () => {
  const { root, config } = await fixture();
  let calls = 0;
  const options = { run: async () => { calls++; } };
  for (const args of [[], ['--config', 'relative.properties'], ['--config', config, '--server.port=9090']])
    await assert.rejects(start(root, args, options));
  const inside = join(root, 'runtime.properties');
  await writeFile(inside, 'APP_PORT=8088\n', { mode: 0o600 });
  await assert.rejects(start(root, ['--config', inside], options));
  await chmod(config, 0o644);
  await assert.rejects(start(root, ['--config', config], options));
  assert.equal(calls, 0);
});

test('linked config and linked JAR parent are refused without spawning Java', async () => {
  const { root, config, directory } = await fixture();
  let calls = 0;
  const options = { run: async () => { calls++; } };
  const link = join(directory, 'linked.properties');
  await symlink(config, link);
  await assert.rejects(start(root, ['--config', link], options));
  const other = join(directory, 'other-project');
  await mkdir(other);
  await symlink(join(root, 'backend'), join(other, 'backend'));
  await assert.rejects(start(other, ['--config', config], options));
  assert.equal(calls, 0);
});
