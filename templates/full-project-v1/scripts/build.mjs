import { lstat, realpath } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { packageFrontend, requireDirectory } from './package-frontend.mjs';
import { runCli, runCommand, toolEnvironment } from './process.mjs';

export async function build(projectRoot, { env = process.env, signal, run = runCommand } = {}) {
  if (!process.versions.node.startsWith('24.20.')) throw new Error('Node 24.20.x is required.');
  const root = await realpath(projectRoot);
  for (const name of ['frontend', 'backend']) await requireDirectory(join(root, name));
  const childEnv = toolEnvironment(env);
  await run(process.execPath, ['--test', 'scripts/process.test.mjs', 'scripts/build.test.mjs', 'scripts/start.test.mjs'],
    { cwd: root, env: childEnv, signal });
  for (const args of [
    ['ci', '--ignore-scripts', '--no-audit', '--no-fund'],
    ['run', 'typecheck'], ['run', 'lint'], ['run', 'test:unit'], ['run', 'build'],
  ]) await run('npm', args, { cwd: join(root, 'frontend'), env: childEnv, signal });
  if (signal?.aborted) throw new Error('Build cancelled.');
  const resources = await packageFrontend(root);
  await run(join(root, 'backend/mvnw'), ['-B', 'clean', 'verify', '-Pfull-project-package',
    '-Dfull-project.resources=' + resources], { cwd: join(root, 'backend'), env: childEnv, signal });
  if (signal?.aborted) throw new Error('Build cancelled.');
  await requireDirectory(join(root, 'backend/target'));
  const jar = join(root, 'backend/target/app.jar');
  const stat = await lstat(jar);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) throw new Error('Application JAR is missing.');
  return { jar, resources };
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(async signal => {
    if (process.argv.length !== 2) throw new Error('Build does not accept extra flags.');
    await build(fileURLToPath(new URL('..', import.meta.url)), { signal });
    console.log('Build gates passed: backend/target/app.jar. Runtime and business acceptance remain separate.');
  }, 'Build failed. Inspect the failing gate; no successful build is claimed.');
}
