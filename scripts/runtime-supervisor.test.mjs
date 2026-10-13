import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PassThrough } from 'node:stream';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
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

test('stop removes a stubborn descendant and does not stop an unrelated process', async () => {
  const directory=await mkdtemp(join(tmpdir(),'atoms-runtime-descendant-'));
  const unrelated=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
  try {
    const parentInput=new PassThrough();const id=randomUUID();
    const childScript='process.on("SIGTERM",()=>{});setInterval(()=>{},1000)';
    const script=`const child=require("node:child_process").spawn(process.execPath,["-e",${JSON.stringify(childScript)}],{stdio:"ignore"});require("node:fs").writeFileSync("child.pid",String(child.pid));setInterval(()=>{},1000)`;
    assert.doesNotThrow(()=>new Function(script));
    const running=superviseRuntime({command:process.execPath,args:['-e',script],cwd:directory,id,parentInput,graceMs:100});
    let pid;
    try {
      for(let i=0;i<100;i++){try{pid=Number(await readFile(join(directory,'child.pid'),'utf8'));break;}catch{await delay(25);}}
      assert.ok(Number.isSafeInteger(pid));
    } finally {parentInput.write('STOP\n');await running;}
    assert.throws(()=>process.kill(pid,0),error=>error.code==='ESRCH');
    assert.equal(unrelated.exitCode,null);
    assert.doesNotThrow(()=>process.kill(unrelated.pid,0));
  }finally{const exited=once(unrelated,'exit');unrelated.kill('SIGTERM');await exited;}
});
