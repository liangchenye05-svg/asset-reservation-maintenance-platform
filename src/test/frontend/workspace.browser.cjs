// Real Edge acceptance against a separately provisioned local synthetic database.
// Production requires no browser-test dependencies. Never run against a daily database.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const base=process.env.BROWSER_TEST_BASE_URL,password=process.env.BROWSER_TEST_PASSWORD,schema=process.env.BROWSER_TEST_DB_NAME;
if(!base||!password||!/^ui_[a-f0-9]{32}$/.test(schema||'')||!['127.0.0.1','localhost'].includes(new URL(base).hostname)||new URL(base).port==='8080')throw new Error('Use only the provisioned local synthetic browser-test environment');
const output=process.env.BROWSER_TEST_OUTPUT||path.resolve('target/browser-evidence');
const checks=[],network=[],layouts=[],pageErrors=[];
let browser,currentPage;
const redact=value=>String(value).replaceAll(password,'[REDACTED]');
async function check(name,action){await action();checks.push({name,passed:true});console.log('PASS '+name);}
async function ready(page){await page.locator('#content .panel').first().waitFor();await page.locator('.loading').waitFor({state:'hidden'});}
async function login(page,name){await page.goto(base);await page.locator('#auth-form, #logout').first().waitFor();if(await page.locator('#logout').count())await page.locator('#logout').click();await page.getByLabel('用户名',{exact:true}).fill(name);await page.getByLabel('密码',{exact:true}).fill(password);await page.getByRole('button',{name:'登录工作台'}).click();await ready(page);}
async function nav(page,view){await page.locator(`[data-view="${view}"]`).click();await ready(page);}
async function select(page,id,value){await page.locator(id).selectOption(value);await ready(page);}
async function search(page,value){await page.getByRole('searchbox',{name:'搜索',exact:true}).fill(value);await page.getByRole('button',{name:'搜索',exact:true}).click();await ready(page);}
async function close(page){await page.locator('#modal [data-close]').click();await page.locator('#modal').waitFor({state:'hidden'});}
async function mutation(page,button,method,route){const response=page.waitForResponse(r=>r.request().method()===method&&new URL(r.url()).pathname===route);await button.click();const r=await response;return r;}
async function confirm(page,button,route,status=200,method='PUT'){
 await button.click();await page.locator('#modal').waitFor();const r=await mutation(page,page.locator('#modal [type=submit]'),method,route);assert.equal(r.status(),status);
 if(status===200)await ready(page);else await page.locator('#modal .form-error').filter({hasText:/./}).waitFor();
 return r;
}
async function book(page,start,end){
 await nav(page,'devices');await search(page,'ASSET-001');await page.locator('[data-book="1"]').click();
 await page.getByLabel('开始时间').fill(start);await page.getByLabel('结束时间').fill(end);
 const r=await mutation(page,page.locator('#modal [type=submit]'),'POST','/reservations');assert.equal(r.status(),201);const saved=await r.json();await ready(page);return saved.id;
}
async function read(page,route,method='GET',body){return page.evaluate(async({route,method,body})=>{
 const r=await fetch(route,{method,headers:{Authorization:'Bearer '+sessionStorage.getItem('equip.token'),'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const text=await r.text();let data;try{data=JSON.parse(text);}catch{data=text;}return {status:r.status,data};
 },{route,method,body});}
async function layout(page,name,width){
 const measured=await page.evaluate(()=>({viewport:innerWidth,document:document.documentElement.scrollWidth,dialog:document.querySelector('dialog[open]')?.getBoundingClientRect().toJSON()}));
 assert.equal(measured.viewport,width);assert.ok(measured.document<=width,`${name}: page overflows ${measured.document}/${width}`);
 if(measured.dialog){assert.ok(measured.dialog.x>=0);assert.ok(measured.dialog.right<=width);assert.equal(await page.locator("#modal").evaluate(el=>el.contains(document.activeElement)),true);}
 const navBounds=await page.locator(".nav button").evaluateAll(items=>items.map(el=>({left:el.getBoundingClientRect().left,right:el.getBoundingClientRect().right})));assert.ok(navBounds.every(el=>el.left>=0&&el.right<=width));
 layouts.push({name,...measured});await page.screenshot({path:path.join(output,name+'.png'),fullPage:true});
}
function observe(page){
 page.on('pageerror',error=>pageErrors.push(redact(error.message)));
 page.on('response',response=>{const url=new URL(response.url());if(url.origin===base)network.push({method:response.request().method(),path:url.pathname+url.search,status:response.status()});});
}
(async()=>{
 await fs.mkdir(output,{recursive:true});
 try {
  browser=await chromium.launch({channel:'msedge',headless:true});
  const desktop=await browser.newContext({viewport:{width:1440,height:1000}});const page=await desktop.newPage();currentPage=page;page.setDefaultTimeout(10000);observe(page);
  await check('desktop login validation and registration',async()=>{
   await page.goto(base);await layout(page,'desktop-login',1440);
   await page.getByLabel('用户名',{exact:true}).fill('ui_user');await page.getByLabel('密码',{exact:true}).fill('wrong');
   const bad=await mutation(page,page.getByRole('button',{name:'登录工作台'}),'POST','/users/login');assert.equal(bad.status(),400);await page.locator('.form-error').filter({hasText:'用户名或密码错误'}).waitFor();
   await page.locator('[data-auth=register]').click();await page.getByLabel('用户名',{exact:true}).fill('ui_registered');await page.getByLabel('密码',{exact:true}).fill(password);await page.getByLabel('确认密码',{exact:true}).fill('mismatch');
   await page.locator('#auth-form [type=submit]').click();await page.locator('.form-error').filter({hasText:'两次输入的密码不一致'}).waitFor();
   await page.getByLabel('确认密码',{exact:true}).fill(password);const saved=await mutation(page,page.locator('#auth-form [type=submit]'),'POST','/users/register');assert.equal(saved.status(),200);await page.locator('[data-auth=login].active').waitFor();
   await login(page,'ui_user');assert.equal(await page.locator('[data-view=admin]').count(),0);
  });
  await check('device global search, status, pagination, and detail',async()=>{
   assert.match(await page.locator('.pagination').innerText(),/共 10 条/);await page.locator('[data-page="2"]').click();await ready(page);await page.getByText('演示设备 09',{exact:true}).waitFor();
   await page.locator('[data-device-detail="9"]').click();await page.locator('#modal').getByText('ASSET-009',{exact:true}).waitFor();await close(page);
   await search(page,'ASSET-010');assert.equal(await page.locator('tbody tr').count(),1);await page.getByText('演示设备 10',{exact:true}).waitFor();
   await page.locator('#reset-filters').click();await ready(page);await select(page,'#status-filter','MAINTENANCE');assert.equal(await page.locator('tbody tr').count(),1);await page.getByText('演示设备 02',{exact:true}).waitFor();
   await select(page,'#status-filter','');await search(page,'no-match-fixture');await page.getByText('没有找到符合条件的设备').waitFor();await page.locator('#reset-filters').click();await ready(page);await layout(page,'desktop-user-devices',1440);
  });
  let approvedId,rejectedId,cancelledId,repairId;
  await check('reservation validation, creation, server filters and detail',async()=>{
   await search(page,'ASSET-001');await page.locator('[data-book="1"]').click();await page.getByLabel('开始时间').fill('2035-01-05T11:00');await page.getByLabel('结束时间').fill('2035-01-05T10:00');await page.locator('#modal [type=submit]').click();await page.locator('.form-error').filter({hasText:'结束时间必须晚于开始时间'}).waitFor();await close(page);
   approvedId=await book(page,'2035-01-05T10:00','2035-01-05T11:00');
   await page.locator(`[data-detail="${approvedId}"]`).click();await page.locator('#modal').getByText('#'+approvedId,{exact:true}).waitFor();await close(page);
   await select(page,'#device-filter','1');await select(page,'#status-filter','PENDING');assert.ok(network.some(r=>r.path.includes('/reservations?')&&r.path.includes('deviceId=1')&&r.path.includes('status=PENDING')));
   assert.equal(await page.locator('tbody tr').count(),1);
   rejectedId=await book(page,'2035-01-05T10:00','2035-01-05T11:00');
   cancelledId=await book(page,'2035-01-06T10:00','2035-01-06T11:00');
   await confirm(page,page.locator(`[data-action=cancel][data-id="${cancelledId}"]`),`/reservations/${cancelledId}/cancel`);
   await page.locator('tr').filter({has:page.locator(`[data-detail="${cancelledId}"]`)}).getByText('已取消',{exact:true}).waitFor();await layout(page,'desktop-user-reservations',1440);
  });
  const description='浏览器报修 <img src=x onerror="window.fixtureXss=1"> 验收设备异常';
  await check('repair creation, server status filtering, pagination and escaped detail',async()=>{
   await nav(page,'devices');await search(page,'ASSET-001');await page.locator('[data-report="1"]').click();await page.getByLabel('问题描述',{exact:true}).fill('   ');await page.locator('#modal [type=submit]').click();await page.locator('.form-error').filter({hasText:'请填写问题描述'}).waitFor();
   await page.getByLabel('问题描述',{exact:true}).fill(description);const r=await mutation(page,page.locator('#modal [type=submit]'),'POST','/repair-orders');assert.equal(r.status(),200);await ready(page);
   const records=await read(page,'/repair-orders');repairId=records.data.find(r=>r.description===description).id;assert.ok(records.data.every(r=>r.userId===1));
   await page.locator('[data-page="2"]').click();await ready(page);assert.ok(await page.locator('tbody tr').count()>0);
   await select(page,'#status-filter','COMPLETED');assert.ok(network.some(r=>r.path==='/repair-orders/page?page=1&pageSize=8&status=COMPLETED'&&r.status===200));
   await select(page,'#status-filter','');assert.ok(network.some(r=>r.path==='/repair-orders/page?page=1&pageSize=8'&&r.status===200));
   await search(page,String(repairId));await page.locator(`[data-repair-detail="${repairId}"]`).click();await page.locator('.detail-description').filter({hasText:description}).waitFor();assert.equal(await page.evaluate(()=>window.fixtureXss),undefined);await layout(page,'desktop-repair-detail',1440);await close(page);
   await select(page,'#device-filter','1');assert.ok((await read(page,'/repair-orders')).data.some(r=>r.id===repairId));
  });
  await check('real backend permission and ownership checks',async()=>{
   assert.equal((await read(page,'/devices/1','PUT',{name:'denied',location:'denied'})).status,403);
   assert.equal((await read(page,'/repair-orders/13')).status,403);
   const injected=await read(page,'/repair-orders?role=ADMIN&userId=3');assert.equal(injected.status,200);assert.ok(injected.data.every(r=>r.userId===1));
  });
  await check('USER session restore and logout',async()=>{
   await page.reload();await ready(page);await page.locator('#logout').click();await page.locator('#auth-form').waitFor();assert.equal(await page.evaluate(()=>sessionStorage.getItem('equip.token')),null);
  });
  await check('ADMIN approve, conflict feedback and rejection',async()=>{
   await login(page,'ui_admin');await nav(page,'admin');
   await confirm(page,page.locator(`[data-action=approve][data-id="${approvedId}"]`),`/reservations/${approvedId}/approve`);
   const conflict=await confirm(page,page.locator(`[data-action=approve][data-id="${rejectedId}"]`),`/reservations/${rejectedId}/approve`,400);assert.equal(await conflict.text(),"预约时间冲突，无法审批通过");await page.locator("#modal .form-error").filter({hasText:"预约时间冲突"}).waitFor();await close(page);
   await confirm(page,page.locator(`[data-action=reject][data-id="${rejectedId}"]`),`/reservations/${rejectedId}/reject`);
   await layout(page,'desktop-admin-workbench',1440);
  });
  await check('ADMIN repair process/complete and live counts',async()=>{
   await nav(page,'repairs');await search(page,String(repairId));await confirm(page,page.locator(`[data-repair=process][data-id="${repairId}"]`),`/repair-orders/${repairId}/process`);
   await page.locator('tbody .pill').filter({hasText:'处理中'}).waitFor();await confirm(page,page.locator(`[data-repair=complete][data-id="${repairId}"]`),`/repair-orders/${repairId}/complete`);
   await select(page,'#status-filter','COMPLETED');assert.equal(await page.locator('tbody tr').count(),1);await page.locator('tbody .pill').filter({hasText:'已完成'}).waitFor();
   await nav(page,'admin');const pending=await read(page,'/repair-orders');const expected=pending.data.filter(r=>r.status!=='COMPLETED').length;assert.equal(await page.locator('.stat[data-go=repairs] strong').innerText(),String(expected));
  });
  await check('ADMIN device create/edit/delete, required fields and duplicate protection',async()=>{
   await nav(page,'devices');await page.locator('#add-device').click();await page.getByLabel('设备编码',{exact:true}).fill('UI-NEW-001');await page.getByLabel('设备名称',{exact:true}).fill('  ');await page.getByLabel('存放位置',{exact:true}).fill('Lab');await page.locator('#modal [type=submit]').click();await page.locator('.form-error').filter({hasText:'不能只有空格'}).waitFor();
   await page.getByLabel('设备名称',{exact:true}).fill('新增验收设备');
   let count=0;await page.route('**/devices',async route=>{if(route.request().method()==='POST'){count++;await new Promise(r=>setTimeout(r,500));}await route.continue();});
   const saved=page.waitForResponse(r=>new URL(r.url()).pathname==='/devices'&&r.request().method()==='POST');await page.locator('#modal [type=submit]').click();assert.equal(await page.locator('#modal [type=submit]').isDisabled(),true);await page.locator('#modal form').evaluate(form=>{form.requestSubmit();form.requestSubmit();});assert.equal((await saved).status(),200);await ready(page);assert.equal(count,1);await page.unroute('**/devices');
   await search(page,'UI-NEW-001');const id=Number(await page.locator('[data-edit]').getAttribute('data-edit'));
   await page.locator('[data-edit]').click();await page.getByLabel('设备名称',{exact:true}).fill('修改后的验收设备');const edited=await mutation(page,page.locator('#modal [type=submit]'),'PUT','/devices/'+id);assert.equal(edited.status(),200);await ready(page);await page.getByText('修改后的验收设备',{exact:true}).waitFor();
   await confirm(page,page.locator('[data-delete]'),'/devices/'+id,200,'DELETE');await page.getByText('没有找到符合条件的设备').waitFor();
   await page.locator('#reset-filters').click();await ready(page);await search(page,'ASSET-001');await confirm(page,page.locator('[data-delete="1"]'),'/devices/1',409,'DELETE');await close(page);
  });
  await check('HTTP failure, refresh retry, 403 and expired session',async()=>{
   await nav(page,'repairs');await page.route('**/repair-orders/page?page=1&pageSize=8&status=PROCESSING',route=>route.fulfill({status:503,contentType:'text/plain',body:'合成故障注入：请重试'}));await select(page,'#status-filter','PROCESSING');await page.getByText('加载失败',{exact:true}).waitFor();assert.equal(await page.locator('tbody tr').count(),0);await page.unroute('**/repair-orders/page?page=1&pageSize=8&status=PROCESSING');await page.getByRole('button',{name:'重试',exact:true}).click();await ready(page);assert.equal(await page.locator('#status-filter').inputValue(),'PROCESSING');
   await page.route('**/repair-orders/page?page=1&pageSize=8&status=PENDING',route=>route.fulfill({status:403,contentType:'text/plain',body:'合成权限提示'}));await select(page,'#status-filter','PENDING');await page.getByText('权限不足',{exact:true}).waitFor();await page.unroute('**/repair-orders/page?page=1&pageSize=8&status=PENDING');await page.getByRole('button',{name:'重试',exact:true}).click();await ready(page);
   await page.evaluate(()=>sessionStorage.setItem('equip.token','synthetic-invalid-jwt'));await page.reload();await page.locator('#auth-form').waitFor();assert.equal(await page.evaluate(()=>sessionStorage.getItem('equip.token')),null);
  });
  await check('real network failure, visible loading, timeout and retry',async()=>{
   await login(page,'ui_user');await nav(page,'repairs');
   await page.route('**/repair-orders/page?page=1&pageSize=8&status=PROCESSING',route=>route.abort('connectionfailed'));
   await select(page,'#status-filter','PROCESSING');await page.getByText('无法连接服务，请确认后端已启动。',{exact:true}).waitFor();
   await page.unroute('**/repair-orders/page?page=1&pageSize=8&status=PROCESSING');await page.getByRole('button',{name:'重试',exact:true}).click();await ready(page);
   await page.route('**/repair-orders/page?page=1&pageSize=8&status=PENDING',async route=>{await new Promise(resolve=>setTimeout(resolve,16000));if(!route.request().failure())await route.continue();});
   const started=Date.now();await page.locator('#status-filter').selectOption('PENDING');await page.locator('.loading').waitFor();
   await page.getByText('请求超时，请先刷新核对结果，避免重复提交。',{exact:true}).waitFor({timeout:22000});assert.ok(Date.now()-started>=14000);
   await page.unroute('**/repair-orders/page?page=1&pageSize=8&status=PENDING');await page.getByRole('button',{name:'重试',exact:true}).click();await ready(page);assert.equal(await page.locator('#status-filter').inputValue(),'PENDING');
  });
  await check('empty account state from the real API',async()=>{
   await login(page,'ui_empty');await nav(page,'reservations');await page.getByText('暂无符合条件的预约').waitFor();await nav(page,'repairs');await page.getByText('暂无维修工单').waitFor();const r=await read(page,'/repair-orders');assert.deepEqual(r.data,[]);await layout(page,'desktop-empty-repairs',1440);
  });
  const mobile=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,isMobile:true,hasTouch:true});const phone=await mobile.newPage();currentPage=phone;phone.setDefaultTimeout(10000);observe(phone);
  await check('390px USER navigation, filters, form and detail',async()=>{
   await phone.goto(base);await layout(phone,'mobile-login-390',390);await login(phone,'ui_user');await layout(phone,'mobile-user-devices-390',390);
   await search(phone,'ASSET-001');await phone.locator('[data-book="1"]').click();await layout(phone,'mobile-booking-390',390);await close(phone);
   await nav(phone,'reservations');await select(phone,'#device-filter','1');await layout(phone,'mobile-reservations-390',390);
   await confirm(phone,phone.locator(`[data-action=cancel][data-id="${approvedId}"]`),`/reservations/${approvedId}/cancel`);
   await nav(phone,'repairs');await select(phone,'#status-filter','COMPLETED');await search(phone,String(repairId));await layout(phone,'mobile-repairs-390',390);await phone.locator(`[data-repair-detail="${repairId}"]`).click();await layout(phone,'mobile-repair-detail-390',390);await close(phone);
  });
  await check('390px ADMIN workbench, cards and device actions',async()=>{
   await phone.locator('#logout').click();await login(phone,'ui_admin');await nav(phone,'admin');await layout(phone,'mobile-admin-workbench-390',390);await nav(phone,'devices');await search(phone,'ASSET-010');await layout(phone,'mobile-admin-devices-390',390);assert.ok(await phone.locator('[data-edit]').isVisible());
  });
  await check('maintenance conflict warning, blocked booking and complete operation history',async()=>{
   currentPage=page;await login(page,'ui_admin');
   const code='CLOSED-LOOP-'+Date.now();assert.equal((await read(page,'/devices','POST',{deviceCode:code,name:'维修闭环验收设备',location:'合成验收区'})).status,200);
   const devices=await read(page,'/devices/page?page=1&pageSize=100');const device=devices.data.records.find(d=>d.deviceCode===code);assert.ok(device);
   await login(page,'ui_user');await search(page,code);await page.locator(`[data-book="${device.id}"]`).click();
   await page.getByLabel('开始时间').fill('2036-03-01T10:00');await page.getByLabel('结束时间').fill('2036-03-01T11:00');
   const booking=await mutation(page,page.locator('#modal [type=submit]'),'POST','/reservations');assert.equal(booking.status(),201);const bookingId=(await booking.json()).id;await ready(page);
   await login(page,'ui_admin');await nav(page,'admin');await confirm(page,page.locator(`[data-action=approve][data-id="${bookingId}"]`),`/reservations/${bookingId}/approve`);
   await login(page,'ui_user');await search(page,code);await page.locator(`[data-report="${device.id}"]`).click();
   const description='维修闭环真实接口验收 '+code;await page.getByLabel('问题描述',{exact:true}).fill(description);
   assert.equal((await mutation(page,page.locator('#modal [type=submit]'),'POST','/repair-orders')).status(),200);await ready(page);
   const orders=await read(page,'/repair-orders/page?keyword='+encodeURIComponent(code));const id=orders.data.records.find(r=>r.description===description).id;
   await login(page,'ui_admin');await nav(page,'repairs');await search(page,String(id));await page.locator(`[data-repair=process][data-id="${id}"]`).click();
   await page.getByRole('heading',{name:'设备维修影响',exact:true}).waitFor();await page.locator('#modal').getByText('预约 #'+bookingId,{exact:false}).waitFor();
   await layout(page,'desktop-maintenance-impact',1440);
   assert.equal((await mutation(page,page.locator('#modal [type=submit]'),'PUT',`/repair-orders/${id}/process`)).status(),200);await ready(page);
   assert.equal((await read(page,'/devices/'+device.id)).data.status,'MAINTENANCE');
   assert.equal((await read(page,'/reservations/'+bookingId)).data.status,'APPROVED');
   await login(page,'ui_user');await search(page,code);await page.locator(`[data-book="${device.id}"]`).click();
   await page.getByLabel('开始时间').fill('2036-03-02T10:00');await page.getByLabel('结束时间').fill('2036-03-02T11:00');
   assert.equal((await mutation(page,page.locator('#modal [type=submit]'),'POST','/reservations')).status(),409);
   await page.locator('#modal .form-error').filter({hasText:'设备维修中'}).waitFor();await close(page);
   await login(page,'ui_admin');await nav(page,'repairs');await search(page,String(id));await confirm(page,page.locator(`[data-repair=complete][data-id="${id}"]`),`/repair-orders/${id}/complete`);
   assert.equal((await read(page,'/devices/'+device.id)).data.status,'AVAILABLE');
   await page.locator(`[data-repair-detail="${id}"]`).click();await page.locator('.history-list li').nth(2).waitFor();assert.equal(await page.locator('.history-list li').count(),3);await layout(page,'desktop-maintenance-history',1440);await close(page);
   currentPage=phone;await login(phone,'ui_user');await nav(phone,'repairs');await search(phone,String(id));await phone.locator(`[data-repair-detail="${id}"]`).click();await phone.locator('.history-list li').nth(2).waitFor();assert.equal(await phone.locator('.history-list li').count(),3);await layout(phone,'mobile-maintenance-history-390',390);await close(phone);
  });
  assert.deepEqual(pageErrors,[]);
  await fs.writeFile(path.join(output,'RESULTS.json'),JSON.stringify({passed:true,browser:'Microsoft Edge / Chromium',checks,layouts,network,pageErrors,syntheticDatabase:true},null,2));
  console.log(`Browser acceptance passed: ${checks.length} groups, ${layouts.length} measured screenshots`);
 } catch(error) {
  await fs.writeFile(path.join(output,'FAILURE.json'),JSON.stringify({passed:false,error:redact(error.stack),checks,layouts,network,pageErrors},null,2));
  if(currentPage)await currentPage.screenshot({path:path.join(output,'failure.png'),fullPage:true});
  console.error(redact(error.stack));process.exitCode=1;
 } finally {if(browser)await browser.close();}
})();
