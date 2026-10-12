import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { startOwned, assertFree } from './standalone-process.mjs';
import { verifyStandalone } from './verify-standalone.mjs';
import { runCli, toolEnvironment } from '../templates/standalone-v1/scripts/process.mjs';

const root=fileURLToPath(new URL('..',import.meta.url));
const origin='http://127.0.0.1:8081';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
export function workspaceVerificationOptions(args) {
  if(args.length!==4 || args[0]!=='--config-dir' || args[2]!=='--output-parent' || !args[1] || !args[3])
    throw new Error('Usage: verify-workspace-build.mjs --config-dir <existing-directory> --output-parent <existing-directory>');
  return {configDir:args[1],outputParent:args[3]};
}
export function completedBuild(build,project,version) {
  if(!build || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(build.id) || build.projectId!==project || build.versionId!==version
      || build.status!=='SUCCEEDED' || build.stage!=='COMPLETE' || build.errorCode!==null || build.buildPolicyVersion!=='native-v1'
      || ['bundleId','archiveSha256','loadedRuntimeId','jarSha256','templateDigest'].some(key=>!/^[a-f0-9]{64}$/.test(build[key])))
    throw new Error('Build evidence did not establish a verified artifact.');
  return build;
}
export function buildFixtureInvocation(workspace,config) {
  const env=toolEnvironment(),java=join(env.JAVA_HOME,'bin/java');
  // The platform fixture uses its existing TEST_DB_PASSWORD, never the standalone DB config.
  return {command:java,args:['-Xmx384m','-Dloader.path='+join(root,'backend/target/test-classes'),
    '-Dloader.main=dev.atoms.demo.workspace.BuildFixture','-cp',join(root,'backend/target/atoms-demo-0.1.0-SNAPSHOT.jar'),
    'org.springframework.boot.loader.launch.PropertiesLauncher',workspace,process.execPath,java,join(config,'test.properties')],cwd:join(root,'backend'),env};
}
export async function startBuildFixture(workspace,config,signal) {
  const invocation=buildFixtureInvocation(workspace,config);
  return startOwned(invocation.command,invocation.args,{...invocation,baseURL:origin,signal});
}
export async function verifyWorkspaceBuild({configDir,outputParent},signal) {
  const config=await realpath(configDir),parent=await realpath(outputParent);
  await assertFree(origin);
  const work=await mkdtemp(join(parent,'workspace-verification-'));
  const workspace=join(work,'builds');await mkdir(workspace,{mode:0o700});
  console.log(`Workspace verification: ${work}`);
  const platform=await startBuildFixture(workspace,config,signal);
  const {request}=await import('../frontend/node_modules/playwright/index.mjs');
  const api=await request.newContext({baseURL:origin,timeout:15000});
  let build,fixture,archive;
  try {
    let csrf=(await (await api.get('/api/csrf')).json()).token;
    assert.equal((await api.post('/api/session',{data:{},headers:{'X-CSRF-TOKEN':csrf}})).status(),200);
    csrf=(await (await api.get('/api/csrf')).json()).token;
    const created=await api.post('/api/test-fixtures',{data:{fixture:'todo-history'},headers:{'X-CSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()}});
    assert.equal(created.status(),201);fixture=await created.json();
    const base=`/api/projects/${fixture.project.id}/builds`;
    const accepted=await api.post(base,{data:{versionId:fixture.version.id},headers:{'X-CSRF-TOKEN':csrf,'Idempotency-Key':randomUUID()}});
    if(accepted.status()!==202) throw new Error(`Build admission failed (${accepted.status()}, ${(await accepted.json()).error?.code ?? 'UNKNOWN'}).`);
    const {buildId}=await accepted.json();
    let previous='';const until=Date.now()+660000;
    while(Date.now()<until) {
      signal?.throwIfAborted();
      const response=await api.get(`${base}/${buildId}`);assert.equal(response.status(),200);
      build=await response.json();
      if(`${build.status}/${build.stage}`!==previous) {previous=`${build.status}/${build.stage}`;console.log(`Build ${buildId}: ${previous}`);}
      if(!['QUEUED','BUILDING','CANCELLING'].includes(build.status)) break;
      await delay(1000,undefined,{signal});
    }
    completedBuild(build,fixture.project.id,fixture.version.id);
    archive=join(work,'build-engineering.zip');
    const download=await api.get(`${base}/${buildId}/engineering.zip`);assert.equal(download.status(),200);
    const bytes=await download.body();assert.ok(bytes.length<=9*1024*1024);assert.equal(hash(bytes),build.archiveSha256);
    await writeFile(archive,bytes,{flag:'wx',mode:0o600});
    const descriptor=JSON.parse(await readFile(join(workspace,buildId,'result/build.json'),'utf8'));
    for(const key of ['projectId','versionId','templateDigest','buildPolicyVersion','bundleId','archiveSha256','loadedRuntimeId','jarSha256']) assert.equal(descriptor[key],build[key]);
    assert.equal(descriptor.buildId,build.id);
    assert.equal(hash(await readFile(join(workspace,buildId,'result/app.jar'))),build.jarSha256);
    await writeFile(join(work,'api-build.json'),JSON.stringify(build,null,2),{flag:'wx',mode:0o600});
  } finally {await api.dispose();await platform.stop();}
  const rebuild=await verifyStandalone({configDir:config,outputParent:work,downloadedZip:archive},signal);
  assert.equal(rebuild.loadedRuntimeId,build.loadedRuntimeId);assert.equal(rebuild.archiveSha256,build.archiveSha256);
  const {verifyRuntimePair}=await import('./workspace-runtime-pair.mjs');
  await verifyRuntimePair({configDir:config,output:work,loadedRuntimeId:build.loadedRuntimeId,
    firstJar:join(workspace,build.id,'result/app.jar'),secondJar:join(rebuild.directory,'backend/target/app.jar'),
    firstHash:build.jarSha256,secondHash:rebuild.jarSha256},signal);
  const report={work,workspace,build,rebuild,archive,checks:['real-api-build','frozen-archive-hash','new-directory-full-build','runtime-identity-parity','two-process-persistence']};
  await writeFile(join(work,'verification.json'),JSON.stringify(report,null,2),{flag:'wx',mode:0o600});
  console.log(`Workspace verification complete: ${join(work,'verification.json')}`);return report;
}
if(process.argv[1] && import.meta.url===pathToFileURL(await realpath(resolve(process.argv[1]))).href) {
  await runCli(signal=>verifyWorkspaceBuild(workspaceVerificationOptions(process.argv.slice(2)),signal),
    'Workspace build verification failed; existing evidence was retained and only owned processes were stopped.');
}
