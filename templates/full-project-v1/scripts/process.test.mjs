import assert from 'node:assert/strict';
import test from 'node:test';
import { CommandFailure, runCommand, toolEnvironment } from './process.mjs';

test('tool environment does not pass application, model or injected runtime options', () => {
  const input = { PATH: '/usr/bin:/bin', LANG: 'C', DEEPSEEK_API_KEY: 'MODEL_CANARY',
    APP_DB_PASSWORD: 'RUNTIME_CANARY', NODE_OPTIONS: '--require private.js',
    JAVA_TOOL_OPTIONS: '-javaagent:private.jar', MAVEN_OPTS: '-Dprivate=value',
    SPRING_APPLICATION_JSON: 'private', NPM_CONFIG_USERCONFIG: '/private/config' };
  assert.deepEqual(toolEnvironment(input), { PATH: '/usr/bin:/bin', LANG: 'C' });
});

test('fixed argv is literal and the actual child receives only the supplied environment', async () => {
  await runCommand(process.execPath, ['-e',
    'if (process.argv[1] !== "$(false); false" || process.env.DEEPSEEK_API_KEY) process.exit(2)',
    '$(false); false'], { env: toolEnvironment({ PATH: '/usr/bin:/bin', DEEPSEEK_API_KEY: 'MODEL_CANARY' }) });
});

test('nonzero exit, missing executable and pre-cancellation do not report success', async () => {
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(7)']),
    error => error instanceof CommandFailure && error.code === 7);
  await assert.rejects(runCommand('/nonexistent/full-project-tool', []), CommandFailure);
  const controller = new AbortController();
  controller.abort('SIGTERM');
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(0)'], { signal: controller.signal }), CommandFailure);
});

test('cancellation terminates the owned child and is not a successful result', async () => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort('SIGTERM'), 100);
  try {
    await assert.rejects(runCommand(process.execPath, ['-e', 'setInterval(() => {}, 1000)'],
      { signal: controller.signal }), error => error instanceof CommandFailure && error.signal === 'SIGTERM');
  } finally { clearTimeout(timer); }
});
