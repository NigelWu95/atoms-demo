import assert from 'node:assert/strict';
import { chmod, link, mkdir, mkdtemp, readFile, realpath, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { build } from './build.mjs';
import { packageFrontend } from './package-frontend.mjs';

async function fixture() {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'full-project-build-test-')));
  await mkdir(join(root, 'frontend/dist/assets'), { recursive: true });
  await mkdir(join(root, 'backend/target'), { recursive: true });
  await writeFile(join(root, 'frontend/dist/index.html'), '<html>native</html>');
  await writeFile(join(root, 'frontend/dist/assets/app.js'), 'export const native = true;');
  return root;
}

test('build gates run in order and package only frontend output without application secrets', async () => {
  const root = await fixture();
  const calls = [];
  const env = { PATH: '/usr/bin:/bin', DEEPSEEK_API_KEY: 'MODEL_CANARY', APP_DB_PASSWORD: 'RUNTIME_CANARY', NODE_OPTIONS: '--inspect' };
  const run = async (command, args, options) => {
    calls.push({ command, args, options });
    assert.deepEqual(options.env, { PATH: '/usr/bin:/bin' });
    if (command.endsWith('/mvnw')) {
      const staging = args.find(arg => arg.startsWith('-Dfull-project.resources=')).split('=').slice(1).join('=');
      assert.equal(await readFile(join(staging, 'static/index.html'), 'utf8'), '<html>native</html>');
      assert.equal(await readFile(join(staging, 'static/assets/app.js'), 'utf8'), 'export const native = true;');
      await writeFile(join(root, 'backend/target/app.jar'), 'fake-output');
    }
  };
  const result = await build(root, { env, run });
  assert.equal(result.jar, join(root, 'backend/target/app.jar'));
  assert.deepEqual(calls.filter(call => call.command === 'npm').map(call => call.args), [
    ['ci', '--ignore-scripts', '--no-audit', '--no-fund'],
    ['run', 'typecheck'], ['run', 'lint'], ['run', 'test:unit'], ['run', 'build'],
  ]);
  assert.equal(calls[0].command, process.execPath);
  assert.deepEqual(calls.at(-1).args.slice(0, 4), ['-B', 'clean', 'verify', '-Pfull-project-package']);
  assert.equal(calls.at(-1).options.cwd, join(root, 'backend'));
  assert.equal(await readFile(join(root, 'frontend/dist/index.html'), 'utf8'), '<html>native</html>');
});

test('failed frontend gate stops before packaging/backend and does not claim a build', async () => {
  const root = await fixture();
  const calls = [];
  await assert.rejects(build(root, { run: async (command, args) => {
    calls.push(command);
    if (args.includes('lint')) throw new Error('intentional gate failure');
  } }), /intentional gate failure/);
  assert.ok(!calls.some(command => command.endsWith('/mvnw')));
});

test('missing output cannot be reported as a successful executable build', async () => {
  await assert.rejects(build(await fixture(), { run: async () => {} }));
});

test('resource packager rejects symlinks and private dot files rather than following them', async () => {
  const root = await fixture();
  await symlink(join(root, 'frontend/dist/index.html'), join(root, 'frontend/dist/assets/leak'));
  await assert.rejects(packageFrontend(root));
  const second = await fixture();
  await writeFile(join(second, 'frontend/dist/.env'), 'PRIVATE_FILE_CANARY');
  await assert.rejects(packageFrontend(second));
});

test('symbolic frontend directory is rejected before any command starts', async () => {
  const root = await mkdtemp(join(tmpdir(), 'full-project-linked-test-'));
  const other = await fixture();
  await mkdir(join(root, 'backend'));
  await symlink(join(other, 'frontend'), join(root, 'frontend'));
  let calls = 0;
  await assert.rejects(build(root, { run: async () => { calls++; } }));
  assert.equal(calls, 0);
});

test('explicit private test config reaches only Maven as a path, never npm or child environment', async () => {
  const root = await fixture();
  const privateRoot = await realpath(await mkdtemp(join(tmpdir(), 'full-project-test-config-')));
  const config = join(privateRoot, 'test database.properties');
  await writeFile(config, 'password=RUNTIME_CANARY', { mode: 0o600 });
  const calls = [];
  await build(root, { args: ['--test-config', config], env: { PATH: '/usr/bin:/bin', APP_TEST_CONFIG: 'ignored',
    APP_DB_PASSWORD: 'RUNTIME_CANARY', JAVA_TOOL_OPTIONS: '-Dtest=skip' }, run: async (command, args, options) => {
    calls.push({ command, args, options });
    assert.deepEqual(options.env, { PATH: '/usr/bin:/bin' });
    if (command.endsWith('/mvnw')) await writeFile(join(root, 'backend/target/app.jar'), 'fake-output');
  } });
  assert.ok(calls.at(-1).args.includes('-Dfull-project.test-config=' + config));
  assert.ok(!JSON.stringify(calls.slice(0, -1)).includes(privateRoot));
  assert.ok(!JSON.stringify(calls).includes('RUNTIME_CANARY'));
  assert.ok(!calls.at(-1).args.some(arg => arg.includes('skipTests')));
});

test('invalid flags and non-private test configs fail before any build command', async () => {
  const root = await fixture();
  const privateRoot = await realpath(await mkdtemp(join(tmpdir(), 'full-project-invalid-config-')));
  const config = join(privateRoot, 'test.properties');
  await writeFile(config, 'purpose=test', { mode: 0o600 });
  const symbolic = join(privateRoot, 'symbolic.properties');
  await symlink(config, symbolic);
  const internal = join(root, 'test.properties');
  await writeFile(internal, 'purpose=test', { mode: 0o600 });
  const publicFile = join(privateRoot, 'public.properties');
  await writeFile(publicFile, 'purpose=test', { mode: 0o600 });
  await chmod(publicFile, 0o644);
  const empty = join(privateRoot, 'empty.properties');
  await writeFile(empty, '', { mode: 0o600 });
  const oversized = join(privateRoot, 'large.properties');
  await writeFile(oversized, 'x'.repeat(16 * 1024 + 1), { mode: 0o600 });
  const hardlink = join(privateRoot, 'hardlink.properties');
  await link(config, hardlink);
  for (const args of [['--skip-tests'], ['--test-config'], ['--test-config', 'relative.properties'],
    ['--test-config', symbolic], ['--test-config', internal], ['--test-config', publicFile],
    ['--test-config', empty], ['--test-config', oversized], ['--test-config', hardlink],
    ['--test-config', config, '--skip-tests'], ['--test-config', privateRoot]]) {
    let calls = 0;
    await assert.rejects(build(root, { args, run: async () => { calls++; } }));
    assert.equal(calls, 0, 'configuration must be rejected before dependency installation');
  }
});
