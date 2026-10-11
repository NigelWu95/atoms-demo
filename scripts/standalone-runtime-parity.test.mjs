import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../', import.meta.url);
test('standalone runtime keeps the platform document and SDK byte-for-byte', async () => {
  for (const name of ['bootstrap.js', 'document.html']) {
    const original = await readFile(new URL(`backend/src/main/resources/runtime/${name}`, root));
    const standalone = await readFile(new URL(`templates/standalone-v1/backend/src/main/resources/runtime/${name}`, root));
    assert.deepEqual(standalone, original);
  }
});
test('standalone HTML allows exactly the same markup as the platform', async () => {
  const original = await readFile(new URL('backend/src/main/java/dev/atoms/demo/artifact/ArtifactValidator.java', root), 'utf8');
  const standalone = await readFile(new URL('templates/standalone-v1/backend/src/main/java/dev/atoms/standalone/ApplicationHtml.java', root), 'utf8');
  const rules = source => source.slice(source.indexOf('new Safelist()'), source.indexOf('.addAttributes("text", "x", "y", "fill")') + 39);
  assert.ok(rules(original).length > 2000);
  assert.equal(rules(standalone), rules(original));
});
