import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, realpath } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { runCli } from '../templates/standalone-v1/scripts/process.mjs';
import { startOwned, listenerFromConfig } from './standalone-process.mjs';

/** Local evidence is a consistency check, not a signature or permission to import arbitrary bundles. */
export async function verifiedProject(path) {
  const invalid = () => { throw new Error('Invalid standalone verification evidence.'); };
  if (!path || !isAbsolute(path)) invalid();
  const info = await lstat(path);
  if (!info.isFile() || info.isSymbolicLink() || info.size > 4096) invalid();
  const report = JSON.parse(await readFile(path, 'utf8'));
  if (typeof report.directory !== 'string' || !isAbsolute(report.directory)
    || dirname(report.directory) !== dirname(path) || !/^engineering-bundle-[A-Za-z0-9]{6}$/.test(basename(report.directory))
    || !/^[a-f0-9]{64}$/.test(report.jarSha256) || !/^http:\/\/127\.0\.0\.1:\d{4,5}$/.test(report.baseURL)) invalid();
  for (const part of ['', 'backend', 'backend/target', 'backend/target/app.jar']) {
    const stat = await lstat(join(report.directory, part));
    if (stat.isSymbolicLink() || (part.endsWith('.jar') ? !stat.isFile() || stat.size > 128 * 1024 * 1024 : !stat.isDirectory())) invalid();
  }
  const digest = createHash('sha256');
  for await (const bytes of createReadStream(join(report.directory, 'backend/target/app.jar'))) digest.update(bytes);
  if (digest.digest('hex') !== report.jarSha256) invalid();
  return { directory: report.directory, baseURL: report.baseURL };
}

/** Repository-only lifecycle: recheck the JAR on every start, own only the returned process. */
export async function startVerifiedRuntime(report, config, { signal, baseURL } = {}) {
  const project = await verifiedProject(report);
  const listener = baseURL ?? project.baseURL;
  if (!config || await listenerFromConfig(config) !== listener)
    throw new Error('Explicit matching runtime config required.');
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java';
  return startOwned(java, ['-jar', join(project.directory, 'backend/target/app.jar'),
    '--spring.config.location=classpath:/application.yml,file:' + config],
  { baseURL: listener, cwd: dirname(project.directory), signal });
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(async signal => {
    if (process.argv.length !== 2) throw new Error('No launcher flags supported.');
    const runtime = await startVerifiedRuntime(process.env.STANDALONE_VERIFICATION_REPORT,
      process.env.STANDALONE_RUNTIME_CONFIG, { signal });
    try {
      const failure = await runtime.done;
      if (failure) throw failure;
    } finally { await runtime.stop(); }
  }, 'Browser acceptance server failed; verify the explicit report and runtime configuration.');
}
