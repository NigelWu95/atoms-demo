import { createHash } from 'node:crypto';
import { realpath, mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { buildInputsHash, listFiles, readBounded } from './build-inputs.mjs';

export const RESOURCE_PATHS = Object.freeze([
  'application/main.js', 'application/manifest.json', 'application/styles.css', 'application/view.html',
  'runtime/bootstrap.js', 'runtime/document.html',
]);

// Format v2 adds the actual frontend outputs and a digest of controlled compilation inputs.
// Domain prefix, then sorted UTF-8 path + NUL + decimal byte length + NUL + bytes.
// Hash actual packaged bytes, never mtimes, absolute paths, instance IDs or source-version metadata.
export function runtimeId(files) {
  const paths = [...RESOURCE_PATHS, 'runtime/build.txt', ...staticPaths(files['runtime/build.txt'])].sort();
  if (Object.keys(files).sort().join('\n') !== paths.join('\n')) throw new Error('Invalid resource set.');
  const hash = createHash('sha256').update('atoms-runtime-content-v2\0');
  let total = 0;
  for (const path of paths) {
    if (!Buffer.isBuffer(files[path])) throw new Error('Expected resource bytes.');
    total += files[path].length;
    if (files[path].length > 2 * 1024 * 1024 || total > 8 * 1024 * 1024) throw new Error('Runtime resources exceed limits.');
    hash.update(`${path}\0${files[path].length}\0`).update(files[path]);
  }
  return hash.digest('hex');
}

export function staticPaths(index) {
  if (!Buffer.isBuffer(index) || index.length > 8192) throw new Error('Missing runtime build index.');
  const [inputsHash, ...paths] = new TextDecoder('utf-8', { fatal: true }).decode(index).split('\n');
  if (paths.pop() !== '' || !/^[a-f0-9]{64}$/.test(inputsHash) || paths.length > 64
      || !paths.includes('static/index.html') || !paths.some(path => /^static\/assets\/[^/]+\.js$/.test(path))
      || paths.some(path => !/^static\/(?:index\.html|favicon\.svg|assets\/[a-zA-Z0-9_-]+\.(?:js|css))$/.test(path))
      || new Set(paths.map(path => path.toLowerCase())).size !== paths.length
      || [...paths].sort().join('\n') !== paths.join('\n')) throw new Error('Invalid static resource index.');
  return paths;
}

export async function collectResources(root) {
  const files = {};
  for (const path of RESOURCE_PATHS) files[path] = await readBounded(root,
    path.startsWith('application/') ? path : `backend/src/main/resources/${path}`,
    path.endsWith('manifest.json') ? 272 * 1024 : 120 * 1024);
  if (['main.js', 'styles.css', 'view.html'].reduce((size, name) => size + files[`application/${name}`].length, 0) > 120 * 1024)
    throw new Error('Application source exceeds size limit.');
  const paths = (await listFiles(root, 'frontend/dist', 64)).map(path => path.replace('frontend/dist/', 'static/'));
  files['runtime/build.txt'] = Buffer.from(await buildInputsHash(root) + '\n' + paths.join('\n') + '\n');
  for (const path of staticPaths(files['runtime/build.txt']))
    files[path] = await readBounded(root, path.replace('static/', 'frontend/dist/'));
  return files;
}

export async function packageApplication(projectRoot, outputParent) {
  const root = await realpath(resolve(projectRoot));
  const files = await collectResources(root);
  const loadedRuntimeId = runtimeId(files);
  // Never overwrite an existing build or delete a caller-selected directory.
  const directory = await mkdtemp(join(await realpath(resolve(outputParent)), 'application-resources-'));
  for (const [path, bytes] of Object.entries({ ...files, 'runtime/content.sha256': loadedRuntimeId + '\n' })) {
    await mkdir(dirname(join(directory, path)), { recursive: true });
    await writeFile(join(directory, path), bytes, { flag: 'wx' });
  }
  return { directory, loadedRuntimeId };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv.length !== 4) {
    console.error('Usage: node scripts/package-application.mjs <project-root> <existing-output-parent>');
    process.exitCode = 1;
  } else {
    try { console.log(JSON.stringify(await packageApplication(process.argv[2], process.argv[3]))); }
    catch { console.error('Application resources could not be packaged.'); process.exitCode = 1; }
  }
}
