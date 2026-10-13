import { spawn } from 'node:child_process';
import { writeFile, rename } from 'node:fs/promises';
import { join, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { toolEnvironment } from './supervisor.mjs';

/** Trusted entry only; the keeper owns its CURRENT group, including orphaned descendants. */
export async function superviseRuntime({command,args,cwd,id,parentInput,graceMs=5000}) {
  if (!isAbsolute(command) || !isAbsolute(cwd) || !Array.isArray(args) || args.some(v=>typeof v!=='string')
      || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id)
      || !Number.isInteger(graceMs) || graceMs<1 || graceMs>5000) throw new Error('Invalid runtime supervision');
  let stopping=false,ready=false,spawned=false,failed=false,stopTimer,input='';
  const keeper=spawn(process.execPath,[fileURLToPath(new URL('./protocol.mjs',import.meta.url)),String(graceMs)],{
    detached:true,shell:false,env:toolEnvironment(),stdio:['ignore','ignore','ignore','ipc'],
  });
  const stop=()=>{
    if(stopping) return;
    stopping=true;
    if(keeper.connected) keeper.send({type:'STOP'},()=>{});
    stopTimer=setTimeout(()=>{if(keeper.connected) keeper.disconnect();},graceMs+2000);
  };
  const data=bytes=>{
    input+=bytes.toString('ascii');
    if(input.length>16 || input.includes('\n')) {stop();input='';}
  };
  parentInput.on('data',data);parentInput.on('end',stop);parentInput.on('error',stop);parentInput.resume();
  process.on('SIGTERM',stop);process.on('SIGINT',stop);
  if(parentInput.readableEnded || parentInput.destroyed) stop();
  try {
    await new Promise(resolveExit=>{
      keeper.once('spawn',()=>{spawned=true;});
      keeper.once('error',()=>{failed=true;resolveExit();});
      keeper.once('exit',resolveExit);
      keeper.on('message',message=>{
        if(message?.type==='READY' && !ready) {
          ready=true;
          if(stopping) {if(keeper.connected) keeper.send({type:'STOP'},()=>{});return;}
          keeper.send({type:'START',command,args,cwd,env:toolEnvironment()},error=>{if(error)stop();});
        } else if(message?.type==='EXIT') stop();
        else stop();
      });
    });
    let settled=!spawned && failed;
    if(spawned) {
      const deadline=Date.now()+3000;
      while(Date.now()<deadline) {
        // Observation only; NEVER send a destructive signal to a cached PID/group.
        try {process.kill(-keeper.pid,0);}
        catch(error) {if(error.code==='ESRCH')settled=true;break;}
        await delay(25);
      }
    }
    if(settled) {
      await writeFile(join(cwd,'exit.pending'),JSON.stringify({id,settled:true}),{flag:'wx',mode:0o600});
      await rename(join(cwd,'exit.pending'),join(cwd,'exit.json'));
    }
    return {settled};
  } finally {
    clearTimeout(stopTimer);
    parentInput.off('data',data);parentInput.off('end',stop);parentInput.off('error',stop);parentInput.pause();
    process.off('SIGTERM',stop);process.off('SIGINT',stop);
    if(keeper.connected)keeper.disconnect();
  }
}

if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  process.umask(0o077);
  const [command,jar,config,cwd,id]=process.argv.slice(2);
  try {
    if(process.argv.length!==7 || !isAbsolute(jar) || !isAbsolute(config)) throw new Error();
    const result=await superviseRuntime({command,args:['-Xmx256m','-jar',jar,
      '--spring.config.location=classpath:/application.yml,file:'+config],cwd,id,parentInput:process.stdin});
    process.exitCode=result.settled?0:1;
  } catch {process.exitCode=1;}
}
