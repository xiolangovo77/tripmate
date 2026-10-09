/* TripMate: Offline multi-page interactive prototype. No network requests or real AI. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const page = document.body.dataset.page;
  const paths = ['index.html', 'trip.html', 'itinerary.html', 'my-trips.html'];
  const maxMembers = 8;
  const styleNames = {relaxed:'🌿 悠闲不赶路', balanced:'📍 经典体验', budget:'💰 省钱慢游', food:'🍜 美食优先', photo:'📸 拍照探索'};
  const paceNames = {relaxed:'轻松一点', balanced:'适中节奏', packed:'充实一点'};
  const interestNames = ['美食','自然','拍照','文化','购物'];
  const sampleFriends = [
    {name:'小王', budget:450, pace:'relaxed', interests:['美食','自然'], icon:'🧢'},
    {name:'小张', budget:380, pace:'relaxed', interests:['文化','美食'], icon:'🌼'},
    {name:'小陈', budget:650, pace:'balanced', interests:['拍照','购物'], icon:'😎'},
    {name:'阿雅', budget:580, pace:'packed', interests:['自然','拍照'], icon:'🎒'},
  ];
  let state = null;
  let toastTimer = null;

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (v) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[v]));
  }
  function pickInt(value, low, high, fallback) {
    const n = Number(value);
    return Number.isFinite(n) && n >= low && n <= high ? Math.round(n) : fallback;
  }
  function loadProfile() {
    try {
      const profile=JSON.parse(localStorage.getItem('tripmate_demo_profile')||'null');
      if(profile && typeof profile==='object')return {
        pace:Object.hasOwn(paceNames,profile.pace)?profile.pace:'balanced',
        interests:Array.isArray(profile.interests)?[...new Set(profile.interests.filter(x=>interestNames.includes(x)))]:[]
      };
    } catch(e) {}
    return {pace:'balanced',interests:[]};
  }
  function newState(city='杭州', days=2, budget=600) {
    const profile=loadProfile();
    return {city:city.slice(0,32),days,budget,createdAt:Date.now(),
      members:[{id:1,name:'我（发起人）',budget,pace:profile.pace,interests:[...profile.interests],icon:'🌿'}],
      style:'relaxed', day:0, status:'planning', chat:[]};
  }
  function normalize(s) {
    if (!s || typeof s !== 'object' || !Array.isArray(s.members)) return null;
    const clean = newState(String(s.city || '杭州'),pickInt(s.days,1,4,2),pickInt(s.budget,100,10000,600));
    clean.members = s.members.slice(0,maxMembers).map((m,i) => ({
      id:i+1,name:String(m.name || '朋友').slice(0,16),budget:pickInt(m.budget,100,10000,600),
      pace:['relaxed','balanced','packed'].includes(m.pace)?m.pace:'balanced',
      interests:Array.isArray(m.interests)?m.interests.filter(x=>['美食','自然','拍照','文化','购物'].includes(x)).slice(0,5):[],
      icon:String(m.icon || '🙂').slice(0,4)
    }));
    if (clean.members.length===0) clean.members=newState().members;
    clean.style=Object.hasOwn(styleNames,s.style)?s.style:'relaxed';
    clean.day=pickInt(s.day,0,clean.days-1,0);
    clean.status=s.status==='confirmed'?'confirmed':'planning';
    clean.chat=Array.isArray(s.chat)?s.chat.slice(-12).map(m=>({role:m.role==='user'?'user':'assistant',text:String(m.text||'').slice(0,260)})):[];
    return clean;
  }
  function loadState() {
    if (location.hash.startsWith('#data=')) {
      try { const value=normalize(JSON.parse(decodeURIComponent(location.hash.slice(6)))); if(value) return value; } catch(e) {}
    }
    try {const v=normalize(JSON.parse(localStorage.getItem('tripmate_demo_active')||'null'));if(v)return v;}catch(e){}
    return null;
  }
  function serialize() {return encodeURIComponent(JSON.stringify(state));}
  function persist() {
    if(!state)return;
    try { localStorage.setItem('tripmate_demo_active',JSON.stringify(state)); } catch(e) {}
    try { history.replaceState(null,'',location.pathname+location.search+'#data='+serialize()); } catch(e) {}
  }
  function goto(path, fresh=false) {
    if(!paths.includes(path))return;
    if (fresh) {try{localStorage.removeItem('tripmate_demo_active');}catch(e){}location.href=path;return;}
    if(state) persist();
    location.href=path+(state?'#data='+serialize():'');
  }
  function showToast(message) {
    const el=$('toast');if(!el)return;
    el.textContent=message;el.classList.add('show');
    clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),3200);
  }
  async function copyText(value) {
    try {if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(value);showToast('已复制到剪贴板');return;}} catch(e) {}
    const area=document.createElement('textarea');area.value=value;area.style.position='fixed';area.style.opacity='0';document.body.append(area);area.select();
    let ok=false;try{ok=document.execCommand('copy');}catch(e){}area.remove();
    if(ok)showToast('已复制到剪贴板');else window.prompt('请手动复制以下内容：',value);
  }
  function addMember(member) {
    if(state.members.length>=maxMembers){showToast('演示房间最多支持8人');return false;}
    const name=member.name.trim();
    if(state.members.some(m=>m.name===name)){showToast('这个昵称已经在房间里了');return false;}
    state.members.push({...member,id:Math.max(...state.members.map(m=>m.id),0)+1});state.status='planning';persist();return true;
  }
  function summary() {
    const cap=Math.min(state.budget,...state.members.map(m=>m.budget));
    const counts={};state.members.forEach(m=>m.interests.forEach(i=>counts[i]=(counts[i]||0)+1));
    const ranked=Object.entries(counts).sort((a,b)=>b[1]-a[1]).slice(0,2).map(x=>x[0]);
    const relaxed=state.members.filter(m=>m.pace==='relaxed').length;
    const gap=Math.max(...state.members.map(m=>m.budget))-Math.min(...state.members.map(m=>m.budget));
    return {cap,ranked,relaxed,gap};
  }
  function recommendStyle() {
    const x=summary();
    if(x.relaxed>=Math.ceil(state.members.length/2))return 'relaxed';
    if(x.cap<450)return 'budget';
    const preferred=x.ranked[0];
    if(preferred==='美食')return 'food';
    if(preferred==='拍照')return 'photo';
    return 'balanced';
  }
  function showMembers() {
    const box=$('members');if(!box)return;
    box.innerHTML=state.members.map((m,i)=>`<div class="member-card"><div class="member-icon">${escapeHtml(m.icon)}</div><div class="grow"><strong>${escapeHtml(m.name)}</strong><p>偏好：${escapeHtml(m.interests.join(' / ') || '暂未选择')} · ${escapeHtml(paceNames[m.pace])}</p></div><span class="member-badge">¥${m.budget}</span></div>`).join('');
    $('member-count').textContent=`${state.members.length} 人已加入`;
    const x=summary();
    $('room-insights').innerHTML=`<strong>✦ AI 先帮你整理一下</strong><br>已有 <b>${state.members.length}</b> 人分享想法。建议按最低预算 <b>¥${x.cap}/人</b> 规划${x.gap?'，成员预算上限相差 ¥'+x.gap:''}。${x.ranked.length?'目前偏好集中在「'+escapeHtml(x.ranked.join('、'))+'」。':''}${x.relaxed?' '+x.relaxed+' 位伙伴想要轻松一点的节奏。':''}`;
  }
  function renderTrip() {
    $('room-title').textContent=`${state.city} · ${state.days}天${state.days>1?state.days-1+'晚':''}`;
    $('room-meta').textContent=`发起预算上限 ¥${state.budget}/人`;
    showMembers();
    $('simulate-add').addEventListener('click',()=>{
      let sample=sampleFriends.find(x=>!state.members.some(m=>m.name===x.name));
      if(!sample) sample={name:'朋友'+(state.members.length+1),budget:500,pace:'balanced',interests:['美食'],icon:'🙂'};
      if(addMember({...sample,interests:[...sample.interests]})){showMembers();showToast(`${sample.name} 已模拟加入旅行房间`);}
    });
    $('generate-itinerary').addEventListener('click',()=>{
      if(state.members.length<2){showToast('建议先添加至少一位伙伴，再体验多人偏好协调');return;}
      state.style=recommendStyle();
      state.status='planning';persist();goto('itinerary.html');
    });
    const modal=$('invite-modal');
    $('copy-invite').addEventListener('click',()=>{
      $('invitation-address').textContent=location.href;
      modal.classList.add('open');
    });
    $('close-invite').addEventListener('click',()=>modal.classList.remove('open'));
    modal.addEventListener('click',e=>{if(e.target===modal)modal.classList.remove('open');});
    $('copy-invite-link').addEventListener('click',()=>copyText(location.href));
  }
  function cityPlaces(){
    const cities={
      '杭州':{scenic:'西湖湖滨慢行',street:'河坊街随意逛逛',culture:'西湖周边的历史街区',photo:'湖边日落拍照'},
      '苏州':{scenic:'古城河畔慢行',street:'平江路闲逛',culture:'古城园林街区',photo:'小桥流水拍照'},
      '南京':{scenic:'玄武湖边散步',street:'老门东慢慢逛',culture:'明城墙周边探索',photo:'梧桐街道拍照'},
      '上海':{scenic:'城市滨水步道散步',street:'特色街区随意逛',culture:'城市历史建筑探索',photo:'城市街景拍照'},
    };
    return cities[state.city]||{scenic:'城市公园悠闲漫步',street:'老街与小店探索',culture:'当地人文街区',photo:'特色街景拍照'};
  }
  function planEvents(dayIndex){
    const p=cityPlaces();const s=state.style;const last=dayIndex===state.days-1;
    const starting=dayIndex===0, isOne=state.days===1;
    let data;
    if(starting){data=[
      {time:'10:30',name:'抵达目的地 · 从容开始',desc:'先确认交通和行李安排',cost:0},
      {time:'12:00',name:s==='food'?'特色美食第一站':'吃顿在地午餐',desc:'照顾大家口味和饮食偏好',cost:s==='budget'?35:55},
      {time:'14:00',name:s==='photo'?p.photo:s==='balanced'?p.culture:p.scenic,desc:'轻松步行，适时休息',cost:0},
      {time:'17:30',name:s==='budget'?'便利小食与自由时间':s==='food'?'继续寻味当地小吃':'小店喝茶与自由探索',desc:'可自行选择，不强制消费',cost:s==='budget'?18:36},
      {time:'19:00',name:'晚上一起吃饭',desc:'留时间聊天、分享当天发现',cost:s==='budget'?40:65}
    ];}
    else if(last){data=[
      {time:'09:30',name:'睡饱了再出发',desc:'慢慢吃早餐，收拾行李',cost:30},
      {time:'11:00',name:s==='photo'?p.photo:s==='balanced'?p.culture:p.street,desc:'最后一段城市漫游',cost:0},
      {time:'12:30',name:'午餐与伴手礼时间',desc:'按各自预算自由选择',cost:s==='budget'?35:55},
      {time:'15:00',name:'准备返程',desc:'预留前往车站的充足时间',cost:0}
    ];}
    else{data=[
      {time:'09:30',name:'早餐和当天计划确认',desc:'大家一起确认今天重点',cost:28},
      {time:'11:00',name:s==='photo'?p.photo:s==='balanced'?p.culture:p.scenic,desc:'保留拍照和休息的空间',cost:0},
      {time:'13:00',name:'午餐与自由活动',desc:'可以按照兴趣小组行动',cost:s==='budget'?40:65},
      {time:'16:00',name:p.street,desc:'弹性安排，可提前休息',cost:0},
      {time:'19:00',name:'晚餐和当天回顾',desc:'分享今天最喜欢的地方',cost:s==='budget'?40:60}
    ];}
    if(s==='food'){data=data.map(e=>e.cost?{...e,cost:Math.round(e.cost*1.25),desc:e.desc+' · 优先考虑餐饮体验'}:e)}
    if(s==='relaxed'){data=data.filter(e=>e.time!=='17:30' || !starting)}
    if(s==='photo'&&starting){data=data.map(e=>e.time==='17:30'?{...e,name:p.photo,desc:'预留黄金时段拍照，按需消费'}:e)}
    if(isOne){data=data.filter(e=>e.time!=='19:00').concat([{time:'18:00',name:'结束今天的探索',desc:'按需返程，别把行程排太满',cost:0}]);}
    return data;
  }
  function estimate(){let n=0;for(let d=0;d<state.days;d++)for(const e of planEvents(d))n+=e.cost;return n;}
  function renderPlan(){
    $('plan-city').textContent=`${state.city} ${state.days}日漫游`;const x=summary();
    $('plan-meta').textContent=`${state.days}天 · ${state.members.length}位伙伴 · 共同预算参考 ¥${x.cap}/人`;
    $('recommended-style').textContent=styleNames[state.style];
    $('preference-summary').innerHTML=`<strong>✦ AI 协调摘要</strong><br>成员预算上限取最保守的 <b>¥${x.cap}/人</b>。${x.ranked.length?'大家比较偏爱'+escapeHtml(x.ranked.join('、'))+'。':''}${x.relaxed?' 有'+x.relaxed+'位伙伴想玩得轻松一点。':''} 这份行程是可调整的草案，景点时间与价格尚未实时核验。`;
    $('day-tabs').innerHTML=Array.from({length:state.days},(_,i)=>`<button type="button" class="${state.day===i?'active':''}" data-day="${i}">第 ${i+1} 天</button>`).join('');
    $('day-tabs').querySelectorAll('[data-day]').forEach(el=>el.addEventListener('click',()=>{state.day=+el.dataset.day;persist();renderPlan();}));
    $('events').innerHTML=planEvents(state.day).map(e=>`<div class="event"><span class="event-dot"></span><div class="time">${escapeHtml(e.time)}</div><div class="event-content"><div class="row between" style="align-items:start"><strong>${escapeHtml(e.name)}</strong><span class="cost">${e.cost?'约¥'+e.cost:'免费/待定'}</span></div><p>${escapeHtml(e.desc)}</p></div></div>`).join('');
    const amount=estimate();$('estimate-cost').textContent='¥ '+amount;
    $('budget-status').textContent=amount>x.cap?'⚠ 仅活动餐饮已超预算':'✓ 餐饮活动未超参考上限';
    $('budget-status').className='badge'+(amount>x.cap?' orange':'');
    $('plan-members').innerHTML=state.members.map(m=>`<div class="row" style="font-size:13px"><span class="member-icon" style="width:32px;height:32px;border-radius:11px;font-size:16px">${escapeHtml(m.icon)}</span><span class="grow">${escapeHtml(m.name)}</span><span class="muted tiny">¥${m.budget}</span></div>`).join('');
    const confirmed=state.status==='confirmed';$('confirmed-note').style.display=confirmed?'block':'none';
    $('confirm-trip').textContent=confirmed?'✓ 已确认行程':'✓ 确认这份行程';
    $('confirm-trip').disabled=confirmed;
  }
  function addChat(role,text){state.chat.push({role,text});state.chat=state.chat.slice(-12);persist();renderChat();}
  function renderChat(){
    const log=$('chat-log');if(!log)return;
    log.innerHTML=`<div class="chat assistant">大家的偏好我看到了！这是一份示例行程。你可以告诉我“省点钱”“多点美食”或“安排轻松一点”。</div>`+state.chat.map(m=>`<div class="chat ${m.role}">${escapeHtml(m.text)}</div>`).join('');
    log.scrollTop=log.scrollHeight;
  }
  function handleChat(msg){
    if(!msg.trim())return;
    addChat('user',msg.slice(0,180));
    let next=null,text='';
    if(/省|便宜|预算|省钱|节省/.test(msg)){next='budget';text='收到，我把方案切换成「省钱慢游」，压缩活动餐饮预估，优先安排免费或低消费体验。';}
    else if(/轻松|慢|累|休息|不赶/.test(msg)){next='relaxed';text='好，我们慢一点。这份行程会减少需要赶场的安排，留更多自由时间。';}
    else if(/吃|美食|餐|小吃/.test(msg)){next='food';text='已调整为「美食优先」。我把餐饮体验放在更重要的位置，同时提醒你注意总体预算。';}
    else if(/拍照|摄影|照片|打卡/.test(msg)){next='photo';text='安排上！已经切换到「拍照探索」，尽量给风景和街拍留出更多时间。';}
    else if(/经典|充实|多玩|景点/.test(msg)){next='balanced';text='已调整为「经典体验」，兼顾特色景点和留白时间。';}
    else {text='目前是离线 Agent 演示，我可以响应“省点钱、轻松一点、多点美食、多拍拍照、经典体验”等指令。真实大模型接入后可以理解更自由的要求。';}
    if(next){state.style=next;state.status='planning';persist();renderPlan();}
    addChat('assistant',text);
  }
  function renderItinerary(){
    if(state.members.length===1){state.members.push({...sampleFriends[0],id:2},{...sampleFriends[1],id:3});persist();}
    renderPlan();renderChat();
    $('chat-form').addEventListener('submit',e=>{e.preventDefault();const input=$('chat-input');const message=input.value.trim();if(!message)return;input.value='';handleChat(message);});
    document.querySelectorAll('[data-suggest]').forEach(el=>el.addEventListener('click',()=>handleChat(el.dataset.suggest)));
    $('confirm-trip').addEventListener('click',()=>{state.status='confirmed';persist();renderPlan();showToast('行程已确认，保存在当前演示项目里');});
    $('copy-itinerary').addEventListener('click',()=>{
      const parts=[`TripMate · ${state.city} ${state.days}日行程`,`风格：${styleNames[state.style]}`,`同行：${state.members.map(m=>m.name).join('、')}`,`餐饮活动示例合计：约¥${estimate()}/人（不含车费住宿）`];
      for(let d=0;d<state.days;d++)parts.push(`\n第${d+1}天`,...planEvents(d).map(e=>`${e.time} ${e.name}`));
      parts.push('\n仅为未核实的规划示例，出行前请自行核实开放时间、交通与实际价格。');copyText(parts.join('\n'));
    });
  }
  function renderMine(){
    const profile=loadProfile();
    $('profile-pace').value=profile.pace;
    document.querySelectorAll('#profile-interests input').forEach(el=>{el.checked=profile.interests.includes(el.value);});
    $('profile-form').addEventListener('submit',e=>{
      e.preventDefault();
      $('profile-error').textContent='';
      const pace=$('profile-pace').value;
      if(!Object.hasOwn(paceNames,pace)){$('profile-error').textContent='请选择有效的旅行节奏';return;}
      const interests=[...document.querySelectorAll('#profile-interests input:checked')].map(el=>el.value).filter(x=>interestNames.includes(x));
      try {localStorage.setItem('tripmate_demo_profile',JSON.stringify({pace,interests}));}
      catch(e){$('profile-error').textContent='暂时无法保存，请允许浏览器存储后重试';return;}
      showToast('个人偏好已保存，下次新建旅行时生效');
    });
    if(!state){
      $('mine-content').innerHTML='<div class="card card-pad"><p class="muted">还没有旅行，选个目的地开始吧。</p><button class="btn" type="button" data-action="new-trip">＋ 新建旅行</button></div>';
      return;
    }
    $('mine-content').innerHTML=`<div class="card card-pad" style="max-width:760px"><div class="row between" style="align-items:flex-start;flex-wrap:wrap"><div><span class="badge">${state.status==='confirmed'?'✓ 已确认':'✦ 正在策划'}</span><h2 class="h2" style="margin:12px 0 6px">${escapeHtml(state.city)} · ${state.days}日同行计划</h2><p class="muted" style="margin:0">${state.members.length}位伙伴已加入 · 预算上限 ¥${summary().cap}/人</p></div><span style="font-size:48px">🧳</span></div><div class="divider"></div><div class="row" style="flex-wrap:wrap"><button class="btn" type="button" data-route="itinerary.html">查看 AI 行程 ↗</button><button class="btn btn-line" type="button" data-route="trip.html">编辑旅行房间</button><button class="btn btn-soft" type="button" data-action="new-trip">＋ 新的旅行</button></div><div class="notice" style="margin-top:22px">演示版本主要体验一趟旅行，所有操作留在当前浏览器。本地 HTML 没有真实账号、远程分享或订单数据。</div></div>`;
  }
  function createFromHome(){
    const city=$('quick-city').value.trim();const days=+$('quick-days').value;
    const budgetText=$('quick-budget').value.trim();const budget=Number(budgetText);
    if(!city || city.length>32){$('home-error').textContent='请输入1～32个字的目的地';$('quick-city').focus();return;}
    if(!Number.isInteger(days)||days<1||days>4){$('home-error').textContent='请选择旅行天数（1～4天）';$('quick-days').focus();return;}
    if(!budgetText){$('home-error').textContent='请填写每人预算';$('quick-budget').focus();return;}
    if(!Number.isFinite(budget)||budget<100||budget>10000){$('home-error').textContent='每人预算请输入100～10000元';$('quick-budget').focus();return;}
    $('home-error').textContent='';
    state=newState(city,days,Math.round(budget));
    state.style=recommendStyle();
    persist();goto('trip.html');
  }
  function renderHome(){
    $('quick-create').addEventListener('click',createFromHome);
    document.querySelectorAll('[data-preset]').forEach(el=>el.addEventListener('click',()=>{$('quick-city').value=el.dataset.preset;showToast('已选择 '+el.dataset.preset);$('quick-city').focus();}));
    ['quick-city','quick-budget'].forEach(id=>$(id).addEventListener('keydown',e=>{if(e.key==='Enter')createFromHome();}));
  }
  function init(){
    state=loadState();
    if(!state && (page==='trip'||page==='plan')) state=newState();
    if(state && page!=='home')persist();
    document.addEventListener('click',e=>{
      const route=e.target.closest('[data-route]');if(route){e.preventDefault();goto(route.dataset.route);return;}
      const action=e.target.closest('[data-action="new-trip"]');if(action){goto('index.html',true);}
    });
    if(page==='home')renderHome();
    if(page==='trip')renderTrip();
    if(page==='plan')renderItinerary();
    if(page==='mine')renderMine();
  }
  init();
})();
