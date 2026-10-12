import { lstat, mkdtemp, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readBounded } from './build-inputs.mjs';
import { collectResources, packageApplication, RESOURCE_PATHS, runtimeId } from './package-application.mjs';
import { runCli, runCommand, toolEnvironment } from './process.mjs';

export async function build(projectRoot, { env = process.env, signal } = {}) {
  const root = await realpath(projectRoot);
  const config = env.STANDALONE_TEST_CONFIG;
  if (!config || !isAbsolute(config) || !config.endsWith('.properties')) throw new Error('Explicit test config required.');
  const stat = await lstat(config);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 16 * 1024) throw new Error('Invalid test config file.');
  // Do not parse credentials here, or pass even the test config location to npm/Vite.
  // TestDatabase validates the marker and the actual database/role before any migration or cleanup.
  for (const path of RESOURCE_PATHS) await readBounded(root,
    path.startsWith('application/') ? path : `backend/src/main/resources/${path}`,
    path.endsWith('manifest.json') ? 272 * 1024 : 120 * 1024);
  const childEnv = toolEnvironment(env);
  for (const args of [['ci', '--ignore-scripts'], ['run', 'typecheck'], ['run', 'lint'], ['run', 'test:unit'], ['run', 'build']]) {
    await runCommand('npm', args, { cwd: join(root, 'frontend'), env: childEnv, signal });
  }
  // Outside backend/target: Maven clean cannot erase its own resource inputs. Never reuse an old stage.
  const stage = await mkdtemp(join(tmpdir(), 'standalone-build-'));
  const packaged = await packageApplication(root, stage);
  await runCommand(join(root, 'backend/mvnw'), ['-B', 'clean', 'verify', '-Pstandalone-package',
    '-Dstandalone.resources=' + packaged.directory, '-Dstandalone.expected-runtime-id=' + packaged.loadedRuntimeId],
  { cwd: join(root, 'backend'), env: { ...childEnv, STANDALONE_TEST_CONFIG: config }, signal });
  if (signal?.aborted || runtimeId(await collectResources(root)) !== packaged.loadedRuntimeId)
    throw new Error('Inputs changed during the build.');
  const jar = await lstat(join(root, 'backend/target/app.jar'));
  if (!jar.isFile() || jar.isSymbolicLink() || jar.size === 0) throw new Error('Executable JAR is missing.');
  return packaged;
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(async signal => {
    if (process.argv.length !== 2) throw new Error('Build does not accept extra flags.');
    const result = await build(fileURLToPath(new URL('..', import.meta.url)), { signal });
    console.log(`Build verified: backend/target/app.jar (runtime ${result.loadedRuntimeId})`);
  }, 'Build failed. Check the required test config, build inputs and the failing gate; no verified build was produced.');
}
