import { lstat, realpath } from 'node:fs/promises';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { requireDirectory } from './package-frontend.mjs';
import { runCli, runCommand, toolEnvironment } from './process.mjs';

export async function start(projectRoot, args, { env = process.env, signal, run = runCommand } = {}) {
  if (args.length !== 2 || args[0] !== '--config' || !isAbsolute(args[1])
      || !args[1].endsWith('.properties') || /[\x00-\x1f\x7f,;#?]/.test(args[1]))
    throw new Error('Use --config with one absolute .properties path.');
  const root = await realpath(projectRoot);
  const stat = await lstat(args[1]);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1 || stat.size === 0
      || stat.size > 16 * 1024 || (stat.mode & 0o077) !== 0) throw new Error('Private regular config required.');
  const config = await realpath(args[1]);
  const location = relative(root, config);
  if (location === '' || (!isAbsolute(location) && location !== '..' && !location.startsWith('..' + sep)))
    throw new Error('Runtime config must be outside the source project.');
  for (const name of ['backend', 'backend/target']) await requireDirectory(join(root, name));
  const jar = join(root, 'backend/target/app.jar');
  const output = await lstat(jar);
  if (!output.isFile() || output.isSymbolicLink() || output.size === 0) throw new Error('Application JAR is missing.');
  const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin/java') : 'java';
  await run(java, ['-jar', jar, '--spring.config.location=classpath:/application.yml,file:' + config,
    '--server.address=127.0.0.1'], { cwd: root, env: toolEnvironment(env), signal });
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(signal => start(fileURLToPath(new URL('..', import.meta.url)), process.argv.slice(2), { signal }),
    'Start failed. Supply --config /absolute/private/runtime.properties and a built backend/target/app.jar.');
}
