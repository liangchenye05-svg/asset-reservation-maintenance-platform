'use strict';
(() => {
  const app = document.querySelector('#app');
  const modal = document.querySelector('#modal');
  const state = { token: sessionStorage.getItem('equip.token'), user: null, view: 'devices', page: 1, repairPage: 1, status: '', query: '', deviceId: '', version: 0, devices: new Map(), catalogReady: false };
  const labels = { AVAILABLE:'可用', IN_USE:'使用中', MAINTENANCE:'维修中', UNAVAILABLE:'不可用', PENDING:'待处理', APPROVED:'已批准', REJECTED:'已拒绝', CANCELLED:'已取消', PROCESSING:'处理中', COMPLETED:'已完成' };
  const views = {devices:['◫','设备资源','查找设备，安排使用与维护。'], reservations:['▤','我的预约','每一次申请，都有清晰的进度。'], repairs:['◇','维修工单','记录问题，跟进维修全过程。'], admin:['▦','审批工作台','集中处理预约与维修待办。']};
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const time = value => value ? String(value).replace('T',' ').slice(0,16) : '—';
  const admin = () => state.user?.role === 'ADMIN';
  const deviceName = id => state.devices.get(id)?.name || `设备 #${id}`;
  const brand = '<div class="brand"><span class="brand-mark" aria-hidden="true">E</span><div>Equip<span class="brand-dot">.</span><small>资产与运维协作平台</small></div></div>';
  const badge = value => `<span class="pill ${['AVAILABLE','APPROVED','COMPLETED'].includes(value)?'good':['PENDING','PROCESSING','IN_USE'].includes(value)?'wait':['MAINTENANCE','REJECTED'].includes(value)?'bad':'neutral'}">${esc(labels[value] || value || '未知')}</span>`;

  function notify(message, error=false) {
    const box=document.querySelector('#notice');
    box.innerHTML=`<div class="toast ${error?'error':''}">${esc(message)}</div>`;
    clearTimeout(notify.timer);notify.timer=setTimeout(()=>box.replaceChildren(),5500);
  }
  function clearSession() {
    state.token=null;state.user=null;state.devices.clear();state.catalogReady=false;state.version++;
    sessionStorage.removeItem('equip.token');if(modal.open)modal.close();login();
  }
  async function api(path, options={}) {
    const controller=new AbortController();
    const timeout=setTimeout(()=>controller.abort(),15000);
    try {
      const headers={Accept:'application/json'};
      if(state.token)headers.Authorization=`Bearer ${state.token}`;
      if(options.body!==undefined)headers['Content-Type']='application/json';
      const response=await fetch(path,{...options,headers,signal:controller.signal,body:options.body===undefined?undefined:JSON.stringify(options.body)});
      const raw=await response.text();
      let data;try {data=JSON.parse(raw);} catch {data=raw;}
      if(!response.ok) {
        if(response.status===401&&state.user){clearSession();throw new Error('登录已失效，请重新登录。');}
        const fallback={401:'请先登录。',403:'当前账号没有操作权限。',404:'记录不存在，可能已被更新。',409:'记录冲突，请刷新后重试。'};
        const message=typeof data==='string'&&!data.startsWith('<')?data:data?.message;
        const error=new Error(message||fallback[response.status]||`请求未完成（${response.status}）`);
        error.status=response.status;throw error;
      }
      return data;
    } catch(error) {
      if(error.name==='AbortError')throw new Error('请求超时，请先刷新核对结果，避免重复提交。');
      if(error instanceof TypeError)throw new Error('无法连接服务，请确认后端已启动。');
      throw error;
    } finally {clearTimeout(timeout);}
  }
  function login(register=false) {
    app.innerHTML=`<main class="login"><section class="login-story">${brand}<div class="story-copy"><div class="eyebrow">ASSET & SERVICE WORKSPACE</div><h1>资源有序，<br>协作有据。</h1><p>让设备预约、审批与维修进度，在同一个工作空间清晰流转。</p><div class="flow"><span><b>01 / 预约</b>找到设备，安排使用</span><span><b>02 / 审批</b>核对时段，确认申请</span><span><b>03 / 维修</b>记录问题，跟进处理</span></div></div><div class="story-footer">EQUIP WORKSPACE · 企业内部资产协作</div></section><section class="login-area"><div class="login-box"><div class="eyebrow">WELCOME TO EQUIP</div><h2>${register?'创建工作账号':'欢迎回到工作台'}</h2><p>${register?'注册后即可预约设备、提交报修。':'登录以查看设备资源和你的协作进度。'}</p><div class="tabs" aria-label="账号操作"><button type="button" class="${register?'':'active'}" data-auth="login">账号登录</button><button type="button" class="${register?'active':''}" data-auth="register">注册账号</button></div><form id="auth-form"><label class="field">用户名<input name="username" autocomplete="username" maxlength="50" placeholder="请输入用户名" required></label><label class="field">密码<input name="password" type="password" autocomplete="${register?'new-password':'current-password'}" placeholder="请输入密码" required></label>${register?'<label class="field">确认密码<input name="confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" required></label>':''}<div class="form-error" role="alert" aria-live="polite"></div><button class="btn primary" type="submit">${register?'创建账号':'登录工作台'} <span aria-hidden="true">↗</span></button></form><div class="login-note">${register?'新账号为普通用户，管理员权限由项目负责人配置。':'普通用户跟进自己的申请，管理员维护设备并处理审批与维修。'}</div></div></section></main>`;
    document.querySelectorAll('[data-auth]').forEach(b=>b.onclick=()=>login(b.dataset.auth==='register'));
    document.querySelector('#auth-form').onsubmit=async event=>{
      event.preventDefault();const form=event.currentTarget,button=form.querySelector('[type=submit]');if(button.disabled)return;
      const data=Object.fromEntries(new FormData(form)),body={username:data.username.trim(),password:data.password};
      form.querySelector('.form-error').textContent='';
      if(!body.username||!body.password.trim()){form.querySelector('.form-error').textContent='用户名和密码不能为空或只有空格。';return;}
      if(register&&data.password!==data.confirmPassword){form.querySelector('.form-error').textContent='两次输入的密码不一致。';return;}
      button.disabled=true;button.textContent=register?'正在创建…':'正在登录…';
      try {
        if(register){await api('/users/register',{method:'POST',body});login();notify('注册成功，请使用新账号登录。');}
        else {
          state.token=await api('/users/login',{method:'POST',body});
          try {state.user=await api('/users/me');} catch(error){state.token=null;throw error;}
          sessionStorage.setItem('equip.token',state.token);state.catalogReady=false;navigate('devices');
        }
      } catch(error){form.querySelector('.form-error').textContent=error.message;}
      finally {form.elements.password.value='';if(form.elements.confirmPassword)form.elements.confirmPassword.value='';button.disabled=false;button.textContent=register?'创建账号':'登录工作台';}
    };
  }
  function navigate(view) {
    state.view=view;state.page=1;state.repairPage=1;state.status='';state.query='';state.deviceId='';shell();load();
  }
  function shell() {
    app.innerHTML=`<div class="shell"><aside class="sidebar">${brand}<div class="nav-label">工作空间</div><nav class="nav" aria-label="工作台导航">${Object.entries(views).filter(([key])=>key!=='admin'||admin()).map(([key,[icon,title]])=>`<button data-view="${key}" class="${state.view===key?'active':''}" ${state.view===key?'aria-current="page"':''}><span class="nav-icon" aria-hidden="true">${icon}</span>${key==='reservations'&&admin()?'全部预约':title}</button>`).join('')}</nav><div class="sidebar-bottom"><div class="sidebar-note"><strong>${admin()?'协作从清晰的待办开始':'让每次使用都井然有序'}</strong><br>${admin()?'设备、预约、维修，一处跟进。':'查看进度，安排下一次使用。'}</div><footer>EQUIP / WORKSPACE</footer></div></aside><main class="main"><header class="topbar"><span class="breadcrumb">工作空间 <span aria-hidden="true">/</span> <span id="crumb"></span></span><div class="account"><span class="pill neutral">${admin()?'管理员':'普通用户'}</span><span class="avatar" aria-hidden="true">${esc((state.user.username||'U').slice(0,1).toUpperCase())}</span><span class="account-name">${esc(state.user.username)}</span><button class="btn text" id="logout">退出</button></div></header><section class="content" id="content" aria-live="polite"></section></main></div>`;
    document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>navigate(b.dataset.view));
    document.querySelector('#logout').onclick=clearSession;
  }
  function head(title, subtitle, extra='') {
    return `<div class="heading"><div><div class="eyebrow">EQUIP / ${state.view.toUpperCase()}</div><h1>${esc(title)}</h1><p>${esc(subtitle)}</p></div><div class="actions"><button class="btn" data-refresh><span aria-hidden="true">↻</span> 刷新</button>${extra}</div></div>`;
  }
  function empty(text, subtitle='调整筛选条件，或刷新后重试。') {
    return `<div class="empty"><span class="empty-icon" aria-hidden="true">◫</span><strong>${esc(text)}</strong><p>${esc(subtitle)}</p></div>`;
  }
  function pager(result, attribute='page') {
    const last=Math.max(1,Math.ceil(result.total/result.pageSize));
    return `<div class="pagination"><span>共 ${result.total} 条 · 第 ${result.page} / ${last} 页</span><div class="actions"><button class="btn small" data-${attribute}="${result.page-1}" ${result.page<=1?'disabled':''}>上一页</button><button class="btn small" data-${attribute}="${result.page+1}" ${result.page>=last?'disabled':''}>下一页</button></div></div>`;
  }
  function slicePage(records, page) {
    const current=Math.min(page,Math.max(1,Math.ceil(records.length/8)));
    return {total:records.length,page:current,pageSize:8,records:records.slice((current-1)*8,current*8)};
  }
  function filters({search=false,statuses=[],device=false}) {
    return `<form id="filter-form" class="filters">${search?`<label class="search-field"><span class="sr-only">搜索</span><input name="query" type="search" value="${esc(state.query)}" placeholder="${state.view==='devices'?'搜索设备名称、编码或位置':'搜索工单编号、设备或问题'}" aria-label="搜索"><button class="btn small" type="submit">搜索</button></label>`:''}${statuses.length?`<label class="filter-field"><span>状态</span><select id="status-filter" class="filter" aria-label="${state.view==='repairs'?'工单状态':'状态'}"><option value="">全部状态</option>${statuses.map(s=>`<option value="${s}" ${s===state.status?'selected':''}>${labels[s]}</option>`).join('')}</select></label>`:''}${device?`<label class="filter-field"><span>设备</span><select id="device-filter" class="filter" aria-label="筛选设备"><option value="">全部设备</option>${Array.from(state.devices.values()).map(d=>`<option value="${d.id}" ${String(d.id)===state.deviceId?'selected':''}>${esc(d.name)} · #${d.id}</option>`).join('')}</select></label>`:''}<button class="btn text" type="button" id="reset-filters">重置</button></form>`;
  }
  async function catalog() {
    const first=await api('/devices/page?page=1&pageSize=100'),records=[...first.records];
    for(let page=2;page<=Math.ceil(first.total/100);page++)records.push(...(await api(`/devices/page?page=${page}&pageSize=100`)).records);
    return new Map(records.map(d=>[d.id,d]));
  }
  function reservationTable(records, manage=false) {
    if(!records.length)return empty(manage?'暂时没有待审批预约':'暂无符合条件的预约',manage?'新的预约申请会显示在这里。':'可以调整筛选，或前往设备资源发起预约。');
    return `<div class="table-wrap"><table><caption class="sr-only">预约记录</caption><thead><tr><th>预约编号</th><th>预约设备</th><th>使用时段</th><th>当前状态</th><th>操作</th></tr></thead><tbody>${records.map(r=>`<tr><td data-label="预约编号"><strong>#${r.id}</strong></td><td data-label="预约设备">${esc(deviceName(r.deviceId))}<span class="sub">设备 #${r.deviceId}${admin()?` · 用户 #${r.userId}`:''}</span></td><td data-label="使用时段">${esc(time(r.startTime))}<span class="sub">至 ${esc(time(r.endTime))}</span></td><td data-label="当前状态">${badge(r.status)}</td><td data-label="操作"><div class="row-actions"><button class="btn text" data-detail="${r.id}">详情</button>${manage&&r.status==='PENDING'?`<button class="btn small" data-action="approve" data-id="${r.id}">批准</button><button class="btn text danger" data-action="reject" data-id="${r.id}">拒绝</button>`:''}${!manage&&['PENDING','APPROVED'].includes(r.status)&&new Date(r.startTime)>new Date()?`<button class="btn text danger" data-action="cancel" data-id="${r.id}">取消</button>`:''}</div></td></tr>`).join('')}</tbody></table></div>`;
  }
  function repairTable(records, manage=false) {
    if(!records.length)return empty('暂无维修工单','调整筛选，或从设备资源页提交报修。');
    return `<div class="table-wrap"><table><caption class="sr-only">维修工单记录</caption><thead><tr><th>工单 / 设备</th><th>问题描述</th><th>提交时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${records.map(r=>`<tr><td data-label="工单 / 设备"><strong>#${r.id} · ${esc(deviceName(r.deviceId))}</strong><span class="sub">设备 #${r.deviceId}${admin()?` · 用户 #${r.userId}`:''}</span></td><td data-label="问题描述" class="description">${esc(r.description)}${r.historyComplete===false?'<span class="sub">历史不完整 · 仅记录已知真实操作</span>':''}</td><td data-label="提交时间">${esc(time(r.createdAt))}</td><td data-label="状态">${badge(r.status)}${r.manualReviewRequired?'<span class="sub">需人工核对 · 自动恢复暂停</span>':''}</td><td data-label="操作"><div class="row-actions"><button class="btn text" data-repair-detail="${r.id}">详情</button>${manage&&r.status==='PENDING'?`<button class="btn small" data-repair="process" data-id="${r.id}">受理工单</button>`:manage&&r.status==='PROCESSING'?`<button class="btn small" data-repair="complete" data-id="${r.id}">完成维修</button>`:''}</div></td></tr>`).join('')}</tbody></table></div>`;
  }
  async function load(refresh=false) {
    const version=++state.version,target=document.querySelector('#content');if(!target)return;
    document.querySelector('#crumb').textContent=admin()&&state.view==='reservations'?'全部预约':views[state.view][1];
    target.innerHTML='<div class="loading" role="status"><span class="spinner" aria-hidden="true"></span>正在读取最新数据…</div>';
    try {
      if(!state.catalogReady||refresh){const devices=await catalog();if(version!==state.version||!state.user)return;state.devices=devices;state.catalogReady=true;}
      let html;
      if(state.view==='devices') {
        const query=state.query.toLocaleLowerCase();
        const devices=Array.from(state.devices.values());
        const filtered=devices.filter(d=>(!state.status||d.status===state.status)&&(!query||`${d.name} ${d.deviceCode} ${d.location} ${d.model||''}`.toLocaleLowerCase().includes(query)));
        const result=slicePage(filtered,state.page);state.page=result.page;
        html=head('设备资源','找到合适的设备，让下一次使用安排更从容。',admin()?'<button class="btn primary" id="add-device">＋ 新增设备</button>':'')+`<div class="hero"><div><div class="eyebrow">RESOURCE CENTER</div><h2>让资源有序流转。</h2><p>选择设备发起预约，或记录需要维护的问题。</p></div><div class="hero-number">${devices.length}<small>台设备已入库</small></div></div><div class="panel"><div class="panel-head"><div><h2>设备台账</h2><small>按设备编号顺序展示 · 共 ${devices.length} 台设备</small></div><span class="pill neutral">每页 8 条</span></div>${filters({search:true,statuses:['AVAILABLE','IN_USE','MAINTENANCE','UNAVAILABLE']})}${result.records.length?`<div class="table-wrap"><table><caption class="sr-only">设备台账</caption><thead><tr><th>设备名称</th><th>存放位置</th><th>设备状态</th><th>操作</th></tr></thead><tbody>${result.records.map(d=>`<tr><td data-label="设备名称"><div class="item-name"><span class="device-icon" aria-hidden="true">◫</span><div><strong>${esc(d.name)}</strong><span class="sub">${esc(d.deviceCode)} · ${esc(d.model||'未填写型号')}</span></div></div></td><td data-label="存放位置">${esc(d.location)}</td><td data-label="设备状态">${badge(d.status)}</td><td data-label="操作"><div class="row-actions"><button class="btn text" data-device-detail="${d.id}">详情</button><button class="btn small" data-book="${d.id}">预约</button><button class="btn text" data-report="${d.id}">报修</button>${admin()?`<button class="btn text" data-edit="${d.id}">编辑</button><button class="btn text danger" data-delete="${d.id}">删除</button>`:''}</div></td></tr>`).join('')}</tbody></table></div>`:empty(devices.length?'没有找到符合条件的设备':'设备库还是空的',devices.length?'试试其他关键词或重置筛选条件。':admin()?'点击新增设备，建立第一条台账。':'管理员添加设备后，即可发起预约。')}${pager(result)}</div><p class="footnote">受理维修后暂停预约申请与批准；已有预约保留，由管理员人工协调。</p>`;
      } else if(state.view==='reservations') {
        const params=new URLSearchParams({page:String(state.page),pageSize:'8'});if(state.status)params.set('status',state.status);if(state.deviceId)params.set('deviceId',state.deviceId);
        const result=await api('/reservations?'+params);
        html=head(admin()?'全部预约':'我的预约',admin()?'核对预约记录，在审批工作台处理待办。':'查看自己的申请进度、使用时段和预约详情。')+`<div class="panel"><div class="panel-head"><div><h2>预约记录</h2><small>按提交顺序倒序显示</small></div><button class="btn small" data-go="${admin()?'admin':'devices'}">${admin()?'前往审批':'预约设备'} ↗</button></div>${filters({statuses:['PENDING','APPROVED','REJECTED','CANCELLED'],device:true})}${reservationTable(result.records)}${pager(result)}</div>`;
      } else if(state.view==='repairs') {
        const params=new URLSearchParams({page:String(state.page),pageSize:'8'});if(state.status)params.set('status',state.status);if(state.deviceId)params.set('deviceId',state.deviceId);if(state.query.trim())params.set('keyword',state.query.trim());
        const result=await api('/repair-orders/page?'+params);
        const last=Math.max(1,Math.ceil(result.total/result.pageSize));
        if(state.page>last){state.page=last;return load();}
        html=head('维修工单',admin()?'从受理到完成，跟进每一台设备的维修进度。':'查看自己提交的问题及处理进度。')+`<div class="panel"><div class="panel-head"><div><h2>${admin()?'全部维修工单':'我的报修记录'}</h2><small>共 ${result.total} 条${state.status||state.query||state.deviceId?' · 已筛选':''}</small></div><button class="btn small" data-go="devices">提交报修 ↗</button></div>${filters({search:true,statuses:['PENDING','PROCESSING','COMPLETED'],device:true})}${repairTable(result.records,admin())}${pager(result)}</div>`;
      } else {
        if(!admin()){const error=new Error('当前账号没有管理员权限。');error.status=403;throw error;}
        const [reservations,repairPage]=await Promise.all([api(`/reservations?page=${state.page}&pageSize=8&status=PENDING`),api(`/repair-orders/page?page=${state.repairPage}&pageSize=8&status=OPEN`)]);
        const last=Math.max(1,Math.ceil(repairPage.total/repairPage.pageSize));
        if(state.repairPage>last){state.repairPage=last;return load();}
        html=head('审批工作台','先核对设备与时段，再处理预约和维修待办。')+`<div class="stats"><button class="stat" data-go="reservations"><span>待审批预约<small>等待确认使用安排</small></span><strong>${reservations.total}</strong></button><button class="stat" data-go="repairs"><span>待处理工单<small>待受理与维修中</small></span><strong>${repairPage.total}</strong></button><button class="stat" data-go="devices"><span>设备总数<small>查看资源台账</small></span><strong>${state.devices.size}</strong></button></div><div class="panel"><div class="panel-head"><div><h2>待审批预约</h2><small>批准前会核对重叠时段</small></div>${badge('PENDING')}</div>${reservationTable(reservations.records,true)}${pager(reservations)}</div><div class="panel"><div class="panel-head"><div><h2>待处理维修</h2><small>受理 → 处理中 → 完成维修</small></div><button class="btn text" data-go="repairs">查看全部 ↗</button></div>${repairTable(repairPage.records,true)}${pager(repairPage,'repair-page')}</div>`;
      }
      if(version!==state.version||!state.user)return;
      target.innerHTML=html;bindContent();
    } catch(error) {
      if(version!==state.version||!state.user)return;
      target.innerHTML=head(error.status===403?'当前账号没有访问权限':'暂时无法读取数据','可以刷新重试，或返回设备资源。')+`<div class="panel error-panel" role="alert">${empty(error.status===403?'权限不足':'加载失败',error.message)}<div class="empty-actions"><button class="btn primary" data-refresh>重试</button><button class="btn" data-go="devices">返回设备资源</button></div></div>`;bindContent();
    }
  }
  function bindContent() {
    document.querySelectorAll('[data-refresh]').forEach(b=>b.onclick=()=>load(true));
    document.querySelectorAll('[data-go]').forEach(b=>b.onclick=()=>navigate(b.dataset.go));
    document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{state.page=Number(b.dataset.page);load();});
    document.querySelectorAll('[data-repair-page]').forEach(b=>b.onclick=()=>{state.repairPage=Number(b.dataset.repairPage);load();});
    const status=document.querySelector('#status-filter');if(status)status.onchange=()=>{state.status=status.value;state.page=1;load();};
    const device=document.querySelector('#device-filter');if(device)device.onchange=()=>{state.deviceId=device.value;state.page=1;load();};
    const filter=document.querySelector('#filter-form');if(filter)filter.onsubmit=event=>{event.preventDefault();state.query=String(new FormData(filter).get('query')||'').trim();state.page=1;load();};
    document.querySelector('#reset-filters')?.addEventListener('click',()=>{state.query='';state.status='';state.deviceId='';state.page=1;load();});
    document.querySelectorAll('[data-book]').forEach(b=>b.onclick=()=>booking(Number(b.dataset.book)));
    document.querySelectorAll('[data-report]').forEach(b=>b.onclick=()=>report(Number(b.dataset.report)));
    document.querySelectorAll('[data-edit]').forEach(b=>b.onclick=()=>deviceForm(Number(b.dataset.edit)));
    document.querySelectorAll('[data-delete]').forEach(b=>b.onclick=()=>confirmAction('device',Number(b.dataset.delete),'delete'));
    document.querySelector('#add-device')?.addEventListener('click',()=>deviceForm());
    document.querySelectorAll('[data-device-detail]').forEach(b=>b.onclick=()=>detail('device',Number(b.dataset.deviceDetail)));
    document.querySelectorAll('[data-detail]').forEach(b=>b.onclick=()=>detail('reservation',Number(b.dataset.detail)));
    document.querySelectorAll('[data-repair-detail]').forEach(b=>b.onclick=()=>detail('repair',Number(b.dataset.repairDetail)));
    document.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>confirmAction('reservation',Number(b.dataset.id),b.dataset.action));
    document.querySelectorAll('[data-repair]').forEach(b=>b.onclick=()=>confirmAction('repair',Number(b.dataset.id),b.dataset.repair));
  }
  function showModal(title, fields, submitText, action) {
    if(modal.open)modal.close();
    modal.innerHTML=`<div class="dialog-head"><div><div class="eyebrow">EQUIP / WORKFLOW</div><h2 id="dialog-title">${esc(title)}</h2></div><button type="button" class="close" aria-label="关闭">×</button></div><form>${fields}<div class="form-error" role="alert" aria-live="polite"></div><div class="dialog-footer"><button type="button" class="btn" data-close>${action?'返回':'关闭'}</button>${action?`<button type="submit" class="btn primary">${esc(submitText)}</button>`:''}</div></form>`;
    modal.dataset.busy='false';modal.oncancel=event=>{if(modal.dataset.busy==='true')event.preventDefault();};
    modal.querySelectorAll('.close,[data-close]').forEach(b=>b.onclick=()=>modal.close());
    modal.querySelector('form').onsubmit=async event=>{
      event.preventDefault();if(!action)return;const form=event.currentTarget,button=form.querySelector('[type=submit]');if(button.disabled)return;
      form.querySelector('.form-error').textContent='';modal.dataset.busy='true';modal.querySelectorAll('button').forEach(b=>b.disabled=true);button.textContent='正在提交…';
      try {await action(Object.fromEntries(new FormData(form)));modal.close();await load();}
      catch(error){form.querySelector('.form-error').textContent=error.message;}
      finally {modal.dataset.busy='false';modal.querySelectorAll('button').forEach(b=>b.disabled=false);button.textContent=submitText;}
    };
    modal.showModal();
    (modal.querySelector("input,textarea,select")||modal.querySelector("[data-close]")).focus();
  }
  function booking(id) {
    showModal('发起设备预约',`<div class="callout">${esc(deviceName(id))} · 设备 #${id}<br>提交后等待审批，批准时由服务端核对时段冲突。</div><div class="two-col"><label class="field">开始时间<input name="startTime" type="datetime-local" required></label><label class="field">结束时间<input name="endTime" type="datetime-local" required></label></div>`,'提交预约',async data=>{
      if(data.startTime>=data.endTime)throw new Error('结束时间必须晚于开始时间。');
      const saved=await api('/reservations',{method:'POST',body:{deviceId:id,...data}});
      notify(`预约 #${saved.id} 已提交，等待管理员审批。`);state.view='reservations';state.page=1;state.status='';state.query='';state.deviceId='';shell();
    });
  }
  function report(id) {
    showModal('提交维修工单',`<div class="callout">${esc(deviceName(id))} · 设备 #${id}</div><label class="field">问题描述<textarea name="description" maxlength="500" placeholder="请描述故障现象、发生时间及对使用的影响" aria-describedby="repair-hint" required></textarea></label><p class="field-hint" id="repair-hint">最多500字，请勿填写密码等敏感信息。</p>`,'提交工单',async data=>{
      if(!data.description.trim())throw new Error('请填写问题描述。');
      await api('/repair-orders',{method:'POST',body:{deviceId:id,description:data.description.trim()}});
      notify('报修已提交，可在维修工单中查看进度。');state.view='repairs';state.page=1;state.status='';state.query='';state.deviceId='';shell();
    });
  }
  function deviceForm(id) {
    if(!admin())return;const d=state.devices.get(id)||{};
    showModal(id?'编辑设备':'新增设备',`${!id?'<label class="field">设备编码<input name="deviceCode" maxlength="50" required placeholder="例如 LAB-001"></label>':''}<label class="field">设备名称<input name="name" maxlength="50" value="${esc(d.name)}" required placeholder="例如 数字示波器"></label><label class="field">存放位置<input name="location" maxlength="100" value="${esc(d.location)}" required placeholder="例如 A栋 302 实验室"></label><label class="field">设备型号<input name="model" maxlength="50" value="${esc(d.model)}" placeholder="选填"></label>`,'保存设备',async data=>{
      const body=Object.fromEntries(Object.entries(data).map(([k,v])=>[k,v.trim()]));
      if(!body.name||!body.location||(!id&&!body.deviceCode))throw new Error('设备编码、名称和存放位置不能只有空格。');
      await api(id?`/devices/${id}`:'/devices',{method:id?'PUT':'POST',body});state.catalogReady=false;notify('设备已保存。');
    });
  }
  async function confirmAction(kind,id,action) {
    const names={approve:'批准预约',reject:'拒绝预约',cancel:'取消预约',process:'受理工单',complete:'完成维修',delete:'删除设备'};
    const subtitle=action==='delete'?'删除后无法在页面恢复；已有预约或工单引用的设备无法删除。':'操作完成后将刷新记录，以服务端最新状态为准。';
    let impact='',reviewRequired=false;
    if(kind==='repair'&&action==='process'){
      try{
        const affected=await api(`/repair-orders/${id}/maintenance-impact`);
        impact=`<section class="detail-section"><h3>设备维修影响</h3><p>受理后设备进入维修状态，暂停新预约申请与批准。已有预约不会自动取消。</p><p>尚未结束的已批准预约：<strong>${affected.approvedReservationCount}</strong> 条，请人工协调。</p>${affected.reservations.length?`<ul class="impact-list">${affected.reservations.map(r=>`<li>预约 #${r.id} · ${esc(time(r.startTime))} 至 ${esc(time(r.endTime))}</li>`).join('')}</ul><small>最多显示前100条；完整记录可在预约页按设备查看。</small>`:''}</section>`;
      }catch(error){notify(error.message,true);return;}
    }
    if(kind==='repair'&&action==='complete'){
      try {
        const order=await api(`/repair-orders/${id}`);reviewRequired=order.manualReviewRequired;
        impact=reviewRequired?'<div class="callout danger-callout" role="alert"><strong>必须人工核对</strong><p>该设备缺少可靠维修前状态依据，自动恢复已暂停。请核对现场情况和已有预约，填写真实处理说明。</p><p>本次仅完成工单，设备保持现状；不会猜测原状态或自动恢复为可用，恢复暂停不会自动解除。</p></div><label class="field">人工核对说明<textarea name="reviewNote" required maxlength="500" placeholder="说明实际核对情况与后续协调安排，请勿填写密码"></textarea></label>':'<p class="footnote">同设备仍有待处理或处理中的工单时，设备继续保持维修状态。全部工单结束且有可靠快照时才允许自动恢复；人工维修或无快照时保留现状。</p>';
      }catch(error){notify(error.message,true);return;}
    }
    showModal(names[action],`<div class="callout ${action==='delete'?'danger-callout':''}">确认${esc(names[action])} #${id}？<br>${esc(subtitle)}</div>${impact}`,'确认操作',async data=>{
      const route=kind==='device'?`/devices/${id}`:`/${kind==='reservation'?'reservations':'repair-orders'}/${id}/${action}`;
      const options={method:action==='delete'?'DELETE':'PUT'};
      if(reviewRequired){if(!data.reviewNote.trim())throw new Error('请填写真实的人工核对说明。');options.body={reviewNote:data.reviewNote.trim()};}
      await api(route,options);if(kind==='device'||kind==='repair')state.catalogReady=false;notify(`${names[action]}成功。`);
    });
  }
  async function detail(kind,id) {
    try {
      const route=kind==='device'?'devices':kind==='repair'?'repair-orders':'reservations';
      const r=await api(`/${route}/${id}`);
      let history='';
      if(kind==='repair'){
        const events=await api(`/repair-orders/${id}/history`),actions={CREATED:'提交报修',PROCESSED:'受理维修',COMPLETED:'完成维修'};
        history=`<section class="detail-section"><h3>操作历史</h3>${r.historyComplete===false?'<p class="callout" role="status">历史不完整：升级前记录缺失，以下仅显示已知真实操作；后续操作不会补齐旧历史。</p>':''}${r.manualReviewRequired?'<p class="callout danger-callout" role="alert">设备自动恢复暂停，管理员完成前必须填写人工核对说明；完成后保留设备现状。</p>':''}${events.length?`<ol class="history-list">${events.map(e=>`<li><strong>${esc(actions[e.action]||e.action)}</strong><span>${e.fromStatus?`${esc(labels[e.fromStatus]||e.fromStatus)} → `:''}${esc(labels[e.toStatus]||e.toStatus)}</span><small>用户 #${e.actorId} · ${esc(time(e.createdAt))}</small>${e.reviewNote?`<p>人工核对说明：${esc(e.reviewNote)}</p>`:''}</li>`).join('')}</ol>`:'<p class="footnote">暂无可追溯操作记录。旧工单不会补造历史，后续真实操作将记录在这里。</p>'}</section>`;
      }
      let fields;
      if(kind==='device')fields=[['设备编号',`#${r.id}`],['设备状态',badge(r.status)],...(r.maintenanceRestoreBlocked?[['自动恢复','暂停，需后续人工核对；工单完成不会解除']]:[]),['设备名称',esc(r.name)],['设备编码',esc(r.deviceCode)],['存放位置',esc(r.location)],['型号',esc(r.model||'未填写')],['入库时间',esc(time(r.createdAt))]];
      else fields=[['记录编号',`#${r.id}`],['当前状态',badge(r.status)],['设备',esc(deviceName(r.deviceId))],['提交时间',esc(time(r.createdAt))],...(kind==='repair'?[['更新时间',esc(time(r.updatedAt))]]:[['开始时间',esc(time(r.startTime))],['结束时间',esc(time(r.endTime))]]),...(admin()?[['申请人',`用户 #${r.userId}`]]:[])];
      showModal(kind==='device'?'设备详情':kind==='repair'?'维修工单详情':'预约详情',`<dl class="detail-grid">${fields.map(([label,value])=>`<div><dt>${label}</dt><dd>${value}</dd></div>`).join('')}</dl>${kind==='repair'?`<section class="detail-section"><h3>问题描述</h3><p class="detail-description">${esc(r.description)}</p></section>${history}`:''}`,'',null);
    } catch(error){notify(error.message,true);}
  }
  async function init() {
    if(!state.token){login();return;}
    try {state.user=await api('/users/me');shell();await load();} catch {clearSession();notify('请重新登录后继续。',true);}
  }
  init();
})();
