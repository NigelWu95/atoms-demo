import test from 'node:test';
import assert from 'node:assert/strict';
import { workspaceVerificationOptions, completedBuild } from './verify-workspace-build.mjs';

test('accepts only explicit local verification directories without execution flags', () => {
  assert.deepEqual(workspaceVerificationOptions(['--config-dir','config','--output-parent','output']),{configDir:'config',outputParent:'output'});
  for(const args of [[],['--config-dir','config'],['--config-dir','config','--output-parent','output','--skip-tests']])
    assert.throws(()=>workspaceVerificationOptions(args));
});
test('build evidence binds project version archive and actual runtime identities', () => {
  const project='a0000000-0000-4000-8000-000000000001',version='b0000000-0000-4000-8000-000000000001';
  const good={id:'c0000000-0000-4000-8000-000000000001',projectId:project,versionId:version,status:'SUCCEEDED',stage:'COMPLETE',
    bundleId:'a'.repeat(64),archiveSha256:'b'.repeat(64),loadedRuntimeId:'c'.repeat(64),jarSha256:'d'.repeat(64),templateDigest:'e'.repeat(64),buildPolicyVersion:'native-v1',errorCode:null};
  assert.equal(completedBuild(good,project,version),good);
  for(const change of [{projectId:version},{versionId:project},{id:'../escape'},{status:'FAILED'},{stage:'VERIFYING'},{jarSha256:null},{loadedRuntimeId:'bad'},{errorCode:'BUILD_FAILED'}])
    assert.throws(()=>completedBuild({...good,...change},project,version));
});
