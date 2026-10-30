'use strict';
(() => {
  const app = document.querySelector('#app');
  const modal = document.querySelector('#modal');
  const state = { token: sessionStorage.getItem('equip.token'), user: null, view: 'devices', page: 1, status: '', version: 0, devices: new Map() };
  const labels = { AVAILABLE: '可用', IN_USE: '使用中', MAINTENANCE: '维修中', UNAVAILABLE: '不可用', PENDING: '待处理', APPROVED: '已批准', REJECTED: '已拒绝', CANCELLED: '已取消', PROCESSING: '处理中', COMPLETED: '已完成' };
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const time = value => value ? String(value).replace('T', ' ').slice(0,16) : '—';
  const admin = () => state.user?.role === 'ADMIN';
  const brand = '<div class="brand"><span class="brand-mark">E</span><div>Equip<span style="color:#81917b">.</span><small>设备协作台</small></div></div>';
  const badge = value => `<span class="pill ${['AVAILABLE','APPROVED','COMPLETED'].includes(value)?'good':['PENDING','PROCESSING','IN_USE'].includes(value)?'wait':['MAINTENANCE','REJECTED'].includes(value)?'bad':'neutral'}">${esc(labels[value] || value || '未知')}</span>`;
  function notify(message, error = false) {
    const box = document.querySelector('#notice');
    box.innerHTML = `<div class="toast ${error?'error':''}">${esc(message)}</div>`;
    clearTimeout(notify.timer); notify.timer = setTimeout(() => box.replaceChildren(), 5500);
  }
  function clearSession() {
    state.token = null; state.user = null; state.devices.clear(); state.version++;
    sessionStorage.removeItem('equip.token'); if (modal.open) modal.close(); login();
  }
  async function api(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const headers = { Accept: 'application/json' };
      if (state.token) headers.Authorization = `Bearer ${state.token}`;
      if (options.body !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetch(path, { ...options, headers, signal: controller.signal, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
      const raw = await response.text();
      let data; try { data = JSON.parse(raw); } catch { data = raw; }
      if (!response.ok) {
        if (response.status === 401 && state.user) { clearSession(); throw new Error('登录已失效，请重新登录。'); }
        const fallback = {401:'请先登录。',403:'当前账号没有操作权限。',404:'记录不存在，可能已被更新。',409:'记录冲突，请刷新后重试。'};
        const message = typeof data === 'string' && !data.startsWith('<') ? data : data?.message;
        throw new Error(message || fallback[response.status] || `请求未完成（${response.status}）`);
      }
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('请求超时，请先刷新核对结果，避免重复提交。');
      if (error instanceof TypeError) throw new Error('无法连接服务，请确认后端已启动。');
      throw error;
    } finally { clearTimeout(timeout); }
  }
  function login(register = false) {
    app.innerHTML = `<main class="login"><section class="login-story">${brand}<div><div class="eyebrow">EQUIPMENT & WORK ORDERS</div><h1>资源有序，<br>协作有据。</h1><p>从设备预约到维修处理，把每一次申请、每一个状态，放回清晰的工作流程。</p><div class="flow"><span><b>01 / 预约</b>找到设备，安排使用</span><span><b>02 / 审批</b>检查时段，确认申请</span><span><b>03 / 维修</b>提交问题，跟进处理</span></div></div><div class="story-footer">EQUIP WORKSPACE · 设备预约与工单管理</div></section><section class="login-area"><div class="login-box"><div class="eyebrow">WELCOME TO YOUR WORKSPACE</div><h2>${register?'创建你的账号':'欢迎回到工作台'}</h2><p>${register?'注册普通用户账号，开始预约与报修。':'登录后查看设备、预约及维修进度。'}</p><div class="tabs"><button class="${register?'':'active'}" data-auth="login">账号登录</button><button class="${register?'active':''}" data-auth="register">注册账号</button></div><form id="auth-form"><label class="field">用户名<input name="username" autocomplete="username" maxlength="50" placeholder="请输入用户名" required></label><label class="field">密码<input name="password" type="password" autocomplete="${register?'new-password':'current-password'}" placeholder="请输入密码" required></label><div class="form-error" role="alert"></div><button class="btn primary" type="submit">${register?'创建账号':'登录工作台'} <span aria-hidden="true">↗</span></button></form><div class="login-note">${register?'新账号默认为普通用户。管理员账号由项目负责人在后端配置。':'普通用户管理自己的预约和工单；管理员负责设备维护、预约审批与维修处理。'}</div></div></section></main>`;
    document.querySelectorAll('[data-auth]').forEach(button => button.onclick = () => login(button.dataset.auth === 'register'));
    document.querySelector('#auth-form').onsubmit = async event => {
      event.preventDefault(); const form = event.currentTarget, button = form.querySelector('[type=submit]');
      const body = Object.fromEntries(new FormData(form)); button.disabled = true; form.querySelector('.form-error').textContent = '';
      try {
        if (register) { await api('/users/register', {method:'POST',body}); login(); notify('注册成功，请使用新账号登录。'); }
        else {
          state.token = await api('/users/login', {method:'POST',body});
          try { state.user = await api('/users/me'); } catch (error) { state.token = null; throw error; }
          sessionStorage.setItem('equip.token', state.token); state.view = 'devices'; state.page = 1; state.status = ''; shell(); await load();
        }
      } catch (error) { form.querySelector('.form-error').textContent = error.message; }
      finally { form.elements.password.value = ''; button.disabled = false; }
    };
  }
  const views = {devices:['◫','设备资源','查找设备，安排下一次使用。'],reservations:['▤','我的预约','查看申请进度，管理自己的使用安排。'],repairs:['◇','维修工单','提交设备问题，跟进每一次处理。'],admin:['▦','审批工作台','集中处理预约申请和设备维修。']};
  function shell() {
    app.innerHTML = `<div class="shell"><aside class="sidebar">${brand}<div class="nav-label">WORKSPACE / 工作空间</div><nav class="nav" aria-label="工作台导航">${Object.entries(views).filter(([key])=>key!=='admin'||admin()).map(([key,[icon,title]])=>`<button data-view="${key}" class="${state.view===key?'active':''}"><span class="nav-icon" aria-hidden="true">${icon}</span>${key==='reservations'&&admin()?'全部预约':title}</button>`).join('')}</nav><div class="sidebar-bottom"><div class="sidebar-note">${admin()?'管理视角<br>设备、预约与维修统一协作。':'我的工作空间<br>你的预约和报修进度，都在这里。'}</div><footer>EQUIP / WORKSPACE</footer></div></aside><main class="main"><header class="topbar"><span class="breadcrumb">工作空间 <span style="margin:0 9px">/</span> <span id="crumb"></span></span><div class="account"><span class="pill">${admin()?'管理员':'普通用户'}</span><span class="avatar">${esc((state.user.username||'U').slice(0,1).toUpperCase())}</span><span class="account-name">${esc(state.user.username)}</span><button class="btn text" id="logout">退出</button></div></header><section class="content" id="content"></section></main></div>`;
    document.querySelectorAll('[data-view]').forEach(button=>button.onclick=()=>{state.view=button.dataset.view;state.page=1;state.status='';shell();load();});
    document.querySelector('#logout').onclick=clearSession;
  }
  function head(title, subtitle, extra='') { return `<div class="heading"><div><div class="eyebrow">EQUIP / ${state.view.toUpperCase()}</div><h1>${esc(title)}</h1><p>${esc(subtitle)}</p></div><div class="actions"><button class="btn" data-refresh>↻ 刷新</button>${extra}</div></div>`; }
  function empty(text, subtitle='可以刷新列表，或开始一条新的申请。') {return `<div class="empty"><strong>${esc(text)}</strong>${esc(subtitle)}</div>`;}
  function pager(result) { const last=Math.max(1,Math.ceil(result.total/result.pageSize));return `<div class="pagination"><span>共 ${result.total} 条 · 第 ${result.page} / ${last} 页</span><div class="actions"><button class="btn small" data-page="${result.page-1}" ${result.page<=1?'disabled':''}>上一页</button><button class="btn small" data-page="${result.page+1}" ${result.page>=last?'disabled':''}>下一页</button></div></div>`; }
  const deviceName = id => state.devices.get(id)?.name || `设备 #${id}`;
  function reservationRows(records, manage=false) {return records.map(r=>`<tr><td><strong>#${r.id}</strong></td><td>${esc(deviceName(r.deviceId))}<span class="sub">设备 #${r.deviceId}${admin()?` · 用户 #${r.userId}`:''}</span></td><td>${esc(time(r.startTime))}<span class="sub">至 ${esc(time(r.endTime))}</span></td><td>${badge(r.status)}</td><td><div class="row-actions"><button class="btn text" data-detail="${r.id}">详情</button>${manage&&r.status==='PENDING'?`<button class="btn small" data-action="approve" data-id="${r.id}">批准</button><button class="btn text danger" data-action="reject" data-id="${r.id}">拒绝</button>`:''}${!manage&&['PENDING','APPROVED'].includes(r.status)&&new Date(r.startTime)>new Date()?`<button class="btn text danger" data-action="cancel" data-id="${r.id}">取消</button>`:''}</div></td></tr>`).join('');}
  function reservationTable(records, manage=false) {return records.length?`<div class="table-wrap"><table><thead><tr><th>预约编号</th><th>预约设备</th><th>使用时段</th><th>当前状态</th><th>操作</th></tr></thead><tbody>${reservationRows(records,manage)}</tbody></table></div>`:empty(manage?'暂时没有待审批预约':'暂无符合条件的预约',manage?'新的申请会显示在这里。':'前往设备资源页，选择设备发起预约。');}
  function repairTable(records, manage=false) {return records.length?`<div class="table-wrap"><table><thead><tr><th>工单 / 设备</th><th>问题描述</th><th>提交时间</th><th>状态</th><th>操作</th></tr></thead><tbody>${records.map(r=>`<tr><td><strong>#${r.id} · ${esc(deviceName(r.deviceId))}</strong><span class="sub">设备 #${r.deviceId}${admin()?` · 用户 #${r.userId}`:''}</span></td><td class="description">${esc(r.description)}</td><td>${esc(time(r.createdAt))}</td><td>${badge(r.status)}</td><td>${manage&&r.status==='PENDING'?`<button class="btn small" data-repair="process" data-id="${r.id}">受理工单</button>`:manage&&r.status==='PROCESSING'?`<button class="btn small" data-repair="complete" data-id="${r.id}">完成维修</button>`:'<span class="muted">—</span>'}</td></tr>`).join('')}</tbody></table></div>`:empty('暂无维修工单','设备使用中遇到问题，可以从设备资源页提交报修。');}
  async function load() {
    const version=++state.version, target=document.querySelector('#content'); if(!target)return;
    document.querySelector('#crumb').textContent=admin()&&state.view==='reservations'?'全部预约':views[state.view][1];
    target.innerHTML='<div class="loading" role="status">正在读取最新数据…</div>';
    try {
      let html;
      if(state.view==='devices') {
        const result=await api(`/devices/page?page=${state.page}&pageSize=8`);
        result.records.forEach(d=>state.devices.set(d.id,d));
        html=head('设备资源','浏览设备台账，发起预约或提交维修工单。',admin()?'<button class="btn primary" id="add-device">＋ 新增设备</button>':'')+`<div class="hero"><div><div class="eyebrow" style="margin-bottom:10px">RESOURCE CENTER</div><h2>每一台设备，都有清晰的安排。</h2><p>选择设备并提交使用时段，审批结果可在预约列表中查看。</p></div><div class="hero-number">${result.total}<small>台设备已入库</small></div></div><div class="panel"><div class="panel-head"><div><h2>设备台账</h2><small>可用状态以设备台账为准，预约时段由后端校验</small></div><span class="pill neutral">每页 8 条</span></div>${result.records.length?`<div class="table-wrap"><table><thead><tr><th>设备名称</th><th>存放位置</th><th>设备状态</th><th>操作</th></tr></thead><tbody>${result.records.map(d=>`<tr><td><div class="item-name"><span class="device-icon" aria-hidden="true">◫</span><div><strong>${esc(d.name)}</strong><span class="sub">${esc(d.deviceCode)} · ${esc(d.model||'未填写型号')}</span></div></div></td><td>${esc(d.location)}</td><td>${badge(d.status)}</td><td><div class="row-actions"><button class="btn small" data-book="${d.id}">预约</button><button class="btn text" data-report="${d.id}">报修</button>${admin()?`<button class="btn text" data-edit="${d.id}">编辑</button>`:''}</div></td></tr>`).join('')}</tbody></table></div>`:empty('设备库还是空的',admin()?'点击右上角新增设备，开始演示。':'等待管理员添加设备后，即可发起预约。')}${pager(result)}</div><p class="footnote">提示：设备台账状态与预约时间占用是两个维度，具体时段冲突以审批校验结果为准。</p>`;
      } else if(state.view==='reservations') {
        const result=await api(`/reservations?page=${state.page}&pageSize=8&status=${encodeURIComponent(state.status)}`);
        html=head(admin()?'全部预约':'我的预约',admin()?'查看所有用户的预约记录，前往审批工作台处理申请。':'这里仅展示当前账号的预约记录。')+`<div class="panel"><div class="panel-head"><div><h2>预约记录</h2><small>按创建顺序倒序显示</small></div><div class="toolbar"><label class="muted" for="status-filter" style="font-size:11px">状态</label><select id="status-filter" class="filter"><option value="">全部状态</option>${['PENDING','APPROVED','REJECTED','CANCELLED'].map(s=>`<option value="${s}" ${s===state.status?'selected':''}>${labels[s]}</option>`).join('')}</select></div></div>${reservationTable(result.records)}${pager(result)}</div>`;
      } else if(state.view==='repairs') {
        const records=await api('/repair-orders');
        const filtered=state.status?records.filter(r=>r.status===state.status):records;
        html=head('维修工单',admin()?'查看全部工单，处理待受理及维修中的设备问题。':'查看我提交的问题及管理员处理进度。')+`<div class="panel"><div class="panel-head"><div><h2>${admin()?'全部维修工单':'我的报修记录'}</h2><small>共 ${filtered.length} 条${state.status?' · 已筛选':''}</small></div><div class="toolbar"><select aria-label="工单状态" id="status-filter" class="filter"><option value="">全部状态</option>${['PENDING','PROCESSING','COMPLETED'].map(s=>`<option value="${s}" ${s===state.status?'selected':''}>${labels[s]}</option>`).join('')}</select></div></div>${repairTable(filtered,admin())}</div>`;
      } else {
        if(!admin())throw new Error('当前账号没有管理员权限。');
        const [reservations,repairs,devices]=await Promise.all([api(`/reservations?page=${state.page}&pageSize=8&status=PENDING`),api('/repair-orders'),api('/devices/page?page=1&pageSize=1')]);
        const pending=repairs.filter(r=>r.status!=='COMPLETED');
        html=head('审批工作台','先确认设备与使用时段，再处理预约和维修申请。')+`<div class="stats"><div class="stat"><span>待审批预约<small>等待管理员确认</small></span><strong>${reservations.total}</strong></div><div class="stat"><span>待处理工单<small>待受理与处理中</small></span><strong>${pending.length}</strong></div><div class="stat"><span>设备总数<small>当前设备台账</small></span><strong>${devices.total}</strong></div></div><div class="panel"><div class="panel-head"><div><h2>待审批预约</h2><small>重叠时段会在批准时进行冲突校验</small></div>${badge('PENDING')}</div>${reservationTable(reservations.records,true)}${pager(reservations)}</div><div class="panel"><div class="panel-head"><div><h2>待处理维修</h2><small>受理后进入处理中，维修结束后确认完成</small></div></div>${repairTable(pending,true)}</div>`;
      }
      if(version!==state.version||!state.user)return;
      target.innerHTML=html; bindContent();
    } catch(error) {
      if(version!==state.version||!state.user)return;
      target.innerHTML=head('暂时无法读取数据','请检查连接后重试。')+`<div class="panel">${empty('加载失败',error.message)}</div>`; bindContent();
    }
  }
  function bindContent() {
    document.querySelectorAll('[data-refresh]').forEach(b=>b.onclick=load);
    document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{state.page=Number(b.dataset.page);load();});
    const status=document.querySelector('#status-filter'); if(status)status.onchange=()=>{state.status=status.value;state.page=1;load();};
    document.querySelectorAll('[data-book]').forEach(b=>b.onclick=()=>booking(Number(b.dataset.book)));
    document.querySelectorAll('[data-report]').forEach(b=>b.onclick=()=>report(Number(b.dataset.report)));
    document.querySelectorAll('[data-edit]').forEach(b=>b.onclick=()=>deviceForm(Number(b.dataset.edit)));
    document.querySelector('#add-device')?.addEventListener('click',()=>deviceForm());
    document.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>confirmAction('reservation',Number(b.dataset.id),b.dataset.action));
    document.querySelectorAll('[data-repair]').forEach(b=>b.onclick=()=>confirmAction('repair',Number(b.dataset.id),b.dataset.repair));
    document.querySelectorAll('[data-detail]').forEach(b=>b.onclick=()=>detail(Number(b.dataset.detail)));
  }
  function showModal(title, fields, submitText, action) {
    if(modal.open)modal.close();
    modal.innerHTML=`<div class="dialog-head"><div><div class="eyebrow" style="margin-bottom:9px">EQUIP / WORKSPACE</div><h2 id="dialog-title">${esc(title)}</h2></div><button type="button" class="close" aria-label="关闭">×</button></div><form>${fields}<div class="form-error" role="alert"></div><div class="dialog-footer"><button type="button" class="btn" data-close>返回</button>${action?`<button type="submit" class="btn primary">${esc(submitText)}</button>`:''}</div></form>`;
    modal.querySelectorAll('.close,[data-close]').forEach(b=>b.onclick=()=>modal.close());
    modal.querySelector('form').onsubmit=async event=>{
      event.preventDefault();if(!action)return;const form=event.currentTarget,button=form.querySelector('[type=submit]');button.disabled=true;
      form.querySelector('.form-error').textContent='';
      try{await action(Object.fromEntries(new FormData(form)));modal.close();await load();}
      catch(error){form.querySelector('.form-error').textContent=error.message;}
      finally{button.disabled=false;}
    };
    modal.showModal();
  }
  function booking(id) {
    showModal('发起设备预约',`<div class="callout">${esc(deviceName(id))} · 设备 #${id}<br>提交后进入待审批，最终使用时段以管理员审批结果为准。</div><label class="field">开始时间<input name="startTime" type="datetime-local" required></label><label class="field">结束时间<input name="endTime" type="datetime-local" required></label>`,'提交预约',async data=>{
      if(data.startTime>=data.endTime)throw new Error('结束时间必须晚于开始时间。');
      const saved=await api('/reservations',{method:'POST',body:{deviceId:id,...data}});
      notify(`预约 #${saved.id} 已提交，等待管理员审批。`);
      state.view='reservations';state.page=1;state.status='';shell();
    });
  }
  function report(id) {
    showModal('提交维修工单',`<div class="callout">${esc(deviceName(id))} · 设备 #${id}</div><label class="field">问题描述<textarea name="description" maxlength="500" placeholder="请描述故障现象、发生时间及对使用的影响" required></textarea><small>最多500字，请勿填写密码等敏感信息。</small></label>`,'提交工单',async data=>{
      if(!data.description.trim())throw new Error('请填写问题描述。');
      await api('/repair-orders',{method:'POST',body:{deviceId:id,description:data.description.trim()}});
      notify('报修已提交，可在维修工单中查看进度。');state.view='repairs';state.status='';shell();
    });
  }
  function deviceForm(id) {
    if(!admin())return;const d=state.devices.get(id)||{};
    showModal(id?'编辑设备':'新增设备',`${!id?'<label class="field">设备编码<input name="deviceCode" maxlength="50" required placeholder="例如 LAB-001"></label>':''}<label class="field">设备名称<input name="name" maxlength="50" value="${esc(d.name)}" required placeholder="例如 数字示波器"></label><label class="field">存放位置<input name="location" maxlength="100" value="${esc(d.location)}" required placeholder="例如 A栋 302 实验室"></label><label class="field">设备型号<input name="model" maxlength="50" value="${esc(d.model)}" placeholder="选填"></label>`,'保存设备',async data=>{
      await api(id?`/devices/${id}`:'/devices',{method:id?'PUT':'POST',body:data});notify('设备已保存。');
    });
  }
  function confirmAction(kind,id,action) {
    const names={approve:'批准预约',reject:'拒绝预约',cancel:'取消预约',process:'受理工单',complete:'完成维修'};
    showModal(names[action],`<p class="muted" style="font-size:13px">确认${esc(names[action])} #${id}？操作结果以服务端最新状态和权限校验为准。</p>`,'确认操作',async()=>{
      await api(`/${kind==='reservation'?'reservations':'repair-orders'}/${id}/${action}`,{method:'PUT'});notify(`${names[action]}成功。`);
    });
  }
  async function detail(id) {
    try {const r=await api(`/reservations/${id}`);showModal('预约详情',`<dl class="detail-grid"><div><dt>预约编号</dt><dd>#${r.id}</dd></div><div><dt>状态</dt><dd>${badge(r.status)}</dd></div><div><dt>设备</dt><dd>${esc(deviceName(r.deviceId))} (#${r.deviceId})</dd></div><div><dt>申请人</dt><dd>用户 #${r.userId}</dd></div><div><dt>开始时间</dt><dd>${esc(time(r.startTime))}</dd></div><div><dt>结束时间</dt><dd>${esc(time(r.endTime))}</dd></div><div><dt>提交时间</dt><dd>${esc(time(r.createdAt))}</dd></div></dl>`,'',null);}catch(error){notify(error.message,true);}
  }
  async function init() {
    if(!state.token){login();return;}
    try{state.user=await api('/users/me');shell();await load();}catch{clearSession();notify('请重新登录后继续。',true);}
  }
  init();
})();
