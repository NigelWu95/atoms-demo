import test from 'node:test';
import assert from 'node:assert/strict';
import { browserWorkspace } from './serve-workspace-fixture.mjs';

test('browser fixture requires explicit absolute private output and config directories', () => {
  for (const env of [{}, {WORKSPACE_BROWSER_OUTPUT:'relative',WORKSPACE_BROWSER_CONFIG:'/tmp/config'},
    {WORKSPACE_BROWSER_OUTPUT:'/tmp/work',WORKSPACE_BROWSER_CONFIG:''}])
    assert.throws(()=>browserWorkspace(env), /explicit absolute/);
  assert.deepEqual(browserWorkspace({WORKSPACE_BROWSER_OUTPUT:'/tmp/work',WORKSPACE_BROWSER_CONFIG:'/tmp/config'}),
    {output:'/tmp/work',config:'/tmp/config'});
});
