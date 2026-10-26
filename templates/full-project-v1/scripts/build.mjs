import { lstat, realpath } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { packageFrontend, requireDirectory } from './package-frontend.mjs';
import { runCli, runCommand, toolEnvironment } from './process.mjs';

export async function build(projectRoot, { args = [], env = process.env, signal, run = runCommand } = {}) {
  if (!process.versions.node.startsWith('24.20.')) throw new Error('Node 24.20.x is required.');
  const root = await realpath(projectRoot);
  const testConfig = await privateTestConfig(root, args);
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
  // Recheck after dependency/frontend commands; the Java initializer also validates the file on use.
  if (testConfig) await privateTestConfig(root, args);
  await run(join(root, 'backend/mvnw'), ['-B', 'clean', 'verify', '-Pfull-project-package',
    '-Dfull-project.resources=' + resources, ...(testConfig ? ['-Dfull-project.test-config=' + testConfig] : [])],
  { cwd: join(root, 'backend'), env: childEnv, signal });
  if (signal?.aborted) throw new Error('Build cancelled.');
  await requireDirectory(join(root, 'backend/target'));
  const jar = join(root, 'backend/target/app.jar');
  const stat = await lstat(jar);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0) throw new Error('Application JAR is missing.');
  return { jar, resources };
}

async function privateTestConfig(root, args) {
  if (args.length === 0) return null;
  if (args.length !== 2 || args[0] !== '--test-config' || !isAbsolute(args[1])
      || !args[1].endsWith('.properties') || resolve(args[1]) !== args[1] || /[\x00-\x1f\x7f,;#?]/.test(args[1]))
    throw new Error('Use --test-config with one absolute .properties path.');
  const stat = await lstat(args[1]);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1 || stat.size === 0 || stat.size > 16 * 1024
      || (stat.mode & 0o077) !== 0 || (stat.mode & 0o400) === 0 || stat.uid !== process.getuid())
    throw new Error('Private regular test config required.');
  const config = await realpath(args[1]);
  const location = relative(root, config);
  if (config !== args[1] || location === '' || (!isAbsolute(location) && location !== '..' && !location.startsWith('..' + sep)))
    throw new Error('Test config must be outside the source project without symbolic links.');
  return config;
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(async signal => {
    await build(fileURLToPath(new URL('..', import.meta.url)), { args: process.argv.slice(2), signal });
    console.log('Build gates passed: backend/target/app.jar. Runtime and business acceptance remain separate.');
  }, 'Build failed. Inspect the failing gate; no successful build is claimed.');
}
