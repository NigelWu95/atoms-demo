import { createHash } from 'node:crypto';
import { chmod, lstat, mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

const MAX_FILE = 2 * 1024 * 1024;
const MAX_TOTAL = 8 * 1024 * 1024;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const reject = () => { throw new Error('Invalid engineering bundle.'); };
const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
const canonical = value => Array.isArray(value) ? value.map(canonical)
  : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;

function validatePaths(paths) {
  const prefixes = new Map();
  const files = new Set(paths.map(path => typeof path === 'string' ? path.toLowerCase() : ''));
  if (files.size !== paths.length) reject();
  for (const path of paths) {
    if (typeof path !== 'string' || !/^[A-Za-z0-9._/-]+$/.test(path)) reject();
    let prefix = '';
    const parts = path.split('/');
    for (const [i, part] of parts.entries()) {
      if (!part || part === '.' || part === '..' || part.endsWith('.') || /^\.env/i.test(part)
        || /^(\.git|\.local|node_modules|target|dist|coverage|test-results|playwright-report|application.properties)$/i.test(part)
        || /\.(pem|key|log)$/i.test(part)) reject();
      prefix += (prefix ? '/' : '') + part;
      const folded = prefix.toLowerCase();
      if ((prefixes.has(folded) && prefixes.get(folded) !== prefix) || (i < parts.length - 1 && files.has(folded))) reject();
      prefixes.set(folded, prefix);
    }
  }
}

/** Trusted fixture transport only, not an import API. Hashes verify consistency, not authorship. */
export function validateBundle(bundle, allowedPaths) {
  if (!exactKeys(bundle, ['bundleId', 'files']) || !/^[a-f0-9]{64}$/.test(bundle.bundleId)
    || !Array.isArray(bundle.files) || bundle.files.length > 128) reject();
  const expected = [...allowedPaths, 'bundle-manifest.json'].sort();
  validatePaths(expected);
  if (JSON.stringify(bundle.files.map(file => file.path).sort()) !== JSON.stringify(expected)) reject();
  const files = new Map();
  let total = 0;
  for (const file of bundle.files) {
    if (!exactKeys(file, ['path', 'content']) || typeof file.content !== 'string'
      || file.content.length > Math.ceil(MAX_FILE / 3) * 4) reject();
    const bytes = Buffer.from(file.content, 'base64');
    if (bytes.toString('base64') !== file.content || bytes.length > MAX_FILE) reject();
    total += bytes.length;
    if (total > MAX_TOTAL) reject();
    files.set(file.path, bytes);
  }
  let manifest;
  try { manifest = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(files.get('bundle-manifest.json'))); }
  catch { reject(); }
  if (!exactKeys(manifest, ['bundleId', 'bundleFormatVersion', 'templateId', 'templateVersion', 'sourceVersionId',
    'sourceContentHash', 'sourceRuntime', 'dataMode', 'files']) || manifest.bundleId !== bundle.bundleId
    || manifest.bundleFormatVersion !== 1 || manifest.templateId !== 'vue-spring-postgres' || manifest.templateVersion !== '1.0.0'
    || manifest.sourceRuntime !== 'web-static-v1' || manifest.dataMode !== 'INITIAL_ONLY'
    || !/^[a-f0-9]{64}$/.test(manifest.sourceContentHash)
    || !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(manifest.sourceVersionId)
    || !Array.isArray(manifest.files) || manifest.files.length !== allowedPaths.length) reject();
  const { bundleId, ...identity } = manifest;
  if (hash(JSON.stringify(canonical(identity))) !== bundleId) reject();
  if (JSON.stringify(manifest.files.map(file => file.path)) !== JSON.stringify([...allowedPaths].sort())) reject();
  for (const entry of manifest.files) {
    const bytes = files.get(entry.path);
    if (!exactKeys(entry, ['path', 'size', 'sha256']) || !bytes || entry.size !== bytes.length || entry.sha256 !== hash(bytes)) reject();
  }
  return { files, bundleId, fileCount: files.size, totalBytes: total,
    maxFileBytes: Math.max(...[...files.values()].map(bytes => bytes.length)) };
}

/** Never reuse, overwrite or delete a project directory; invalid input writes nothing. */
export async function materializeBundle(bundle, allowedPaths, outputParent) {
  const verified = validateBundle(bundle, allowedPaths);
  const parent = await realpath(outputParent);
  if (!(await lstat(parent)).isDirectory()) reject();
  const directory = await mkdtemp(join(parent, 'engineering-bundle-'));
  for (const [path, bytes] of verified.files) {
    const target = join(directory, path);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, bytes, { flag: 'wx', mode: 0o644 });
    if (path === 'backend/mvnw') await chmod(target, 0o755);
  }
  await verifyMaterialized(directory, verified.files);
  const { files, ...metrics } = verified;
  return { directory, ...metrics };
}

export async function verifyMaterialized(directory, expectedFiles) {
  for (const [path, bytes] of expectedFiles) {
    let current = directory;
    for (const part of path.split('/')) {
      current = join(current, part);
      if ((await lstat(current)).isSymbolicLink()) reject();
    }
    const info = await lstat(current);
    if (!info.isFile() || info.size !== bytes.length || hash(await readFile(current)) !== hash(bytes)) reject();
  }
}
