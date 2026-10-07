import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, mkdtemp, readFile, realpath, writeFile, unlink } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createServer } from 'node:net';
import { once } from 'node:events';

// Local verification only: production JAR + production assets, isolated test DB,
// disposable TLS proxy. No migrations of normal data and no real model requests.
const image = 'public.ecr.aws/docker/library/nginx@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94';
const root = fileURLToPath(new URL('..', import.meta.url));
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.code ?? result.stderr.trim()}`);
  return result.stdout.trim();
}
async function requireFree(port) {
  const server = createServer();
  server.listen(port, '127.0.0.1');
  await once(server, 'listening');
  await new Promise((resolve) => server.close(resolve));
}
async function main() {
  const release = await realpath(process.argv[2] ?? '');
  const manifest = JSON.parse(await readFile(join(release, 'release.json'), 'utf8'));
  for (const file of manifest.files) {
    if (!/^(server|frontend|backend)\/[\w./-]+$/.test(file.path) || file.path.includes('..')) throw new Error('Invalid manifest path.');
    const digest = createHash('sha256').update(await readFile(join(release, file.path))).digest('hex');
    if (digest !== file.sha256) throw new Error('Release checksum mismatch.');
  }
  run('docker', ['image', 'inspect', '--format', '{{.Id}}', image]);
  await requireFree(8082);
  await requireFree(18443);
  const password = (await readFile(join(root, '.env.local'), 'utf8')).match(/^TEST_DB_PASSWORD=([a-f0-9]{64})\r?$/m)?.[1];
  if (!password) throw new Error('A local isolated test database credential is required.');
  const temp = await mkdtemp(join(tmpdir(), 'atoms-release-smoke-'));
  const proxy = join(temp, 'nginx');
  await mkdir(proxy);
  const config = join(temp, 'application.properties');
  await writeFile(config, [
    'DB_URL=jdbc:postgresql://127.0.0.1:5432/atoms_demo_test?currentSchema=atoms&connectTimeout=3&socketTimeout=5',
    'DB_USER=atoms_test', `APP_DB_PASSWORD=${password}`, 'PORT=8082',
    'DEEPSEEK_API_KEY=', 'GENERATION_MAX_MODEL_CALLS=0', 'atoms.generation.worker-enabled=false',
    'PUBLIC_APP_ORIGIN=https://127.0.0.1:18443', 'server.servlet.session.cookie.name=ATOMS_TEST_SESSION',
  ].join('\n') + '\n', { mode: 0o600 });
  let backend;
  let container;
  const log = createWriteStream(join(temp, 'backend.log'), { mode: 0o600 });
  try {
    run('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost',
      '-keyout', join(proxy, 'test.key'), '-out', join(proxy, 'test.crt')]);
    let site = await readFile(join(release, 'server/nginx-site.conf'), 'utf8');
    site = site.replaceAll('atoms.example.com', 'localhost').replace('listen 443 ssl;', 'listen 8443 ssl;')
      .replace('/opt/atoms-demo/current/frontend', '/release/frontend')
      .replace('/etc/letsencrypt/live/localhost/fullchain.pem', '/etc/nginx/atoms-demo/test.crt')
      .replace('/etc/letsencrypt/live/localhost/privkey.pem', '/etc/nginx/atoms-demo/test.key');
    await writeFile(join(proxy, 'site.conf'), site);
    const routes = (await readFile(join(release, 'server/nginx-routes.conf'), 'utf8'))
      .replace('http://127.0.0.1:8080', 'http://host.docker.internal:8082');
    await writeFile(join(proxy, 'nginx-routes.conf'), routes);
    await writeFile(join(proxy, 'nginx-static-headers.conf'), await readFile(join(release, 'server/nginx-static-headers.conf')));
    backend = spawn('sh', [join(release, 'server/start.sh'), config], {
      env: { PATH: process.env.PATH, ...(process.env.JAVA_HOME ? { JAVA_HOME: process.env.JAVA_HOME } : {}) },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    backend.stdout.pipe(log, { end: false });
    backend.stderr.pipe(log, { end: false });
    let ready = false;
    for (let attempt = 0; attempt < 90; attempt++) {
      if (backend.exitCode !== null) throw new Error(`Packaged backend exited; inspect restricted log at ${temp}.`);
      try { ready = (await fetch('http://127.0.0.1:8082/api/health', { signal: AbortSignal.timeout(1000) })).ok; } catch { /* startup */ }
      if (ready) break;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    if (!ready) throw new Error('Packaged backend did not become healthy.');
    container = run('docker', ['run', '--detach', '--rm', '--name', `atoms-release-smoke-${randomUUID()}`,
      '--label', 'dev.atoms.release-smoke=true', '--publish', '127.0.0.1:18443:8443',
      '--mount', `type=bind,source=${proxy},target=/etc/nginx/atoms-demo,readonly`,
      '--mount', `type=bind,source=${join(proxy, 'site.conf')},target=/etc/nginx/conf.d/default.conf,readonly`,
      '--mount', `type=bind,source=${join(release, 'frontend')},target=/release/frontend,readonly`, image]);
    run('docker', ['exec', container, 'nginx', '-t']);
    const result = spawn('npm', ['run', 'test:e2e:release'],
      { cwd: join(root, 'frontend'), stdio: 'inherit' });
    const [code] = await once(result, 'exit');
    if (code !== 0) throw new Error('Release browser acceptance failed.');
    console.log('Release TLS proxy / production JAR / browser acceptance passed. No model calls.');
  } finally {
    // A failed proxy must not prevent cleanup of the owned Java child or secrets.
    if (container) {
      try { run('docker', ['stop', '--time', '5', container]); }
      catch { console.error('Proxy cleanup failed; inspect the atoms-release-smoke container.'); }
    }
    if (backend && backend.exitCode === null) {
      const exited = once(backend, 'exit');
      const timeout = setTimeout(() => backend.kill('SIGKILL'), 45000);
      backend.kill('SIGTERM');
      try { await exited; } finally { clearTimeout(timeout); }
    }
    log.end();
    await unlink(config);
    await unlink(join(proxy, 'test.key')).catch(() => {});
  }
}
main().catch((error) => { console.error(error.message); process.exitCode = 1; });
