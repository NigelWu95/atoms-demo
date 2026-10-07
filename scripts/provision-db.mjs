import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

// This administrator-only setup creates restricted roles and dedicated schemas.
// It never resets a role password, drops a database, or recreates a Docker volume.
const values = Object.fromEntries(readFileSync(new URL('../.env.local', import.meta.url), 'utf8')
  .split(/\r?\n/).filter((line) => /^[A-Z_]+=/.test(line))
  .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
const passwords = [values.APP_DB_PASSWORD, values.TEST_DB_PASSWORD];
if (passwords.some((password) => !/^[a-f0-9]{64}$/.test(password ?? ''))) {
  throw new Error('.env.local must contain separate 64-character random hex database passwords.');
}
const sql = `
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'atoms_app') THEN
    CREATE ROLE atoms_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${passwords[0]}';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'atoms_test') THEN
    CREATE ROLE atoms_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${passwords[1]}';
  END IF;
END $$;
SELECT 'CREATE DATABASE atoms_demo_test' WHERE NOT EXISTS
  (SELECT FROM pg_database WHERE datname = 'atoms_demo_test')\n\\gexec
\\connect atoms_demo
GRANT CONNECT ON DATABASE atoms_demo TO atoms_app;
CREATE SCHEMA IF NOT EXISTS atoms AUTHORIZATION atoms_app;
\\connect atoms_demo_test
GRANT CONNECT ON DATABASE atoms_demo_test TO atoms_test;
CREATE SCHEMA IF NOT EXISTS atoms AUTHORIZATION atoms_test;
`;
const result = spawnSync('docker', ['exec', '-i', 'atoms-demo-postgres', 'psql',
  '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'], { input: sql, encoding: 'utf8' });
if (result.error || result.status !== 0) {
  let message = result.error?.message ?? result.stderr;
  for (const password of passwords) message = message.replaceAll(password, '[REDACTED]');
  console.error(message);
  process.exit(1);
}
console.log('Database roles and isolated application/test schemas are ready. No data was deleted.');
