// Run with node --test src/test/frontend/repair-status.test.cjs (no npm dependencies).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/app.js'), 'utf8');

// Small DOM adapter: execute the actual app and its registered event handlers.
// This verifies request/render logic; it does not replace browser layout testing.
function workspace(fetchRepair, role = 'USER', live = false, devices = []) {
  const requests = [];
  const elements = new Map();
  const element = key => {
    if (!elements.has(key)) elements.set(key, {innerHTML:'', textContent:'', value:'', dataset:{}, addEventListener(type, handler) {this['on'+type]=handler;}});
    return elements.get(key);
  };
  const content = element('#content');
  let html = '';
  Object.defineProperty(content, 'innerHTML', {
    get: () => html,
    set: value => { html = value; elements.delete('#status-filter'); }
  });
  const nav = ['devices','repairs','reservations','admin'].map(view => ({dataset:{view}}));
  const refresh = {};
  const pages = [1,2,3].map(page=>({dataset:{page:String(page)}}));
  const token = live ? process.env.DAY12_TOKEN : 'synthetic-test-token';
  const document = {
    querySelector: key => {
      if (key === '#status-filter' && !html.includes('id="status-filter"')) return null;
      if (key === '#add-device') return null;
      return element(key);
    },
    querySelectorAll: key => key === '[data-view]' ? nav : key === '[data-refresh]' ? [refresh] : key === '[data-page]' ? pages : []
  };
  vm.runInNewContext(source, {
    document, sessionStorage:{getItem:()=>token,removeItem(){},setItem(){}},
    AbortController, TypeError, URLSearchParams, FormData:class {get(){return element('#filter-form').query;}}, setTimeout:()=>1, clearTimeout(){}, console,
    fetch: async (url, options) => {
      requests.push({url, headers:options.headers});
      if (live) return fetch(new URL(url, process.env.DAY12_BASE_URL), options);
      if (url === '/users/me') return new Response(JSON.stringify({id:1,username:'fixture',role}));
      if (url.startsWith('/devices/page')) {
        const params=new URL(url,'http://fixture').searchParams;
        const page=Number(params.get('page')),size=Number(params.get('pageSize'));
        return new Response(JSON.stringify({records:devices.slice((page-1)*size,page*size),total:devices.length,page,pageSize:size}));
      }
      const response=await fetchRepair(url, options);
      if(!response.ok)return response;
      const data=await response.json(),params=new URL(url,'http://fixture').searchParams;
      return new Response(JSON.stringify(Array.isArray(data)?{records:data,total:data.length,page:Number(params.get('page')||1),pageSize:8}:data));
    }
  });
  async function ready() {
    for (let i=0; i<500; i++) {
      if (html && !html.includes('正在读取最新数据')) return;
      await new Promise(resolve=>setTimeout(resolve,10));
    }
    throw new Error('App did not settle');
  }
  return {
    requests, ready, content, refresh,
    async repairs() {nav.find(b=>b.dataset.view==='repairs').onclick(); await ready();},
    async select(value) {const select=element('#status-filter');select.value=value;select.onchange();await ready();},
    async page(value) {pages.find(b=>b.dataset.page===String(value)).onclick();await ready();},
    async search(value) {element('#filter-form').query=value;element('#filter-form').onsubmit({preventDefault(){}});await ready();},
    async device(value) {const select=element('#device-filter');select.value=value;select.onchange();await ready();},
    navigate(view) {nav.find(b=>b.dataset.view===view).onclick();}
  };
}
const row = (id, status='PENDING') => ({id,deviceId:1,userId:1,status,description:`fixture-${id}`,createdAt:'2030-01-01T00:00:00'});

