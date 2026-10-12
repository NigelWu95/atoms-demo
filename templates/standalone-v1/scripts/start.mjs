import { lstat, realpath } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runCli, runCommand, toolEnvironment } from './process.mjs';

export async function start(projectRoot, args, { env = process.env, signal } = {}) {
  if (args.length !== 2 || args[0] !== '--config' || !isAbsolute(args[1])
      || !args[1].endsWith('.properties') || /[\x00-\x1f\x7f,;]/.test(args[1]))
    throw new Error('Use --config with one absolute .properties path.');
  const config = await lstat(args[1]);
  if (!config.isFile() || config.isSymbolicLink() || config.size > 16 * 1024) throw new Error('Invalid configuration file.');
  const root = await realpath(projectRoot);
  for (const path of ['backend', 'backend/target', 'backend/target/app.jar']) {
    const stat = await lstat(join(root, path));
    if (stat.isSymbolicLink() || (path.endsWith('.jar') ? !stat.isFile() || stat.size === 0 : !stat.isDirectory()))
      throw new Error('Missing or unsafe application JAR.');
  }
  // No config parsing here: the same Java preflight protects this entry and direct java -jar.
  // The startup process never needs npm, Maven, model credentials or the integration-test config.
  const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin/java') : 'java';
  await runCommand(java, ['-jar', join(root, 'backend/target/app.jar'),
    '--spring.config.location=classpath:/application.yml,file:' + args[1]],
  { cwd: root, env: toolEnvironment(env), signal });
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(signal => start(fileURLToPath(new URL('..', import.meta.url)), process.argv.slice(2), { signal }),
    'Start failed. Supply --config /absolute/path/application.properties and a verified backend/target/app.jar; check Java startup errors.');
}
