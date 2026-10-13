import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, lstat } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { startOwned, listenerFromConfig, assertFree } from './standalone-process.mjs';
import { toolEnvironment } from '../templates/standalone-v1/scripts/process.mjs';

export function runtimePairOptions(options) {
  if(['configDir','output','firstJar','secondJar'].some(key=>typeof options[key]!=='string' || !isAbsolute(options[key]))
      || ['firstHash','secondHash','loadedRuntimeId'].some(key=>!/^[a-f0-9]{64}$/.test(options[key])))
    throw new Error('Explicit verified runtime pair required.');
  return options;
}
export async function verifyRuntimePair(options,signal) {
  const {configDir,output,firstJar,secondJar,firstHash,secondHash,loadedRuntimeId}=runtimePairOptions(options);
  const configs=[join(configDir,'runtime-a.properties'),join(configDir,'runtime-b.properties')];
  const origins=await Promise.all(configs.map(listenerFromConfig));
  assert.notEqual(origins[0],origins[1]);
  for(const origin of origins) await assertFree(origin);
  const jars=[firstJar,secondJar],hashes=[firstHash,secondHash];
  for(let index=0;index<2;index++) {
    const info=await lstat(jars[index]);assert.ok(info.isFile() && !info.isSymbolicLink() && info.size<=128*1024*1024);
    assert.equal(createHash('sha256').update(await readFile(jars[index])).digest('hex'),hashes[index]);
  }
  const env=toolEnvironment(),java=join(env.JAVA_HOME,'bin/java');
  const start=index=>startOwned(java,['-Xmx256m','-jar',jars[index],'--spring.config.location=classpath:/application.yml,file:'+configs[index]],
    {baseURL:origins[index],cwd:output,env,signal});
  const owned=[];let browser;
  try {
    owned[0]=await start(0);owned[1]=await start(1);
    const {chromium}=await import('../frontend/node_modules/playwright/index.mjs');
    browser=await chromium.launch({channel:'chrome'});
    const context=await browser.newContext();const failures=[];
    context.on('weberror',error=>failures.push(error.error().name));
    context.on('console',message=>{if(['warning','error'].includes(message.type())) failures.push(message.type());});
    await context.route(url=>!origins.includes(url.origin),route=>route.abort('blockedbyclient'));
    const pages=[await context.newPage(),await context.newPage()],saved=[];
    for(let index=0;index<2;index++) {
      const page=pages[index];await page.goto(origins[index]);
      await page.frameLocator('iframe').getByRole('button',{name:'添加待办',exact:true}).waitFor();
      const app=await context.request.get(origins[index]+'/api/app');assert.equal(app.status(),200);
      assert.equal((await app.json()).loadedRuntimeId,loadedRuntimeId);
      assert.deepEqual((await (await context.request.get(origins[index]+'/api/state')).json()).value,{todos:[]});
      const frame=page.frameLocator('iframe');
      const title=index===0?'平台构建独立数据':'下载重建独立数据';
      await frame.getByLabel('新待办',{exact:true}).fill(title);
      await frame.getByRole('button',{name:'添加待办',exact:true}).click();
      await page.getByRole('status').filter({hasText:'已保存到数据库'}).waitFor();
      saved[index]=await (await context.request.get(origins[index]+'/api/state')).json();
      assert.equal(saved[index].revision,1);assert.equal(saved[index].value.todos.length,1);
      await frame.getByRole('checkbox',{name:title,exact:true}).check();
      await page.getByRole('status').filter({hasText:'已保存到数据库'}).waitFor();
      saved[index]=await (await context.request.get(origins[index]+'/api/state')).json();
      assert.equal(saved[index].revision,2);assert.equal(saved[index].value.todos[0].done,true);
      await page.reload();await page.frameLocator('iframe').getByText('数据已读取。',{exact:true}).waitFor();
      assert.deepEqual(await (await context.request.get(origins[index]+'/api/state')).json(),saved[index]);
      await page.screenshot({path:join(output,`runtime-${index}.png`),fullPage:true});
    }
    assert.notDeepEqual(saved[0].value,saved[1].value);
    for(let index=0;index<2;index++) {
      await owned[index].stop();owned[index]=await start(index);
      await pages[index].reload();await pages[index].frameLocator('iframe').getByText('数据已读取。',{exact:true}).waitFor();
      assert.deepEqual(await (await context.request.get(origins[index]+'/api/state')).json(),saved[index]);
    }
    assert.deepEqual(failures,[]);await context.close();
  } finally {
    await browser?.close();
    for(const process of owned.reverse()) await process?.stop();
  }
}
