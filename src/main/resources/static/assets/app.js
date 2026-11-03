const content=document.querySelector('#content');
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const stamp=value=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';
const badge=value=>`<span class="badge ${esc(value)}">${esc(value)}</span>`;
const hash=value=>`<code class="hash" title="${esc(value)}">${esc(value||'等待报告生成')}</code>`;
const link=(type,id,label)=>`<a href="/${type}/${encodeURIComponent(id)}">${esc(label)} <span class="arrow">↗</span></a>`;
const heading=(eyebrow,title,description,action='')=>`<section class="page-heading"><div><p class="eyebrow">${eyebrow}</p><h1>${esc(title)}</h1><p class="muted">${esc(description)}</p></div>${action}</section>`;
const panel=(title,body,extra='')=>`<section class="panel"><div class="panel-title"><h2>${title}</h2>${extra}</div>${body}</section>`;
function toast(message,error=false) {const el=document.querySelector('#toast');el.textContent=message;el.className=error?'error':'';el.hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>el.hidden=true,8000);}
async function api(path,options={}) {
  const headers={...options.headers};
  if(options.body && !(options.body instanceof FormData)) {headers['Content-Type']='application/json';options.body=JSON.stringify(options.body);}
  if(options.method && options.method!=='GET') headers[document.querySelector('meta[name=csrf-header]').content]=document.querySelector('meta[name=csrf-token]').content;
  const response=await fetch('/api/v1'+path,{...options,headers});
  const text=await response.text();let data;
  try {data=text?JSON.parse(text):null;}catch {throw new Error(`服务器响应格式错误 (${response.status})`);}
  if(!response.ok) throw new Error(Array.isArray(data?.error)?data.error.join('；'):(data?.error||`请求失败 (${response.status})`));
  return data;
}
function form(id,handler) {
  document.querySelector('#'+id)?.addEventListener('submit',async event=>{
    event.preventDefault();const buttons=[...event.target.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);
    try {await handler(Object.fromEntries(new FormData(event.target)),event.target);}catch(error){toast(error.message,true);}finally{buttons.forEach(b=>b.disabled=false);}
  });
}
function action(selector,handler) {document.querySelectorAll(selector).forEach(el=>el.addEventListener('click',async()=>{el.disabled=true;try{await handler(el);}catch(e){toast(e.message,true);}finally{el.disabled=false;}}));}
async function paged(id,path,headers,row) {
  const host=document.querySelector('#'+id);let cursor=null;
  host.innerHTML=`<div class="table-wrap"><table><thead><tr>${headers.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody></tbody></table></div><p class="empty" hidden>暂无记录</p><button class="secondary more" hidden>加载更多 ↓</button>`;
  const more=host.querySelector('.more');let count=0;
  async function load() {
    more.disabled=true;
    try {
      const page=await api(path+(path.includes('?')?'&':'?')+(cursor?'cursor='+encodeURIComponent(cursor):''));
      count+=page.items.length;host.querySelector('tbody').insertAdjacentHTML('beforeend',page.items.map(row).join(''));
      host.querySelector('.empty').hidden=count>0;cursor=page.nextCursor;more.hidden=!cursor;
    }finally{more.disabled=false;}
  }
  more.onclick=()=>load().catch(e=>toast(e.message,true));await load();
}
async function roleFor(project) {const teams=await api('/teams');return teams.find(t=>t.id===project.teamId)?.role;}
async function refreshContext() {
  const context=await api('/context'),select=document.querySelector('#operator-select');
  select.innerHTML=context.operators.map(operator=>`<option value="${esc(operator.id)}" ${operator.id===context.operator.id?'selected':''}>${esc(operator.name)}</option>`).join('');
  select.disabled=false;
  select.onchange=()=>{document.cookie='depradar_operator='+encodeURIComponent(select.value)+'; Path=/; SameSite=Lax';location.href='/';};
}
const rights=role=>({write:['OWNER','MAINTAINER'].includes(role),review:['OWNER','REVIEWER'].includes(role),admin:role==='OWNER'});
function breadcrumbs(html) {document.querySelector('#breadcrumbs').innerHTML=`<a href="/">工作台</a> <span>/</span> ${html}`;}
function context(project) {document.querySelector('#context-nav').innerHTML=`<div class="nav-caption">CURRENT PROJECT</div><a class="nav-item" href="/projects/${esc(project.id)}">◈　${esc(project.name)}</a>`;}
async function home() {
  const teams=await api('/teams');
  content.innerHTML=heading('RELEASE WORKSPACE','项目与团队','从一个应用、一份清单开始，建立可以审查的发布依据。')+
    `<div class="metrics"><div><span>团队空间</span><strong>${teams.length}</strong></div><div><span>默认报告有效期</span><strong>24 <small>小时</small></strong></div><div><span>发布依据</span><strong class="metric-word">证据 + 审查</strong></div></div>`+
    `<div class="split"><div>${teams.map(t=>panel(`${esc(t.name)} <small>${esc(t.role)}</small>`,`<div id="projects-${esc(t.id)}"></div>${rights(t.role).write?`<form id="project-${esc(t.id)}" class="inline"><input aria-label="应用名称" name="name" placeholder="应用名称，例如 payment-service" maxlength="120" required><button>创建项目</button></form>`:''}${rights(t.role).admin?`<details><summary>成员管理</summary><div id="members-${esc(t.id)}"></div><form id="member-${esc(t.id)}" class="inline"><input name="name" maxlength="190" placeholder="操作人姓名" required><select name="role" aria-label="角色"><option>MAINTAINER</option><option>REVIEWER</option><option>VIEWER</option><option>OWNER</option></select><button class="secondary">添加操作人或更改角色</button></form></details>`:''}`)).join('')}</div>
    <aside>${panel('创建团队',`<p class="muted">一个团队可管理多个应用。角色和数据在团队内生效。</p><form id="new-team"><label>团队名称<input name="name" maxlength="120" required></label><button>创建团队 →</button></form>`)}${panel('角色分工','<ul class="explain"><li><b>OWNER</b>管理、登记和审查</li><li><b>MAINTAINER</b>登记版本、扫描和申请例外</li><li><b>REVIEWER</b>人工审查、审批他人例外</li><li><b>VIEWER</b>查看依赖、报告和门禁</li></ul>')}</aside></div>`;
  form('new-team',async data=>{await api('/teams',{method:'POST',body:data});await home();toast('团队已创建');});
  await Promise.all(teams.map(async t=>{
    await paged('projects-'+t.id,`/teams/${t.id}/projects`,['应用项目','规则版本','登记时间'],p=>`<tr><td>${link('projects',p.id,p.name)}</td><td>v${p.currentPolicy}</td><td>${stamp(p.createdAt)}</td></tr>`);
    form('project-'+t.id,async data=>{const p=await api(`/teams/${t.id}/projects`,{method:'POST',body:data});location.href='/projects/'+p.id;});
    if(rights(t.role).admin){const members=await api(`/teams/${t.id}/members`);document.querySelector('#members-'+t.id).innerHTML=members.map(m=>`<p>${esc(m.name)} ${badge(m.role)}</p>`).join('');form('member-'+t.id,async data=>{await api(`/teams/${t.id}/members`,{method:'PUT',body:data});await refreshContext();await home();toast('成员角色已保存');});}
  }));
}
async function projectPage(id) {
  const [p,policies,releases]=await Promise.all([api('/projects/'+id),api(`/projects/${id}/policies`),api(`/projects/${id}/releases`)]);
  const role=await roleFor(p),can=rights(role),policy=policies[0];context(p);breadcrumbs(esc(p.name));document.title=p.name+' · DepRadar';
  content.innerHTML=heading('APPLICATION / PROJECT',p.name,'版本绑定不可变清单。依赖升级请登记新版本；同一份清单可重新扫描。',badge(role))+
    `<div class="metrics"><div><span>当前规则</span><strong>v${p.currentPolicy}</strong></div><div><span>报告有效期</span><strong>${policy.evidenceHours} <small>小时</small></strong></div><div><span>默认审查</span><strong class="metric-word">${policy.requireReview?'人工批准':'按风险规则'}</strong></div></div>`+
    panel('发布版本','<div id="release-list"></div>')+
    (can.write?panel('登记应用版本',`<form id="upload" class="inline"><label>版本标签<input name="tag" placeholder="例如 1.0.0" maxlength="120" required></label><label>CycloneDX JSON 1.6<input name="file" type="file" accept=".json,application/json" required></label><button>上传清单 →</button></form><p class="muted">Maven 组件 · 最大 10 MB · 相同内容返回原版本，同标签不同内容返回冲突。</p>`):'')+
    panel('版本风险对比',`<p class="muted">选择版本，再选择或粘贴扫描 ID。对比使用确定的历史报告。</p><form id="compare"><div class="columns">${['from','to'].map((key,i)=>`<label>${i?'目标':'基线'}版本<select id="${key}-release">${releases.items.map(r=>`<option value="${esc(r.id)}">${esc(r.tag)}</option>`).join('')}</select><input name="${key}ScanId" list="${key}-scans" placeholder="扫描 ID" required><datalist id="${key}-scans"></datalist></label>`).join('')}</div><button class="secondary">比较依赖与风险 ↔</button></form><div id="comparison"></div>`)+
    `<div class="columns">${panel('规则与历史',`<p>阻断 ${policy.blockedSeverities.split(',').map(badge).join(' ')} · 例外最长 ${policy.exceptionDays} 天</p><details><summary>查看规则版本</summary><pre>${esc(JSON.stringify(policies,null,2))}</pre></details>${can.admin?`<details><summary>创建新规则版本</summary><p class="muted">变更后旧批准失效，需要重新扫描和审查。</p><form id="policy"><label>阻断等级（逗号分隔）<input name="severities" value="${esc(policy.blockedSeverities)}" required></label><div class="columns"><label>证据小时<input name="hours" type="number" min="1" value="${policy.evidenceHours}" required></label><label>例外天数<input name="days" type="number" min="1" max="7" value="${policy.exceptionDays}" required></label></div><label class="check"><input name="review" type="checkbox" ${policy.requireReview?'checked':''}>要求人工批准</label><button>保存新规则 v${p.currentPolicy+1}</button></form></details>`:''}`)}${can.admin?panel('CI 接入',`<p class="muted">凭据仅作用于本项目。新建时显示一次，可随时吊销。</p><form id="credential" class="inline"><input name="name" placeholder="凭据名称" maxlength="120" required><button>创建凭据</button></form><div id="new-token"></div><div id="credentials"></div>`):''}</div>`+
    panel('审计记录','<div id="audit"></div>');
  await paged('release-list',`/projects/${id}/releases`,['版本','清单 SHA-256','登记时间'],r=>`<tr><td>${link('releases',r.id,r.tag)}</td><td>${hash(r.sbomHash)}</td><td>${stamp(r.createdAt)}</td></tr>`);
  form('upload',async(_,form)=>{const r=await api(`/projects/${id}/releases`,{method:'POST',body:new FormData(form),headers:{'Idempotency-Key':crypto.randomUUID()}});location.href='/releases/'+r.id;});
  form('policy',async data=>{await api(`/projects/${id}/policies`,{method:'POST',body:{expectedVersion:p.currentPolicy,blockedSeverities:data.severities.split(',').map(s=>s.trim()),requireReview:data.review==='on',evidenceHours:Number(data.hours),exceptionDays:Number(data.days)}});await projectPage(id);toast('新规则已保存；请重新扫描和审查');});
  for(const key of ['from','to']) {
    const select=document.querySelector('#'+key+'-release');
    const load=async()=>{if(!select.value)return;const scans=await api(`/releases/${select.value}/scans`);document.querySelector('#'+key+'-scans').innerHTML=scans.items.map(s=>`<option value="${esc(s.id)}">#${s.ordinal} · ${esc(s.status)} · ${stamp(s.createdAt)}</option>`).join('');document.querySelector(`[name=${key}ScanId]`).value=scans.items.find(s=>s.reportHash)?.id||'';};
    select.onchange=()=>load().catch(e=>toast(e.message,true));await load();
  }
  form('compare',async data=>{
    const result=await api(`/projects/${id}/compare?fromScanId=${encodeURIComponent(data.fromScanId)}&toScanId=${encodeURIComponent(data.toScanId)}`);
    document.querySelector('#comparison').innerHTML=`<div class="callout ${result.riskComparisonComplete?'good':'warning'}">${esc(result.explanation)}</div>`+['addedComponents','removedComponents','addedRisks','resolvedRisks','unresolvedRisks'].map((k,i)=>`<details><summary>${['新增组件','移除组件','新增风险','已不再检出的风险','未确定的旧风险'][i]} (${result[k].length})</summary><pre>${esc(JSON.stringify(result[k],null,2))}</pre></details>`).join('');
  });
  if(can.admin) {
    const credentials=await api(`/projects/${id}/ci-credentials`);
    document.querySelector('#credentials').innerHTML=credentials.map(c=>`<div class="credential"><span>${esc(c.name)} <small>${stamp(c.createdAt)}</small></span>${c.revokedAt?badge('REVOKED'):`<button class="subtle revoke" data-id="${esc(c.id)}">吊销</button>`}</div>`).join('');
    form('credential',async data=>{const c=await api(`/projects/${id}/ci-credentials`,{method:'POST',body:data});document.querySelector('#new-token').innerHTML=`<div class="callout warning">请立即保存，离开后无法再次查看。<code class="secret">${esc(c.token)}</code></div>`;toast('CI 凭据已创建');});
    action('.revoke',async el=>{await api(`/projects/${id}/ci-credentials/${el.dataset.id}`,{method:'DELETE'});await projectPage(id);toast('凭据已吊销');});
  }
  await paged('audit',`/projects/${id}/audit`,['操作','资源','时间','详情'],a=>`<tr><td>${esc(a.action)}</td><td><code>${esc(a.resourceId)}</code></td><td>${stamp(a.createdAt)}</td><td><details><summary>查看</summary><pre>${esc(a.detailJson)}</pre></details></td></tr>`);
}
async function releasePage(id) {
  const r=await api('/releases/'+id),p=await api('/projects/'+r.projectId),can=rights(await roleFor(p));context(p);breadcrumbs(`${link('projects',p.id,p.name)} <span>/</span> ${esc(r.tag)}`);document.title=r.tag+' · DepRadar';
  content.innerHTML=heading('IMMUTABLE APPLICATION RELEASE',r.tag,p.name+' · 登记于 '+stamp(r.createdAt),can.write?'<button id="scan-now">启动新扫描 →</button>':'')+
    panel('清单绑定',`<dl><dt>版本 ID</dt><dd><code>${esc(r.id)}</code></dd><dt>SHA-256</dt><dd>${hash(r.sbomHash)}</dd><dt>根组件</dt><dd><code>${esc(r.rootRef)}</code></dd></dl><a class="text-link" href="/api/v1/releases/${esc(id)}/sbom">下载原始清单 ↓</a>`)+
    panel('扫描记录','<p class="muted">扫描完成与是否发现风险分别表达。最新扫描决定当前门禁绑定。</p><div id="scan-list"></div>')+
    panel('依赖组件','<div id="components"></div><details><summary>查看依赖图</summary><button id="load-graph" class="secondary">读取依赖关系</button><pre id="graph"></pre></details>');
  const scanKey=crypto.randomUUID();
  action('#scan-now',async()=>{const scan=await api(`/releases/${id}/scans`,{method:'POST',headers:{'Idempotency-Key':scanKey}});location.href='/scans/'+scan.id;});
  await paged('scan-list',`/releases/${id}/scans`,['扫描','状态','规则','创建时间','扫描 ID'],s=>`<tr><td>${link('scans',s.id,'扫描 #'+s.ordinal)}</td><td>${badge(s.status)}</td><td>v${s.policyVersion}</td><td>${stamp(s.createdAt)}</td><td><code>${esc(s.id)}</code></td></tr>`);
  await paged('components',`/releases/${id}/components`,['Maven 坐标','版本','PURL'],c=>`<tr><td><b>${esc(c.groupName)}:${esc(c.name)}</b><small>${esc(c.bomRef)}</small></td><td>${esc(c.version)}</td><td><code>${esc(c.purl)}</code></td></tr>`);
  action('#load-graph',async()=>{document.querySelector('#graph').textContent=JSON.stringify(await api(`/releases/${id}/dependencies`),null,2);});
}
function gateHtml(gate) {return `<div class="gate ${esc(gate.result)}"><div>${badge(gate.result)}<span>当前发布门禁</span></div><ul>${gate.reasons.map(r=>`<li>${esc(r)}</li>`).join('')}</ul><small>评估于 ${stamp(gate.evaluatedAt)} · 有效截止 ${stamp(gate.validUntil)}</small></div>`;}
async function scanPage(id) {
  clearTimeout(scanPage.timer);
  const progress=await api('/scans/'+id),s=progress.scan,r=await api('/releases/'+s.releaseId),p=await api('/projects/'+r.projectId),can=rights(await roleFor(p));
  context(p);breadcrumbs(`${link('projects',p.id,p.name)} <span>/</span> ${link('releases',r.id,r.tag)} <span>/</span> 扫描 #${s.ordinal}`);document.title='扫描 #'+s.ordinal+' · DepRadar';
  const running=['QUEUED','RUNNING'].includes(s.status),batches=progress.batches,done=batches.filter(b=>['SUCCEEDED','FAILED','CANCELLED'].includes(b.status)).length;
  content.innerHTML=heading('SCAN / EVIDENCE / REVIEW','扫描 #'+s.ordinal,p.name+' / '+r.tag,running&&can.write?'<button class="secondary" id="cancel">取消扫描</button>':badge(s.status))+
    panel('任务进度',`<div class="progress-line"><span id="scan-status">${badge(s.status)}</span><strong id="progress-label">${done} / ${batches.length} 批次完成</strong></div><progress id="progress-bar" max="${batches.length}" value="${done}"></progress><div id="batch-errors">${batches.filter(b=>b.error).map(b=>`<p class="error">批次 ${b.ordinal+1} · 尝试 ${b.attempts} 次 · ${esc(b.error)}</p>`).join('')}</div><details><summary>报告绑定</summary><dl><dt>扫描 ID</dt><dd><code>${esc(s.id)}</code></dd><dt>SBOM hash</dt><dd>${hash(r.sbomHash)}</dd><dt>报告 hash</dt><dd>${hash(s.reportHash)}</dd><dt>规则版本</dt><dd>v${s.policyVersion}</dd></dl></details>`)+
    '<div id="gate"></div>'+
    panel('风险与引入路径','<p class="muted">严重级别缺失时为 UNKNOWN。路径展示清单声明的一条最短路径。</p><div id="findings"></div>')+
    panel('来源证据','<div id="evidence"></div>')+
    (s.reportHash?`<div class="columns">${panel('人工审查','<div id="review"></div>')}${panel('限期例外','<div id="exceptions"></div>')}</div>`:'');
  action('#cancel',async()=>{await api('/scans/'+id+'/cancel',{method:'POST'});await scanPage(id);toast('任务已取消');});
  try {document.querySelector('#gate').innerHTML=gateHtml(await api(`/releases/${r.id}/gate?scanId=${id}&sbomHash=${r.sbomHash}`));}catch(e){document.querySelector('#gate').innerHTML=`<div class="callout warning">门禁无法评估：${esc(e.message)}</div>`;}
  await paged('findings',`/scans/${id}/findings`,['风险','等级','依赖路径与证据'],f=>`<tr><td><a href="${esc(f.sourceUrl)}" target="_blank" rel="noreferrer">${esc(f.vulnerabilityId)} ↗</a><small>${esc(f.summary)}</small>${can.write&&s.reportHash?`<button class="subtle request-exception" data-finding="${esc(f.id)}">申请例外</button>`:''}</td><td>${badge(f.severity)}</td><td><code class="path">${esc(JSON.parse(f.pathJson).join(' → ')||'清单未声明引入路径')}</code><details><summary>公告证据与风险 ID</summary><code>${esc(f.id)}</code><pre>${esc(JSON.stringify(JSON.parse(f.evidenceJson),null,2))}</pre></details></td></tr>`);
  const report=await api('/scans/'+id+'/report');
  document.querySelector('#evidence').innerHTML=report.results.length?report.results.map(result=>`<details><summary>${badge(result.status)} ${esc(result.source)} · ${esc(result.componentId)} · ${stamp(result.queriedAt)}</summary>${result.error?`<p class="error">${esc(result.error)}</p>`:''}<pre>${esc(JSON.stringify(JSON.parse(result.evidenceJson),null,2))}</pre></details>`).join(''):'<p class="muted">任务尚未保存来源结果。</p>';
  if(s.reportHash) {
    const [review,exceptions]=await Promise.all([api('/scans/'+id+'/review'),api('/scans/'+id+'/exceptions')]);
    document.querySelector('#review').innerHTML=`<p>${badge(review.decision)} <small>并发版本 ${review.version}</small></p>${review.reason?`<p>${esc(review.reason)}</p><small>${stamp(review.decidedAt)}</small>`:''}${can.review?`<form id="review-form"><label>审查决定<select name="decision"><option value="APPROVED">批准该报告</option><option value="REJECTED">拒绝发布</option></select></label><label>审查理由<textarea name="reason" maxlength="4000" required></textarea></label><button>提交审查决定</button></form>`:'<p class="muted">由 OWNER 或 REVIEWER 完成人工审查。</p>'}`;
    form('review-form',async data=>{await api('/scans/'+id+'/review',{method:'PUT',body:{...data,expectedVersion:review.version,sbomHash:r.sbomHash,reportHash:s.reportHash,policyVersion:s.policyVersion}});await scanPage(id);toast('审查决定已保存');});
    document.querySelector('#exceptions').innerHTML=`<p class="muted">例外仅覆盖当前报告的指定风险，申请者不能自行批准。</p>${exceptions.map(e=>`<article class="exception"><p>${badge(e.decision)} <small>截止 ${stamp(e.expiresAt)}</small></p><code>${esc(e.findingId)}</code><p>${esc(e.reason)}</p>${e.decisionReason?`<p>审批理由：${esc(e.decisionReason)}</p>`:''}${can.review&&e.decision==='PENDING'?`<form id="exception-${esc(e.id)}"><input name="reason" placeholder="审批理由" maxlength="4000" required><select name="decision"><option value="APPROVED">批准例外</option><option value="REJECTED">拒绝例外</option></select><button class="secondary">提交决定</button></form>`:''}</article>`).join('')}${can.write?`<form id="request-form"><label>风险 ID<input name="findingId" required placeholder="点击风险条目的“申请例外”填入"></label><label>有效截止（本地时间）<input type="datetime-local" name="expiresAt" required></label><label>业务理由<textarea name="reason" maxlength="4000" required></textarea></label><button class="secondary">提交例外申请</button></form>`:''}`;
    form('request-form',async data=>{await api('/scans/'+id+'/exceptions',{method:'POST',body:{...data,expiresAt:new Date(data.expiresAt).toISOString()}});await scanPage(id);toast('例外已提交，等待他人批准');});
    for(const e of exceptions)form('exception-'+e.id,async data=>{await api('/exceptions/'+e.id,{method:'PUT',body:{...data,expectedVersion:e.version}});await scanPage(id);toast('例外决定已保存');});
    // Delegation also covers findings appended by cursor pagination.
    document.querySelector('#findings').addEventListener('click',event=>{const el=event.target.closest('.request-exception');if(!el)return;const field=document.querySelector('[name=findingId]');field.value=el.dataset.finding;field.scrollIntoView({behavior:'smooth',block:'center'});field.focus();});
  }
  if(running)scanPage.timer=setTimeout(()=>pollScan(id),3000);
}
async function pollScan(id) {
  try {
    const {scan,batches}=await api('/scans/'+id);
    if(!['QUEUED','RUNNING'].includes(scan.status)){await scanPage(id);return;}
    const count=batches.filter(b=>['SUCCEEDED','FAILED','CANCELLED'].includes(b.status)).length;
    document.querySelector('#scan-status').innerHTML=badge(scan.status);document.querySelector('#progress-label').textContent=`${count} / ${batches.length} 批次完成`;document.querySelector('#progress-bar').value=count;
    document.querySelector('#batch-errors').innerHTML=batches.filter(b=>b.error).map(b=>`<p class="error">批次 ${b.ordinal+1} · 尝试 ${b.attempts} 次 · ${esc(b.error)}</p>`).join('');
    scanPage.timer=setTimeout(()=>pollScan(id),3000);
  }catch(e){toast('进度查询失败：'+e.message,true);document.querySelector('#progress-label').textContent='进度查询失败，请刷新重试';}
}
const routes={home,project:projectPage,release:releasePage,scan:scanPage};
refreshContext().then(()=>routes[document.body.dataset.view](document.body.dataset.id)).catch(error=>{content.innerHTML=`<div class="callout warning"><h1>无法读取工作空间</h1><p>${esc(error.message)}</p><a href="${esc(location.pathname)}">重新加载 ↻</a></div>`;});
