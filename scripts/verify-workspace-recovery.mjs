import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { buildFixtureInvocation, startBuildFixture, workspaceVerificationOptions } from './verify-workspace-build.mjs';
import { assertFree } from './standalone-process.mjs';
import { runCli } from '../templates/standalone-v1/scripts/process.mjs';

const origin='http://127.0.0.1:8081';
export function interruptedEvidence(before,after) {
  assert.equal(after.id,before.id); assert.equal(after.startedAt,before.startedAt);
  assert.equal(after.status,'INTERRUPTED');assert.equal(after.errorCode,'BUILD_INTERRUPTED');
}
async function until(action,signal,timeout=60000) {
  const end=Date.now()+timeout;
  while(Date.now()<end) {signal?.throwIfAborted();const value=await action();if(value)return value;await delay(250,undefined,{signal});}
  throw new Error('Recovery verification deadline exceeded.');
}
export async function verifyRecovery({configDir,outputParent},signal) {
  const config=await realpath(configDir),parent=await realpath(outputParent);
  await assertFree(origin);
  const work=await mkdtemp(join(parent,'workspace-recovery-')),workspace=join(work,'builds');
  await mkdir(workspace,{mode:0o700});console.log(`Recovery verification: ${work}`);
  const invocation=buildFixtureInvocation(workspace,config);
  const child=spawn(invocation.command,invocation.args,{cwd:invocation.cwd,env:invocation.env,stdio:'ignore'});
  let exited=false;
  const done=new Promise(resolve=>{child.once('exit',()=>{exited=true;resolve();});child.once('error',()=>{exited=true;resolve();});});
  // Test-only forced crash: signal a currently owned live handle, never rediscover a PID.
  const crash=async()=>{if(!exited)child.kill('SIGKILL');await done;await assertFree(origin);};
  const {request}=await import('../frontend/node_modules/playwright/index.mjs');
  const api=await request.newContext({baseURL:origin,timeout:15000});
  let restarted;
  try {
    await until(async()=>{
      if(exited)throw new Error('Owned platform failed before readiness.');
      try{return (await api.get('/api/health',{timeout:1000})).status()===200;}catch{return false;}
    },signal,120000);
    let csrf=(await (await api.get('/api/csrf')).json()).token;
    assert.equal((await api.post('/api/session',{data:{},headers:{'X-CSRF-TOKEN':csrf}})).status(),200);
    csrf=(await (await api.get('/api/csrf')).json()).token;
    const post=(url,data,key=randomUUID())=>api.post(url,{data,headers:{'X-CSRF-TOKEN':csrf,'Idempotency-Key':key}});
    const fixtures=[];
    for(let i=0;i<2;i++) {const r=await post('/api/test-fixtures',{fixture:'todo-history'});assert.equal(r.status(),201);fixtures.push(await r.json());}
    const base=i=>`/api/projects/${fixtures[i].project.id}/builds`;
    const submit=async i=>{
      const r=await post(base(i),{versionId:fixtures[i].version.id});
      assert.equal(r.status(),202,`Admission failed: ${(await r.json()).error?.code ?? r.status()}`);
      return (await r.json()).buildId;
    };
    const first=await submit(0);
    const get=(i,id)=>api.get(`${base(i)}/${id}`).then(r=>r.json());
    const before=await until(async()=>{
      const b=await get(0,first);
      if(['FAILED','INTERRUPTED','CANCELLED'].includes(b.status))throw new Error(`Build stopped before crash: ${b.errorCode}`);
      return b.status==='BUILDING' && b.stage==='INSTALLING' ? b : false;
    },signal);
    const queued=await submit(1),queuedBefore=await get(1,queued);assert.equal(queuedBefore.status,'QUEUED');
    await crash();
    restarted=await startBuildFixture(workspace,config,signal);
    // HTTP health can become UP before Spring SmartLifecycle finishes recovery.
    const after=await until(async()=>{const b=await get(0,first);return b.status==='INTERRUPTED' ? b : false;},signal);
    const queuedAfter=await until(async()=>{const b=await get(1,queued);return b.status==='INTERRUPTED' ? b : false;},signal);
    interruptedEvidence(before,after);interruptedEvidence(queuedBefore,queuedAfter);
    const proof=await until(async()=>{
      try {const value=JSON.parse(await readFile(join(workspace,first,'private/settlement.json'),'utf8'));return value.settled ? value : false;}
      catch(error) {if(error.code==='ENOENT' || error instanceof SyntaxError)return false;throw error;}
    },signal);
    assert.equal(proof.errorCode,'BUILD_INTERRUPTED');
    const key=randomUUID();
    const fresh=await until(async()=>{
      const r=await post(base(0),{versionId:fixtures[0].version.id},key);
      if(r.status()===202)return (await r.json()).buildId;
      assert.equal(r.status(),503);assert.equal((await r.json()).error.code,'BUILD_ENVIRONMENT_NOT_READY');return false;
    },signal);
    await until(async()=>{const b=await get(0,fresh);return b.status==='BUILDING' ? b : false;},signal);
    assert.equal((await post(`${base(0)}/${fresh}/cancellations`,{})).status(),202);
    await until(async()=>{const b=await get(0,fresh);return b.status==='CANCELLED' ? b : false;},signal);
    assert.deepEqual(await get(0,first),after);assert.deepEqual(await get(1,queued),queuedAfter);
    const evidence={first:after,queued:queuedAfter,proof,fresh,checks:['owned-platform-sigkill','no-replay-after-restart','settlement-releases-fence','fresh-execution-cancelled']};
    await writeFile(join(work,'verification.json'),JSON.stringify(evidence,null,2),{flag:'wx',mode:0o600});
    console.log(`Recovery verification complete: ${join(work,'verification.json')}`);
  } finally {await api.dispose();try {await restarted?.stop();} finally {await crash();}}
}
if(process.argv[1] && import.meta.url===pathToFileURL(await realpath(process.argv[1])).href)
  await runCli(signal=>verifyRecovery(workspaceVerificationOptions(process.argv.slice(2)),signal),'Workspace recovery verification failed; evidence retained.');
