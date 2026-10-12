import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateBundle, verifyMaterialized } from './standalone-bundle.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const reject = () => { throw new Error('Only the current reviewed todo fixture ZIP may be verified.'); };

/** Test-only extraction, NOT a user upload/import feature. Never runs arbitrary downloaded code. */
export async function extractDownloadedFixture(path, work) {
  const info = await lstat(path);
  if (!info.isFile() || info.isSymbolicLink() || info.size < 22 || info.size > 9 * 1024 * 1024) reject();
  const archive = await realpath(path);
  const zipBytes = await readFile(archive);
  const templatePaths = (await readFile(join(root, 'templates/standalone-v1.files'), 'utf8')).trimEnd().split('\n');
  const allowed = [...templatePaths, 'application/main.js', 'application/manifest.json', 'application/styles.css', 'application/view.html'];
  const options = { timeout: 10000, maxBuffer: 2 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] };
  const names = execFileSync('unzip', ['-Z1', archive], options).toString('utf8').trimEnd().split('\n');
  if (JSON.stringify([...names].sort()) !== JSON.stringify([...allowed, 'bundle-manifest.json'].sort())) reject();
  // unzip validates CRCs. Read each bounded entry as bytes; never materialize archive links or modes.
  execFileSync('unzip', ['-t', archive], options);
  const files = names.map(name => ({ path: name, content: execFileSync('unzip', ['-p', archive, name], options).toString('base64') }));
  const manifest = JSON.parse(Buffer.from(files.find(file => file.path === 'bundle-manifest.json').content, 'base64'));
  const expected = validateBundle({ bundleId: manifest.bundleId, files }, allowed);
  // Integrity hashes alone are not permission to execute. Pin all executable/build inputs to this checkout.
  for (const name of templatePaths)
    if (!expected.files.get(name).equals(await readFile(join(root, 'templates/standalone-v1', name)))) reject();
  for (const [name, fixture] of [['view.html', 'todo.html'], ['styles.css', 'todo.css'], ['main.js', 'todo.js']])
    if (!expected.files.get('application/' + name).equals(await readFile(join(root, 'backend/src/test/resources/fixtures', fixture)))) reject();
  const application = JSON.parse(expected.files.get('application/manifest.json'));
  if (application.title !== '手工测试待办' || application.runtime !== 'web-static-v1'
    || application.stateSchemaVersion !== 1 || JSON.stringify(application.initialState) !== '{"todos":[]}') reject();
  const directory = await mkdtemp(join(work, 'engineering-bundle-'));
  for (const [name, bytes] of expected.files) {
    await mkdir(dirname(join(directory, name)), { recursive: true });
    await writeFile(join(directory, name), bytes, { flag: 'wx', mode: 0o644 });
  }
  await verifyMaterialized(directory, expected.files);
  await writeFile(join(work, 'downloaded-engineering.zip'), zipBytes, { flag: 'wx', mode: 0o600 });
  const { files: sourceFiles, ...metrics } = expected;
  return { expected, result: { directory, ...metrics },
    archiveSha256: createHash('sha256').update(zipBytes).digest('hex'), sourceVersionId: manifest.sourceVersionId };
}
