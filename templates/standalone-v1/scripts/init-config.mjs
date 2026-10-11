import { randomUUID } from 'node:crypto';
import { open, readFile } from 'node:fs/promises';
import { isAbsolute } from 'node:path';

const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== '--output' || !isAbsolute(args[1])
  || !args[1].endsWith('.properties') || args[1].includes('\0')) {
  console.error('CONFIG_USAGE: use --output with an absolute .properties file path.');
  process.exitCode = 1;
} else {
  try {
    const example = await readFile(new URL('../config/application.properties.example', import.meta.url), 'utf8');
    const content = example.replace('APP_INSTANCE_ID=GENERATE_INSTANCE_ID', `APP_INSTANCE_ID=${randomUUID()}`);
    // Exclusive creation rejects existing files, including dangling symlinks.
    // https://nodejs.org/download/release/v24.20.0/docs/api/fs.html#file-system-flags
    const file = await open(args[1], 'wx', 0o600);
    try {
      await file.writeFile(content, 'utf8');
    } finally {
      await file.close();
    }
    console.log('CONFIG_CREATED: edit database settings before starting.');
  } catch (error) {
    console.error(error.code === 'EEXIST'
      ? 'CONFIG_EXISTS: configuration already exists; nothing was overwritten.'
      : 'CONFIG_INIT_FAILED: cannot create configuration; check the parent directory and permissions.');
    process.exitCode = 1;
  }
}
