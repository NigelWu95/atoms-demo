import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { randomUUID } from 'node:crypto';
import { superviseRuntime } from '../backend/src/main/resources/workspace/runtime-supervisor.mjs';

for (const mode of ['stop', 'disconnect', 'exit']) {
  test(`runtime ${mode} proves its process group exited without exposing output`, async () => {
    const directory=await mkdtemp(join(tmpdir(),'atoms-runtime-process-'));
    const parentInput=new PassThrough(); const id=randomUUID();
    const script=mode==='exit' ? 'console.error("secret-canary");process.exit(2)' : 'console.error("secret-canary");setInterval(()=>{},1000)';
    const running=superviseRuntime({command:process.execPath,args:['-e',script],cwd:directory,
      id,parentInput,graceMs:100});
    if(mode==='stop') setTimeout(()=>parentInput.write('STOP\n'),200);
    if(mode==='disconnect') setTimeout(()=>parentInput.end(),200);
    const result=await running;
    assert.equal(result.settled,true);
    const proof=await readFile(join(directory,'exit.json'),'utf8');
    assert.deepEqual(JSON.parse(proof),{id,settled:true});
    assert.equal(proof.includes('secret-canary'),false);
  });
}
