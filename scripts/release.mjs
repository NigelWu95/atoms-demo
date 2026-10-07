import { chmod, copyFile, lstat, mkdir, mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { join, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const serverFiles = ['start.sh', 'application.properties.example', 'atoms-demo.service',
  'nginx-site.conf', 'nginx-routes.conf', 'nginx-static-headers.conf', 'init-db.sql', 'README.md'];
const hash = (contents) => createHash('sha256').update(contents).digest('hex');

// Explicit allowlist: never archive the checkout, local config, test fixtures or DB data.
export async function assembleRelease(root, output, metadata) {
  const sources = serverFiles.map((name) => [`deploy/server/${name}`, `server/${name}`]);
  const jars = (await readdir(join(root, 'backend/target'))).filter((name) => /^atoms-demo-[\w.-]+\.jar$/.test(name));
  if (jars.length !== 1) throw new Error('Expected one production JAR; run a clean build.');
  sources.push([`backend/target/${jars[0]}`, 'backend/atoms-demo.jar']);
  async function frontend(path) {
    for (const name of await readdir(join(root, path))) {
      const source = `${path}/${name}`;
      const stat = await lstat(join(root, source));
      if (stat.isSymbolicLink()) throw new Error('Release inputs must not contain symlinks.');
      if (stat.isDirectory() && source === 'frontend/dist/assets') await frontend(source);
      else if (stat.isFile() && (source === 'frontend/dist/index.html' || source === 'frontend/dist/favicon.svg'
        || /^frontend\/dist\/assets\/[\w.-]+\.(js|css|woff2?|svg|png|webp|ico)$/.test(source))) {
        sources.push([source, source.replace('/dist/', '/')]);
      } else throw new Error('Unexpected frontend build file; inspect before expanding the allowlist.');
    }
  }
  await frontend('frontend/dist');
  if (!sources.some(([source]) => source === 'frontend/dist/index.html')) throw new Error('Missing frontend index.');
  for (const [source] of sources) {
    if (!(await lstat(join(root, source))).isFile()) throw new Error('Release input must be a regular file.');
  }
  await mkdir(output, { recursive: true });
  const directory = await mkdtemp(join(output, 'atoms-demo-'));
  await chmod(directory, 0o755);
  const files = [];
  for (const [source, destination] of sources) {
    const target = join(directory, destination);
    await mkdir(dirname(target), { recursive: true });
    await copyFile(join(root, source), target);
    await chmod(target, destination === 'server/start.sh' ? 0o755 : 0o644);
    files.push({ path: destination, sha256: hash(await readFile(target)) });
  }
  const manifest = { ...metadata, builtAt: new Date().toISOString(), java: 17, postgres: 17, files };
  await writeFile(join(directory, 'release.json'), JSON.stringify(manifest, null, 2) + '\n');
  await writeFile(join(directory, 'SHA256SUMS'), files.map((file) => `${file.sha256}  ${file.path}\n`).join(''));
  const archive = directory + '.tar.gz';
  const packed = spawnSync('tar', ['-czf', archive, '-C', output, basename(directory)], {
    env: { ...process.env, COPYFILE_DISABLE: '1' }, encoding: 'utf8',
  });
  if (packed.error || packed.status !== 0) throw new Error('Release archive could not be created.');
  await writeFile(archive + '.sha256', `${hash(await readFile(archive))}  ${basename(archive)}\n`);
  return { directory, archive };
}

function run(command, args, cwd, capture = false) {
  const result = spawnSync(command, args, { cwd, stdio: capture ? 'pipe' : 'inherit', encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(`${command} failed; no successful release was produced.`);
  return result.stdout?.trim();
}

async function build() {
  const root = fileURLToPath(new URL('..', import.meta.url));
  if (!/^24\.20\./.test(process.versions.node)) throw new Error('Use the project Node 24.20.x version.');
  if (!/^11\.19\./.test(run('npm', ['--version'], root, true))) throw new Error('Use npm 11.19.x.');
  run(process.execPath, ['--test', 'scripts/release.test.mjs'], root);
  for (const task of ['typecheck', 'lint', 'format:check', 'test:unit', 'build']) {
    run('npm', ['run', task], join(root, 'frontend'));
  }
  run('./mvnw', ['-B', 'clean', 'verify'], join(root, 'backend'));
  const listing = run('jar', ['tf', 'backend/target/atoms-demo-0.1.0-SNAPSHOT.jar'], root, true);
  if (/BOOT-INF\/classes\/(dev\/atoms\/fixtures|fixtures\/|application-(test|fixture))/.test(listing)) {
    throw new Error('Test fixtures must not enter a production JAR.');
  }
  const revision = run('git', ['rev-parse', 'HEAD'], root, true);
  const dirty = run('git', ['status', '--porcelain'], root, true).length > 0;
  const result = await assembleRelease(root, join(root, 'dist/releases'), { revision, dirty });
  console.log(`Release: ${result.archive}\nSHA-256: ${result.archive}.sha256`);
  console.log('No deployment or model calls performed. Run the browser/remote acceptance checklist before exposure.');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  build().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
