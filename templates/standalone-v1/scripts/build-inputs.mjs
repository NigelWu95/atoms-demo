import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { lstat, open, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const MAX_FILE = 2 * 1024 * 1024;
const MAX_TOTAL = 8 * 1024 * 1024;

async function checkedPath(root, relative) {
  if (!relative.split('/').every(part => /^[a-zA-Z0-9_-][a-zA-Z0-9_.-]*$/.test(part)))
    throw new Error('Invalid build input path.');
  let current = root;
  for (const part of relative.split('/')) {
    current = join(current, part);
    if ((await lstat(current)).isSymbolicLink()) throw new Error('Symbolic build inputs are not supported.');
  }
  return current;
}

// The operator owns the source tree; concurrent hostile rewrites are outside this local build contract.
// No caller-selected deletion, symlink traversal, unbounded file read or configuration scanning.
// https://nodejs.org/docs/latest-v24.x/api/fs.html#fspromisesopenpath-flags-mode
export async function readBounded(root, relative, limit = MAX_FILE) {
  const path = await checkedPath(root, relative);
  if (!(await lstat(path)).isFile()) throw new Error('Expected a regular build input.');
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > limit) throw new Error('Invalid build input size or type.');
    const buffer = Buffer.alloc(limit + 1);
    let size = 0;
    while (size < buffer.length) {
      const { bytesRead } = await file.read(buffer, size, buffer.length - size, null);
      if (!bytesRead) break;
      size += bytesRead;
    }
    if (size > limit) throw new Error('Build input exceeds size limit.');
    const bytes = buffer.subarray(0, size);
    if (new TextDecoder('utf-8', { fatal: true }).decode(bytes).includes('\0')) throw new Error('Invalid build input text.');
    return bytes;
  } finally { await file.close(); }
}

export async function listFiles(root, directory, limit = 128) {
  const paths = [];
  let entries = 0;
  async function visit(relative) {
    const path = await checkedPath(root, relative);
    const stat = await lstat(path);
    if (++entries > limit * 2) throw new Error('Too many build input entries.');
    if (stat.isDirectory()) {
      const names = await readdir(path);
      if (names.length > limit) throw new Error('Too many build input entries.');
      for (const name of names.sort()) await visit(`${relative}/${name}`);
    } else if (stat.isFile() && paths.length < limit) paths.push(relative);
    else throw new Error('Invalid build input tree.');
  }
  await visit(directory);
  return paths.sort();
}

export async function buildInputsHash(root) {
  const paths = ['backend/pom.xml', 'frontend/package.json', 'frontend/package-lock.json',
    ...await listFiles(root, 'backend/src/main')].sort();
  if (!paths.includes('backend/src/main/java/dev/atoms/standalone/StandaloneApplication.java')
      || !paths.includes('backend/src/main/resources/application.yml')) throw new Error('Missing runtime build inputs.');
  const hash = createHash('sha256').update('atoms-controlled-build-inputs-v1\0');
  let size = 0;
  for (const path of paths) {
    if (!/\.(?:java|yml|sql|js|html|xml|json)$/.test(path)) throw new Error('Unsupported runtime build input.');
    const bytes = await readBounded(root, path);
    size += bytes.length;
    if (size > MAX_TOTAL) throw new Error('Build inputs exceed size limit.');
    hash.update(`${path}\0${bytes.length}\0`).update(bytes);
  }
  return hash.digest('hex');
}
