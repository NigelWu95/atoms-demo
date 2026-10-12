import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { materializeBundle, validateBundle, verifyMaterialized } from './standalone-bundle.mjs';
import { assertFree, listenerFromConfig, startOwned } from './standalone-process.mjs';
import { collectResources, runtimeId } from '../templates/standalone-v1/scripts/package-application.mjs';
import { runCli, runCommand, toolEnvironment } from '../templates/standalone-v1/scripts/process.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
export function verificationOptions(args) {
  if (args.length !== 4 || args[0] !== '--config-dir' || args[2] !== '--output-parent' || !args[1] || !args[3])
    throw new Error('Usage: verify-standalone.mjs --config-dir <existing-directory> --output-parent <existing-directory>');
  return { configDir: args[1], outputParent: args[3] };
}

export async function verifyStandalone({ configDir, outputParent }, signal) {
  const config = await realpath(configDir);
  const parent = await realpath(outputParent);
  if (!(await lstat(config)).isDirectory() || !(await lstat(parent)).isDirectory()) throw new Error('Existing directories required.');
  const runtimeConfig = join(config, 'runtime-a.properties');
  const baseURL = await listenerFromConfig(runtimeConfig);
  await assertFree(baseURL);
  const testConfig = join(config, 'test.properties');
  const testStat = await lstat(testConfig);
  if (!testStat.isFile() || testStat.isSymbolicLink() || testStat.size > 16384) throw new Error('Separate test configuration required.');
  const work = await mkdtemp(join(parent, 'verification-'));
  const env = toolEnvironment();
  const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin/java') : 'java';
  const platformJar = join(root, 'backend/target/atoms-demo-0.1.0-SNAPSHOT.jar');
  // Bounded synchronous test-only assembly (<30s), not a server or an arbitrary command from bundle data.
  // https://nodejs.org/download/release/v24.20.0/docs/api/child_process.html#child_processexecfilesyncfile-args-options
  const transport = execFileSync(java, ['-Dloader.path=' + join(root, 'backend/target/test-classes'),
    '-Dloader.main=dev.atoms.demo.engineering.BundleFixture', '-cp', platformJar,
    'org.springframework.boot.loader.launch.PropertiesLauncher'],
  { cwd: work, env, timeout: 30000, maxBuffer: 16 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  signal?.throwIfAborted();
  const bundle = JSON.parse(transport.toString('utf8'));
  const allowed = (await readFile(join(root, 'templates/standalone-v1.files'), 'utf8')).trimEnd().split('\n');
  allowed.push('application/main.js', 'application/manifest.json', 'application/styles.css', 'application/view.html');
  const expected = validateBundle(bundle, allowed);
  const result = await materializeBundle(bundle, allowed, work);
  console.log(`Assembled ${result.fileCount} files (${result.totalBytes} bytes) in ${result.directory}`);
  const scripts = allowed.filter(path => /^scripts\/[^/]+\.test\.mjs$/.test(path));
  await runCommand(process.execPath, ['--test', ...scripts], { cwd: result.directory, env, signal });
  await runCommand(process.execPath, ['scripts/build.mjs'], { cwd: result.directory,
    env: { ...env, STANDALONE_TEST_CONFIG: testConfig }, signal });
  await verifyMaterialized(result.directory, expected.files);
  const loadedRuntimeId = runtimeId(await collectResources(result.directory));
  const jar = join(result.directory, 'backend/target/app.jar');
  const launches = [
    [process.execPath, [join(result.directory, 'scripts/start.mjs'), '--config', runtimeConfig], 'node'],
    [java, ['-jar', jar, '--spring.config.location=classpath:/application.yml,file:' + runtimeConfig], 'java'],
  ];
  for (const [command, args, label] of launches) {
    const owned = await startOwned(command, args, { baseURL, cwd: work, env, signal });
    try {
      const shell = await fetch(baseURL, { signal: AbortSignal.timeout(5000), redirect: 'error' });
      if (shell.status !== 200 || !(await shell.text()).includes('id="app"')) throw new Error('Packaged shell is missing.');
      console.log(`Verified ${label} launch from outside the project directory.`);
    } finally { await owned.stop(); }
  }
  signal?.throwIfAborted();
  const report = { ...result, loadedRuntimeId, baseURL,
    jarSha256: createHash('sha256').update(await readFile(jar)).digest('hex'),
    checks: ['manifest-hashes', 'script-tests', 'frozen-install', 'frontend-checks', 'maven-verify', 'node-start', 'java-start', 'owned-stop'] };
  const reportPath = join(work, 'verification.json');
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  console.log(`Verification report: ${reportPath}`);
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(signal => verifyStandalone(verificationOptions(process.argv.slice(2)), signal),
    'Standalone verification failed; no successful report was produced. Existing directories and unrelated processes were preserved.');
}
