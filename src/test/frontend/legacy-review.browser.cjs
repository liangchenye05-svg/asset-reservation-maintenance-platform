// Real frontend, real local HTTP and B3-created fixtures. No mocked success responses.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const base=process.env.B2_BASE_URL,schema=process.env.B2_DB_SCHEMA;
if(!base||!/^equipment_it_legacy_[a-f0-9]{32}$/.test(schema||''))throw new Error('Use the B3 isolated Java fixture launcher');
const url=new URL(base);
if(url.protocol!=='http:'||!['127.0.0.1','localhost'].includes(url.hostname)||!url.port||url.port==='8080')throw new Error('Dedicated random local HTTP port required');
const output=process.env.B2_BROWSER_OUTPUT||path.resolve('target/b2-browser');
const checks=[],network=[],layouts=[],errors=[];
const passwords=[process.env.B2_ADMIN_PASSWORD,process.env.B2_USER_PASSWORD];
if(passwords.some(p=>!p))throw new Error('Synthetic login fixture missing');
let browser;
async function ready(page){await page.locator('#content .panel').first().waitFor();await page.locator('.loading').waitFor({state:'hidden'});}
async function login(page,admin){
 await page.goto(base);await page.getByLabel('用户名',{exact:true}).fill(admin?'b2_admin':'b2_user');await page.getByLabel('密码',{exact:true}).fill(passwords[admin?0:1]);
 await page.getByRole('button',{name:'登录工作台'}).click();await ready(page);await page.locator('[data-view=repairs]').click();await ready(page);
}
async function find(page,id){
 await page.getByRole('searchbox',{name:'搜索',exact:true}).fill('B2 browser');await page.getByRole('button',{name:'搜索',exact:true}).click();await ready(page);
 const row=page.locator('tr').filter({has:page.locator(`[data-repair-detail="${id}"]`)});await row.waitFor();return row;
}
async function close(page){await page.locator('#modal [data-close]').click();await page.locator('#modal').waitFor({state:'hidden'});}
function observe(page){page.on('pageerror',e=>errors.push(e.message));page.on('response',r=>{const u=new URL(r.url());if(u.origin===base)network.push({method:r.request().method(),path:u.pathname+u.search,status:r.status()});});}
async function layout(page,name,width){
 const bounds=await page.evaluate(()=>({width:innerWidth,documentWidth:document.documentElement.scrollWidth,modal:document.querySelector('dialog[open]')?.getBoundingClientRect().toJSON()}));
 assert.equal(bounds.width,width);assert.ok(bounds.documentWidth<=width);
 if(bounds.modal){assert.ok(bounds.modal.x>=0);assert.ok(bounds.modal.right<=width);}
 layouts.push({name,...bounds});await page.screenshot({path:path.join(output,name+'.png'),fullPage:true});
}
(async()=>{
 await fs.mkdir(output,{recursive:true});
 try {
  browser=await chromium.launch({channel:'msedge',headless:true});
  const context=await browser.newContext({viewport:{width:1440,height:1000}});const page=await context.newPage();page.setDefaultTimeout(10000);observe(page);
  const id=Number(process.env.B2_ORDER_ID);await login(page,true);let row=await find(page,id);
  assert.match(await row.innerText(),/历史不完整/);assert.match(await row.innerText(),/需人工核对/);await layout(page,'desktop-legacy-row',1440);
  await row.locator('[data-repair-detail]').click();await page.locator('#modal').waitFor();assert.match(await page.locator('#modal').innerText(),/历史不完整/);assert.match(await page.locator('#modal').innerText(),/暂无可追溯操作记录/);await close(page);
  await row.locator('[data-repair=complete]').click();await page.locator('#modal').waitFor();assert.match(await page.locator('#modal').innerText(),/设备保持现状/);
  const note=page.getByLabel('人工核对说明');assert.equal(await note.getAttribute('required'),'');await page.locator('#modal [type=submit]').click();assert.equal(await note.evaluate(el=>el.validity.valueMissing),true);
  assert.equal(network.filter(r=>r.method==='PUT'&&r.path===`/repair-orders/${id}/complete`).length,0);
  await note.fill('已核对现场与预约，保留设备现状。<img src=x onerror="window.__b2Injected=1">');await layout(page,'desktop-manual-review',1440);
  // Explicit failure injection verifies UI handling only; all successful mutations below use the backend.
  const route=`**/repair-orders/${id}/complete`;
  await page.route(route,r=>r.fulfill({status:503,contentType:'text/plain',body:'B2 simulated transport failure'}));
  await page.locator('#modal [type=submit]').click();await page.locator('#modal .form-error').filter({hasText:'B2 simulated transport failure'}).waitFor();
  assert.equal(await page.locator('#modal [type=submit]').isEnabled(),true);assert.ok((await note.inputValue()).startsWith('已核对'));await page.unroute(route);
  checks.push({name:'manual review validation and failure retry',passed:true,simulatedFailureStatus:503});
  const completed=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===`/repair-orders/${id}/complete`);
  await page.locator('#modal [type=submit]').click();assert.equal((await completed).status(),200);await page.locator('#modal').waitFor({state:'hidden'});await ready(page);
  row=await find(page,id);assert.match(await row.innerText(),/已完成/);assert.match(await row.innerText(),/历史不完整/);
  await row.locator('[data-repair-detail]').click();await page.locator('#modal').waitFor();assert.match(await page.locator('#modal').innerText(),/历史不完整/);assert.match(await page.locator('#modal').innerText(),/人工核对说明/);
  assert.equal(await page.locator('#modal img').count(),0);assert.equal(await page.evaluate(()=>window.__b2Injected),undefined);await layout(page,'desktop-completed-history',1440);await close(page);
  checks.push({name:'real completion retains persistent history gap and escapes review text',passed:true});
  await page.locator('#logout').click();await login(page,false);row=await find(page,id);assert.equal(await row.locator('[data-repair]').count(),0);
  await row.locator('[data-repair-detail]').click();await page.locator('#modal').waitFor();assert.match(await page.locator('#modal').innerText(),/历史不完整/);assert.match(await page.locator('#modal').innerText(),/人工核对说明/);await close(page);
  const denied=await page.evaluate(async id=>{const r=await fetch(`/repair-orders/${id}/complete`,{method:'PUT',headers:{Authorization:'Bearer '+sessionStorage.getItem('equip.token'),'Content-Type':'application/json'},body:JSON.stringify({reviewNote:'User attempt'})});return r.status;},id);assert.equal(denied,403);
  checks.push({name:'USER sees truthful history and cannot complete',passed:true});
  const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const mp=await mobile.newPage();mp.setDefaultTimeout(10000);observe(mp);
  const mid=Number(process.env.B2_MOBILE_ORDER_ID);await login(mp,true);const mr=await find(mp,mid);assert.match(await mr.innerText(),/历史不完整/);await mr.locator('[data-repair=complete]').click();await mp.locator('#modal').waitFor();await mp.getByLabel('人工核对说明').fill('移动端核对：保持设备当前状态，等待后续协调。');await layout(mp,'mobile-manual-review',390);
  const mobileCompleted=mp.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===`/repair-orders/${mid}/complete`);await mp.locator('#modal [type=submit]').click();assert.equal((await mobileCompleted).status(),200);await mp.locator('#modal').waitFor({state:'hidden'});await ready(mp);
  assert.match(await (await find(mp,mid)).innerText(),/历史不完整/);await layout(mp,'mobile-completed-row',390);
  checks.push({name:'390px actual review and completion with persistent warning',passed:true});assert.deepEqual(errors,[]);
  await fs.writeFile(path.join(output,'B2_BROWSER_RESULT.json'),JSON.stringify({checks,network,layouts,pageErrors:errors},null,2));
  console.log('B2_BROWSER checks='+checks.length+' screenshots='+layouts.length+' widths=1440,390 realCompletions=2 USER=403');
 } finally {if(browser)await browser.close();}
})().catch(error=>{let message=error.stack||String(error);for(const password of passwords)if(password)message=message.replaceAll(password,'[REDACTED]');console.error(message);process.exitCode=1;});
