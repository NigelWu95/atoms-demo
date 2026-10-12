import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, symlink, unlink, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { build } from './build.mjs';
import { RESOURCE_PATHS } from './package-application.mjs';

async function fixture() {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'standalone-build with spaces-')));
  const trace = join(root, 'commands.jsonl');
  const config = join(root, 'dedicated test.properties');
  const files = ['backend/pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
    'backend/src/main/java/dev/atoms/standalone/StandaloneApplication.java',
    'backend/src/main/resources/application.yml', 'frontend/dist/index.html', 'frontend/dist/assets/app.js',
    ...RESOURCE_PATHS.map(path => path.startsWith('application/') ? path : `backend/src/main/resources/${path}`)];
  for (const path of files) {
    await mkdir(dirname(join(root, path)), { recursive: true });
    await writeFile(join(root, path), `content:${path}`);
  }
  await mkdir(join(root, 'bin'));
  await writeFile(config, 'standalone.test-database=true\n');
  const mock = `#!${process.execPath}
    const fs = require('node:fs'); const path=require('node:path');
    const command=path.basename(process.argv[1]);
    const args=process.argv.slice(2);
    fs.appendFileSync(${JSON.stringify(trace)}, JSON.stringify({command,args,cwd:process.cwd(),env:process.env})+'\\n');
    const failureFile=${JSON.stringify(join(root, 'fail-step'))};
    if(fs.existsSync(failureFile) && [command,args.join(' ')].includes(fs.readFileSync(failureFile,'utf8'))) process.exit(27);
    if(command==='mvnw') {
      fs.mkdirSync('target',{recursive:true}); fs.writeFileSync('target/app.jar','FAKE_TEST_OUTPUT_NOT_A_JAR');
      if(fs.existsSync('../mutate-input')) fs.appendFileSync('../frontend/dist/assets/app.js','changed');
    }
  `;
  await writeFile(join(root, 'bin/npm'), mock, { mode: 0o700 });
  await writeFile(join(root, 'backend/mvnw'), mock, { mode: 0o700 });
  return { root, trace, config, env: { PATH: join(root, 'bin'), STANDALONE_TEST_CONFIG: config,
    STANDALONE_DB_PASSWORD: 'RUNTIME_CANARY', DEEPSEEK_API_KEY: 'MODEL_CANARY', MAVEN_ARGS: '-DskipTests' } };
}

test('fixed checked frontend sequence precedes isolated resource packaging and clean verify', async () => {
  const f = await fixture();
  const result = await build(f.root, { env: f.env });
  const commands = (await readFile(f.trace, 'utf8')).trim().split('\n').map(line => JSON.parse(line));
  assert.deepEqual(commands.slice(0, 5).map(call => call.args), [
    ['ci', '--ignore-scripts'], ['run', 'typecheck'], ['run', 'lint'], ['run', 'test:unit'], ['run', 'build'],
  ]);
  assert.ok(commands.slice(0, 5).every(call => call.cwd === join(f.root, 'frontend') && !call.env.STANDALONE_TEST_CONFIG));
  const maven = commands[5];
  assert.equal(commands.length, 6);
  assert.equal(maven.command, 'mvnw');
  assert.equal(maven.cwd, join(f.root, 'backend'));
  assert.deepEqual(maven.args.slice(0, 4), ['-B', 'clean', 'verify', '-Pstandalone-package']);
  assert.equal(maven.args[4], '-Dstandalone.resources=' + result.directory);
  assert.equal(maven.args[5], '-Dstandalone.expected-runtime-id=' + result.loadedRuntimeId);
  assert.equal(maven.env.STANDALONE_TEST_CONFIG, f.config);
  assert.ok(commands.every(call => !call.env.STANDALONE_DB_PASSWORD && !call.env.DEEPSEEK_API_KEY && !call.env.MAVEN_ARGS));
  assert.equal(await readFile(join(result.directory, 'runtime/content.sha256'), 'utf8'), result.loadedRuntimeId + '\n');
});

test('every frontend gate stops the pipeline and preserves its exit code', async () => {
  for (const args of ['ci --ignore-scripts', 'run typecheck', 'run lint', 'run test:unit', 'run build']) {
    const f = await fixture(); await writeFile(join(f.root, 'fail-step'), args);
    await assert.rejects(build(f.root, { env: f.env }), error => error.code === 27);
    const calls = (await readFile(f.trace, 'utf8')).trim().split('\n').map(JSON.parse);
    assert.equal(calls.at(-1).args.join(' '), args);
    assert.ok(calls.every(call => call.command !== 'mvnw'));
  }
});

test('missing or unsafe test config and missing sources fail before any tool runs', async () => {
  const f = await fixture();
  for (const config of [undefined, 'relative.properties', join(f.root, 'missing.properties'), f.root]) {
    await assert.rejects(build(f.root, { env: { ...f.env, STANDALONE_TEST_CONFIG: config } }));
  }
  const link = join(f.root, 'link.properties'); await symlink(f.config, link);
  await assert.rejects(build(f.root, { env: { ...f.env, STANDALONE_TEST_CONFIG: link } }));
  await unlink(join(f.root, 'application/main.js'));
  await assert.rejects(build(f.root, { env: f.env }));
  await assert.rejects(readFile(f.trace));
});

test('a changed source tree during Maven cannot be reported as a successful build', async () => {
  const f = await fixture(); await writeFile(join(f.root, 'mutate-input'), 'yes');
  await assert.rejects(build(f.root, { env: f.env }));
});

test('Maven failure and cancellation propagate, without promoting any build result', async () => {
  const f = await fixture(); await writeFile(join(f.root, 'fail-step'), 'mvnw');
  await assert.rejects(build(f.root, { env: f.env }), error => error.code === 27);
  const aborted = new AbortController(); aborted.abort('SIGINT');
  await assert.rejects(build(f.root, { env: f.env, signal: aborted.signal }), error => error.signal === 'SIGINT');
});
