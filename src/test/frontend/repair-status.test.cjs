// Run with node --test src/test/frontend/repair-status.test.cjs (no npm dependencies).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/assets/app.js'), 'utf8');

// Small DOM adapter: execute the actual app and its registered event handlers.
// This verifies request/render logic; it does not replace browser layout testing.
function workspace(fetchRepair, role = 'USER', live = false) {
  const requests = [];
  const elements = new Map();
  const element = key => {
    if (!elements.has(key)) elements.set(key, {innerHTML:'', textContent:'', value:'', dataset:{}});
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
  const token = live ? process.env.DAY12_TOKEN : 'synthetic-test-token';
  const document = {
    querySelector: key => {
      if (key === '#status-filter' && !html.includes('id="status-filter"')) return null;
      if (key === '#add-device') return null;
      return element(key);
    },
    querySelectorAll: key => key === '[data-view]' ? nav : key === '[data-refresh]' ? [refresh] : []
  };
  vm.runInNewContext(source, {
    document, sessionStorage:{getItem:()=>token,removeItem(){},setItem(){}},
    AbortController, TypeError, setTimeout:()=>1, clearTimeout(){}, console,
    fetch: async (url, options) => {
      requests.push({url, headers:options.headers});
      if (live) return fetch(new URL(url, process.env.DAY12_BASE_URL), options);
      if (url === '/users/me') return new Response(JSON.stringify({id:1,username:'fixture',role}));
      if (url.startsWith('/devices/page')) return new Response(JSON.stringify({records:[],total:0,page:1,pageSize:8}));
      return fetchRepair(url, options);
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
    assert.equal(w.requests.at(-1).url, '/repair-orders'+(status?'?status='+status:''));
    assert.equal(w.requests.at(-1).headers.Authorization,'Bearer synthetic-test-token');
    assert.match(w.content.innerHTML,/fixture-91/);assert.match(w.content.innerHTML,/fixture-92/);
    assert.match(w.content.innerHTML,/共 2 条/);
    if(status)assert.match(w.content.innerHTML,new RegExp(`value="${status}" selected`));
  }
});
test('empty response renders empty state and supports switching back',async()=>{
  const w=workspace(url=>new Response(JSON.stringify(url.includes('?')?[]:[row(93)])));
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
  assert.equal(w.requests.at(-1).url,'/repair-orders?status=COMPLETED');assert.match(w.content.innerHTML,/fixture-94/);
});
test('network failure renders existing connection error',async()=>{
  const w=workspace(()=>{throw new TypeError('offline');});await w.ready();await w.repairs();
  assert.match(w.content.innerHTML,/无法连接服务/);
});
test('late repair response cannot replace a newer page',async()=>{
  let complete;
  const w=workspace(()=>new Promise(resolve=>{complete=resolve;}));await w.ready();
  w.navigate('repairs');w.navigate('devices');await w.ready();
  complete(new Response(JSON.stringify([row(95)])));await new Promise(resolve=>setImmediate(resolve));
  assert.match(w.content.innerHTML,/设备台账/);assert.doesNotMatch(w.content.innerHTML,/fixture-95/);
});

test('actual frontend event handlers fetch filtered real HTTP responses', {skip:!process.env.DAY12_BASE_URL}, async()=>{
  const w=workspace(null,process.env.DAY12_ROLE,true);await w.ready();await w.repairs();
  const count=Number(process.env.DAY12_EXPECT_PER_STATUS);
  assert.match(w.content.innerHTML,new RegExp(`共 ${count*3} 条`));
  for(const status of ['PENDING','PROCESSING','COMPLETED','']) {
    await w.select(status);
    assert.match(w.content.innerHTML,new RegExp(`共 ${count*(status?1:3)} 条`));
    assert.doesNotMatch(w.content.innerHTML,/加载失败/);
    assert.equal(w.requests.at(-1).url,'/repair-orders'+(status?'?status='+status:''));
  }
});
