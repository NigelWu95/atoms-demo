import { isAbsolute, join } from 'node:path';
import { realpath, mkdir } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { startBuildFixture } from './verify-workspace-build.mjs';
import { runCli } from '../templates/standalone-v1/scripts/process.mjs';

export function browserWorkspace(env) {
  const output=env.WORKSPACE_BROWSER_OUTPUT, config=env.WORKSPACE_BROWSER_CONFIG;
  if(!output || !config || !isAbsolute(output) || !isAbsolute(config))
    throw new Error('Browser fixture requires explicit absolute output and config directories.');
  return {output,config};
}
async function serve(signal) {
  const paths=browserWorkspace(process.env);
  const output=await realpath(paths.output),config=await realpath(paths.config);
  const workspace=join(output,'builds');
  await mkdir(workspace,{mode:0o700}); // Existing output is an error, not a reused fixture.
  const platform=await startBuildFixture(workspace,config,signal);
  try {
    const error=await platform.done;
    if(!signal.aborted) throw error ?? new Error('Owned browser fixture stopped unexpectedly.');
  } finally {await platform.stop();}
}
if(process.argv[1] && import.meta.url===pathToFileURL(await realpath(process.argv[1])).href)
  await runCli(serve,'Workspace browser fixture failed; no existing server was reused.');
