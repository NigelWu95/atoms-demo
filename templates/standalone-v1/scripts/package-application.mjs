import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, open, realpath, mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const RESOURCE_PATHS = Object.freeze([
  'application/main.js', 'application/manifest.json', 'application/styles.css', 'application/view.html',
  'runtime/bootstrap.js', 'runtime/document.html',
]);

// Format v1: domain prefix, then sorted UTF-8 path + NUL + decimal byte length + NUL + bytes.
// Hash actual packaged bytes, never mtimes, absolute paths, instance IDs or source-version metadata.
export function runtimeId(files) {
  if (Object.keys(files).sort().join('\n') !== RESOURCE_PATHS.join('\n')) throw new Error('Invalid resource set.');
  const hash = createHash('sha256').update('atoms-runtime-content-v1\0');
  for (const path of RESOURCE_PATHS) {
    if (!Buffer.isBuffer(files[path])) throw new Error('Expected resource bytes.');
    hash.update(`${path}\0${files[path].length}\0`).update(files[path]);
  }
  return hash.digest('hex');
}

async function readResource(root, path) {
  const relative = path.startsWith('application/') ? path : `backend/src/main/resources/${path}`;
  let current = root;
  for (const segment of relative.split('/')) {
    current = join(current, segment);
    if ((await lstat(current)).isSymbolicLink()) throw new Error('Symbolic links are not application resources.');
  }
  const limit = path.endsWith('manifest.json') ? 272 * 1024 : 120 * 1024;
  // Local builds assume the project tree is not concurrently rewritten by another user.
  // O_NOFOLLOW + bounded descriptor reads also reject final-component links and growing files.
  // https://nodejs.org/docs/latest-v24.x/api/fs.html#fspromisesopenpath-flags-mode
  const file = await open(current, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > limit) throw new Error('Invalid resource size or type.');
    const buffer = Buffer.alloc(limit + 1);
    let size = 0;
    while (size < buffer.length) {
      const { bytesRead } = await file.read(buffer, size, buffer.length - size, null);
      if (!bytesRead) break;
      size += bytesRead;
    }
    if (size > limit) throw new Error('Resource exceeds size limit.');
    const bytes = buffer.subarray(0, size);
    if (new TextDecoder('utf-8', { fatal: true }).decode(bytes).includes('\0')) throw new Error('Invalid resource text.');
    return bytes;
  } finally { await file.close(); }
}

export async function packageApplication(projectRoot, outputParent) {
  const root = await realpath(resolve(projectRoot));
  const files = {};
  for (const path of RESOURCE_PATHS) files[path] = await readResource(root, path);
  if (['main.js', 'styles.css', 'view.html'].reduce((size, name) => size + files[`application/${name}`].length, 0) > 120 * 1024)
    throw new Error('Application source exceeds size limit.');
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