test('three statuses reach backend; all omits status; JWT retained; rows rendered directly', async()=>{
  // Deliberately return mixed statuses: the app must render the server response,
  // rather than hiding records with a second client-side status filter.
  const w=workspace(()=>new Response(JSON.stringify([row(91),row(92,'COMPLETED')])));
  await w.ready();await w.repairs();
  for(const status of ['PENDING','PROCESSING','COMPLETED','']) {
    await w.select(status);
    assert.equal(w.requests.at(-1).url, '/repair-orders/page?page=1&pageSize=8'+(status?'&status='+status:''));
    assert.equal(w.requests.at(-1).headers.Authorization,'Bearer synthetic-test-token');
    assert.match(w.content.innerHTML,/fixture-91/);assert.match(w.content.innerHTML,/fixture-92/);
    assert.match(w.content.innerHTML,/共 2 条/);
    if(status)assert.match(w.content.innerHTML,new RegExp(`value="${status}" selected`));
  }
});
test('empty response renders empty state and supports switching back',async()=>{
  const w=workspace(url=>new Response(JSON.stringify(url.includes('status=')?[]:[row(93)])));
  await w.ready();await w.repairs();await w.select('PROCESSING');
  assert.match(w.content.innerHTML,/暂无维修工单/);assert.match(w.content.innerHTML,/共 0 条/);
  await w.select('');assert.match(w.content.innerHTML,/fixture-93/);
});
test('HTTP failure clears old rows; refresh retries the selected status',async()=>{
  let fail=false;
  const w=workspace(()=>fail?new Response('fixture service unavailable',{status:503}):new Response(JSON.stringify([row(94)])));
  await w.ready();await w.repairs();fail=true;await w.select('COMPLETED');
  assert.match(w.content.innerHTML,/加载失败/);assert.doesNotMatch(w.content.innerHTML,/fixture-94/);
  fail=false;w.refresh.onclick();await w.ready();
  assert.equal(w.requests.at(-1).url,'/repair-orders/page?page=1&pageSize=8&status=COMPLETED');assert.match(w.content.innerHTML,/fixture-94/);
});
test('network failure renders existing connection error',async()=>{
  const w=workspace(()=>{throw new TypeError('offline');});await w.ready();await w.repairs();
  assert.match(w.content.innerHTML,/无法连接服务/);
});
test('late repair response cannot replace a newer page',async()=>{
  let complete;
  const w=workspace(()=>new Promise(resolve=>{complete=resolve;}));await w.ready();
  w.navigate('repairs');
  while(!complete)await new Promise(resolve=>setImmediate(resolve));
  w.navigate('devices');await w.ready();
  complete(new Response(JSON.stringify([row(95)])));await new Promise(resolve=>setImmediate(resolve));
  assert.match(w.content.innerHTML,/设备台账/);assert.doesNotMatch(w.content.innerHTML,/fixture-95/);
});

test('device names resolve beyond the first catalogue page',async()=>{
  const devices=Array.from({length:105},(_,i)=>({id:i+1,name:`Asset-${i+1}`,deviceCode:`F-${i+1}`,location:'Lab',status:'AVAILABLE'}));
  const w=workspace(()=>new Response(JSON.stringify([{...row(96),deviceId:105}])),'USER',false,devices);
  await w.ready();await w.repairs();
  assert.ok(w.requests.some(r=>r.url==='/devices/page?page=2&pageSize=100'));
  assert.match(w.content.innerHTML,/Asset-105/);
});

test('server total is retained and combined filters reset the requested page',async()=>{
  const w=workspace(url=>{const page=Number(new URL(url,'http://fixture').searchParams.get('page'));return new Response(JSON.stringify({records:[row(120+page)],total:25,page,pageSize:8}));});
  await w.ready();await w.repairs();await w.page(2);
  assert.equal(w.requests.at(-1).url,'/repair-orders/page?page=2&pageSize=8');
  assert.match(w.content.innerHTML,/共 25 条/);assert.match(w.content.innerHTML,/第 2 \/ 4 页/);
  await w.select('PROCESSING');await w.device('9');await w.search(' pump ');
  assert.equal(w.requests.at(-1).url,'/repair-orders/page?page=1&pageSize=8&status=PROCESSING&deviceId=9&keyword=pump');
  assert.match(w.content.innerHTML,/fixture-121/);
});

test('a page removed by a completed action returns to the last server page',async()=>{
  const w=workspace(url=>{const page=Number(new URL(url,'http://fixture').searchParams.get('page'));return new Response(JSON.stringify({records:page===1?[row(140)]:[],total:1,page,pageSize:8}));});
  await w.ready();await w.repairs();await w.page(2);
  assert.equal(w.requests.at(-1).url,'/repair-orders/page?page=1&pageSize=8');
  assert.match(w.content.innerHTML,/fixture-140/);
});

test('actual frontend event handlers fetch filtered real HTTP responses', {skip:!process.env.DAY12_BASE_URL}, async()=>{
  const target=new URL(process.env.DAY12_BASE_URL);
  assert.ok(['127.0.0.1','localhost'].includes(target.hostname)&&target.protocol==='http:'&&target.port&&target.port!=='8080', 'Use the isolated HTTP fixture launcher');
  assert.match(process.env.TEST_DB_SCHEMA||'',/^equipment_it_status_[a-f0-9]{32}$/, 'Dedicated Java fixture schema required');
  const w=workspace(null,process.env.DAY12_ROLE,true);await w.ready();await w.repairs();
  const count=Number(process.env.DAY12_EXPECT_PER_STATUS);
  assert.match(w.content.innerHTML,new RegExp(`共 ${count*3} 条`));
  for(const status of ['PENDING','PROCESSING','COMPLETED','']) {
    await w.select(status);
    assert.match(w.content.innerHTML,new RegExp(`共 ${count*(status?1:3)} 条`));
    assert.doesNotMatch(w.content.innerHTML,/加载失败/);
    assert.equal(w.requests.at(-1).url,'/repair-orders/page?page=1&pageSize=8'+(status?'&status='+status:''));
  }
});
