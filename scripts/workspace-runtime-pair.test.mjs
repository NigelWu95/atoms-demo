import test from 'node:test';
import assert from 'node:assert/strict';
import { runtimePairOptions } from './workspace-runtime-pair.mjs';
test('two-instance verification requires exact local artifacts and identities',()=>{
  const options={configDir:'/private/config',output:'/private/output',firstJar:'/private/a.jar',secondJar:'/private/b.jar',
    firstHash:'a'.repeat(64),secondHash:'b'.repeat(64),loadedRuntimeId:'c'.repeat(64)};
  assert.equal(runtimePairOptions(options),options);
  for(const bad of [{firstJar:'relative.jar'},{firstHash:'bad'},{loadedRuntimeId:null},{configDir:''}]) assert.throws(()=>runtimePairOptions({...options,...bad}));
});
