import test from 'node:test';
import assert from 'node:assert/strict';
import { interruptedEvidence } from './verify-workspace-recovery.mjs';

test('restart evidence rejects replay, fake success and a changed starting identity',()=>{
  const before={id:'same',startedAt:'original'};
  const after={id:'same',startedAt:'original',status:'INTERRUPTED',errorCode:'BUILD_INTERRUPTED'};
  assert.doesNotThrow(()=>interruptedEvidence(before,after));
  for(const change of [{id:'other'},{startedAt:'replayed'},{status:'SUCCEEDED'},{errorCode:null}])
    assert.throws(()=>interruptedEvidence(before,{...after,...change}));
});
