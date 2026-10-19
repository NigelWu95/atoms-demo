import { constants } from 'node:fs';
import { lstat, mkdir, mkdtemp, open, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export async function requireDirectory(path) {
  const stat = await lstat(path);
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('Expected a real project directory.');
}

// A new stage per build, outside target/. No source files are rewritten or deleted.
// These checks reject accidental links/secret files; they are not an OS sandbox.
export async function packageFrontend(root) {
  for (const name of ['frontend', 'frontend/dist']) await requireDirectory(join(root, name));
  const staging = await mkdtemp(join(tmpdir(), 'full-project-resources-'));
  let entries = 0;
  let total = 0;
  async function copy(directory, destination, depth) {
    if (depth > 12) throw new Error('Frontend output is too deep.');
    await mkdir(destination, { mode: 0o700 });
    for (const name of (await readdir(directory)).sort()) {
      if (++entries > 1024 || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(name)
          || /\.(pem|key|p12|pfx|properties)$/i.test(name)) throw new Error('Unexpected frontend output.');
      const source = join(directory, name);
      const target = join(destination, name);
      const stat = await lstat(source);
      if (stat.isSymbolicLink()) throw new Error('Linked frontend output is not allowed.');
      if (stat.isDirectory()) { await copy(source, target, depth + 1); continue; }
      if (!stat.isFile() || stat.nlink !== 1) throw new Error('Unexpected frontend output.');
      const file = await open(source, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        const current = await file.stat();
        if (!current.isFile() || current.nlink !== 1 || current.size > 8 * 1024 * 1024)
          throw new Error('Frontend file exceeds the package limit.');
        const bytes = Buffer.alloc(8 * 1024 * 1024 + 1);
        let size = 0;
        while (size < bytes.length) {
          const result = await file.read(bytes, size, bytes.length - size, null);
          if (!result.bytesRead) break;
          size += result.bytesRead;
        }
        total += size;
        if (size > 8 * 1024 * 1024 || total > 32 * 1024 * 1024)
          throw new Error('Frontend output exceeds the package limit.');
        await writeFile(target, bytes.subarray(0, size), { flag: 'wx', mode: 0o600 });
      } finally { await file.close(); }
    }
  }
  await copy(join(root, 'frontend/dist'), join(staging, 'static'), 0);
  const index = await lstat(join(staging, 'static/index.html'));
  if (!index.isFile() || index.size === 0) throw new Error('Frontend entry is missing.');
  return staging;
}
