const state = {
  token: "",
  status: null,
  view: "chars",
  selected: [],
  operatorId: null,
  characters: [],
  charQuery: "",
  charSort: "-exp",
  charOnline: ["1", "0"],
  charType: "",
  charPage: 0,
  charLimit: 100,
  pickedChar: null,
  gotoQuery: "",
  mapTab: "pick",
  items: [],
  itemTotal: 0,
  itemPage: 0,
  itemLimit: 100,
  itemCategory: "all",
  itemQuery: "",
  pickedItem: null,
  creatingItem: false,
  itemMeta: null,
  itemPageSwap: false,
  npcs: [],
  npcTotal: 0,
  npcPage: 0,
  npcLimit: 100,
  npcQuery: "",
  pickedNpc: null,
  creatingNpc: false,
  npcMeta: null,
  npcPageSwap: false,
  npcKind: "npc",
  mobs: [],
  mobTotal: 0,
  mobPage: 0,
  mobQuery: "",
  pickedMob: null,
  creatingMob: false,
  mobPageSwap: false,
  shop: [],
  shopPick: false,
  map: null,
  mapId: 4,
  mapImg: null,
  mapCatalog: [],
  mapCatalogAll: [],
  mapGroup: "全部",
  mapOpenGroups: new Set(["亚丁大陆"]),
  mapQuery: "",
  mapClick: null,
  mapView: {scale: 2.2, panX: 0, panY: 0},
  mapDrag: null,
  mapPanAnim: 0,
  mapLoadGen: 0,
  mapRefreshing: false,
  pending: [],
  pendingIds: null,
  drawer: false,
  codexOpen: true,
  inspectors: {chars: false, items: false, map: true, npcs: false, mobs: false},
  charRefreshAt: 0
};

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const cats = {weapon: "武器", armor: "防具", etc: "道具"};
const typeZh = {
  sword: "剑", dagger: "匕首", tohandsword: "双手剑", bow: "弓", singlebow: "单手弓",
  spear: "矛", singlespear: "单手矛", staff: "魔杖", tohandstaff: "双手魔杖",
  blunt: "钝器", tohandblunt: "双手钝器", claw: "钢爪", edoryu: "双刀",
  gauntlet: "铁手甲", chainsword: "锁链剑", kiringku: "奇古兽",
  helm: "头盔", armor: "盔甲", T: "T恤", cloak: "斗篷", glove: "手套",
  boots: "靴子", shield: "盾", amulet: "项链", ring: "戒指", belt: "腰带",
  earring: "耳环", guarder: "臂甲", aidr: "饰品", aidm: "饰品",
  potion: "药水", scroll: "卷轴", food: "食物", other: "杂项",
  questitem: "任务", spellbook: "魔法书", petitem: "宠物道具",
  arrow: "箭", sting: "飞刀", wand: "魔杖", light: "照明",
  material: "材料", gem: "宝石", event: "活动", firecracker: "烟花",
  totem: "图腾", treasure_box: "宝箱", none: "无"
};
const implZh = {
  L1Monster: "怪物", L1MonsterGuard: "怪物警卫", L1Merchant: "商人", L1Teleporter: "传送师",
  L1Dwarf: "仓库", L1Guard: "警卫", L1Guardian: "守护者", L1Housekeeper: "小屋管家",
  L1Door: "门", L1Furniture: "家具", L1FieldObject: "场景物", L1Effect: "特效",
  L1Doll: "魔法娃娃", L1Signboard: "告示牌", L1Scarecrow: "稻草人", L1Tower: "攻城塔",
  L1Crown: "攻城旗帜", L1Bow: "弓手NPC", L1Gam: "赌场", L1Gambling: "赌场",
  L1Quest: "任务NPC", L1Illusory: "幻象", L1Npc: "通用NPC", L1Cn: "国服定制"
};
function typeName(v) { return typeZh[v] || v; }
function implName(v) { return implZh[v] || String(v || "").replace(/^L1/, "") || v; }
function bindListChrome() {
  $$(".list-meta").forEach(meta => {
    const wrap = document.getElementById(meta.dataset.wrap);
    const top = meta.querySelector(".to-top");
    if (!wrap || !top) return;
    const sync = () => meta.classList.toggle("has-top", wrap.scrollTop > 28);
    wrap.addEventListener("scroll", sync, {passive: true});
    top.addEventListener("click", () => wrap.scrollTo({top: 0, behavior: "smooth"}));
    sync();
  });
}
function scrollListTop(wrapId) {
  const wrap = document.getElementById(wrapId);
  if (wrap) wrap.scrollTo({top: 0, behavior: "smooth"});
}
function npcLabel(n) {
  return (n.name || "").trim() || String(n.nameid || "").trim();
}

async function api(path, options = {}) {
  const headers = {"Content-Type": "application/json", ...(options.headers || {})};
  if (state.token) headers["X-GM-Token"] = state.token;
  const response = await fetch(path, {...options, headers});
  const data = await response.json().catch(() => ({error: "无效响应"}));
  if (!response.ok) throw new Error(data.error || `请求失败 ${response.status}`);
  return data;
}

function esc(v) {
  return String(v ?? "").replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
}
function debounce(fn, ms = 200) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
function toast(msg, err) {
  const n = document.createElement("div");
  n.className = "toast" + (err ? " err" : "");
  n.textContent = msg;
  $("#toasts").append(n);
  setTimeout(() => n.remove(), 3200);
}
function friendlyError(msg) {
  const s = String(msg || "");
  if (/Communications link failure|The last packet sent successfully|CommunicationsException|Could not create connection|Connection refused|connect timed out|No operations allowed after connection closed|Communications link failure/i.test(s)) {
    const remote = (typeof state !== "undefined") && state.status && state.status.mode === "remote";
    return remote ? "未检测到远端服务端与数据库" : "未检测到本地服务端与数据库";
  }
  return s;
}
function fail(e) { toast(friendlyError(e && e.message ? e.message : e), true); }
function confirmAction({title, body, ok = "确定", cancel = "取消"}) {
  return new Promise(resolve => {
    document.querySelector(".confirm-mask")?.remove();
    const mask = document.createElement("div");
    mask.className = "confirm-mask";
    mask.innerHTML = `
      <div class="confirm" role="dialog" aria-modal="true" aria-labelledby="confirmTitle">
        <div class="confirm-title" id="confirmTitle">${esc(title)}</div>
        <p class="confirm-body">${esc(body)}</p>
        <div class="confirm-actions">
          <button type="button" class="secondary" data-act="no">${esc(cancel)}</button>
          <button type="button" class="primary" data-act="yes">${esc(ok)}</button>
        </div>
      </div>`;
    const close = value => { mask.remove(); document.removeEventListener("keydown", onKey); resolve(value); };
    const onKey = event => {
      if (event.key === "Escape") close(false);
      if (event.key === "Enter") close(true);
    };
    mask.addEventListener("click", event => {
      if (event.target === mask) close(false);
    });
    mask.querySelector("[data-act=no]").onclick = () => close(false);
    mask.querySelector("[data-act=yes]").onclick = () => close(true);
    document.addEventListener("keydown", onKey);
    document.body.append(mask);
    mask.querySelector("[data-act=no]").focus();
  });
}
function icon(name) { return `<svg><use href="/icons.svg#${name}"/></svg>`; }
function operator() { return state.selected.find(r => r.id === state.operatorId) || state.selected.find(r => r.online) || state.selected[0] || null; }
function selectedIds() { return state.selected.map(r => r.id); }
function isPicked(id) { return state.selected.some(r => r.id === id); }
function canAct() { return Boolean(state.status?.agent); }
const SELECT_KEY = "l1j-gmtool-selected";
function saveSelection() {
  try {
    localStorage.setItem(SELECT_KEY, JSON.stringify({
      ids: state.selected.map(r => ({id: r.id, name: r.name || ""})),
      operatorId: state.operatorId
    }));
  } catch (_) { /* ignore quota */ }
}
function loadSavedSelection() {
  try {
    const data = JSON.parse(localStorage.getItem(SELECT_KEY) || "null");
    if (!data || !Array.isArray(data.ids)) return;
    state.selected = data.ids.map(s => ({
      id: Number(s.id),
      name: s.name || "",
      online: false
    })).filter(s => s.id > 0);
    const op = Number(data.operatorId);
    state.operatorId = state.selected.some(r => r.id === op) ? op : (state.selected[0]?.id || null);
  } catch (_) { /* ignore */ }
}

function toggleSelect(row) {
  const i = state.selected.findIndex(r => r.id === row.id);
  if (i >= 0) state.selected.splice(i, 1);
  else state.selected.push(row);
  if (!state.selected.some(r => r.id === state.operatorId)) {
    const online = state.selected.find(r => r.online);
    state.operatorId = online ? online.id : (state.selected[0]?.id || null);
  }
  renderChips();
  renderChars();
  updateDisabled();
  if (state.mapTab === "goto") renderGotoList();
  saveSelection();
}

function renderChips() {
  const box = $("#chips");
  if (!state.selected.length) {
    box.innerHTML = `<span class="hint" id="chipEmpty">先选定角色</span>`;
    return;
  }
  box.innerHTML = state.selected.map(r => `
    <span class="chip ${r.id === state.operatorId ? "operator" : ""}" data-id="${r.id}">
      ${esc(r.name)} ${r.online ? "在" : "离"}
      <button data-x="${r.id}">${icon("x")}</button>
    </span>`).join("");
  $$("#chips .chip").forEach(n => n.addEventListener("click", e => {
    if (e.target.closest("button")) return;
    state.operatorId = Number(n.dataset.id);
    renderChips();
    updateDisabled();
    saveSelection();
  }));
  $$("#chips button[data-x]").forEach(n => n.addEventListener("click", e => {
    e.stopPropagation();
    const row = state.selected.find(r => r.id === Number(n.dataset.x));
    if (row) toggleSelect(row);
  }));
}

async function loadStatus(silent) {
  try {
    const s = await api("/api/status");
    state.status = s;
    state.token = s.token;
    $("#lampDb").className = "lamp ok";
    const remote = s.mode === "remote";
    $("#lampWin").className = "lamp " + (remote ? "ok" : (s.agent ? "ok" : "bad"));
    $("#lampWin").title = remote
      ? "远端模式 · 服务端在云端，GM 指令走游戏窗口"
      : (s.agent ? ("游戏服已注入 ver=" + (s.agentVer || "?")) : (s.agentError || "未连上游戏服"));
    $("#pendingCount").textContent = s.pending;
  } catch (e) {
    $("#lampDb").className = "lamp bad";
    if (!silent) fail(e);
  }
}

async function loadChars() {
  const q = new URLSearchParams({
    q: state.charQuery, sort: state.charSort,
    online: state.charOnline.length === 2 ? "1,0" : (state.charOnline[0] || "none"),
    type: state.charType
  });
  state.characters = await api("/api/characters?" + q);
  state.selected = state.selected.map(r => state.characters.find(c => c.id === r.id) || r);
  renderChars();
  renderChips();
  if (state.mapTab === "goto") renderGotoList();
}

function renderChars() {
  $$("th[data-sort]").forEach(th => th.classList.toggle("sort", state.charSort.replace("-", "") === th.dataset.sort));
  const total = state.characters.length;
  const pages = Math.max(1, Math.ceil(total / state.charLimit));
  if (state.charPage > pages - 1) state.charPage = pages - 1;
  if (state.charPage < 0) state.charPage = 0;
  const slice = state.characters.slice(state.charPage * state.charLimit, (state.charPage + 1) * state.charLimit);
  $("#charCount").textContent = total + " 名";
  $("#charPage").textContent = `${state.charPage + 1} / ${pages}`;
  $("#charPrev").disabled = state.charPage <= 0;
  $("#charNext").disabled = state.charPage >= pages - 1;
  $("#charRows").innerHTML = slice.map(r => `
    <tr data-id="${r.id}" class="${state.pickedChar?.id === r.id ? "selected" : ""}">
      <td class="check"><input type="checkbox" ${isPicked(r.id) ? "checked" : ""}></td>
      <td>${esc(r.name)}</td>
      <td>${esc(r.account)}</td>
      <td>${esc(r.className)}</td>
      <td>${r.level}</td>
      <td>${Number(r.exp).toLocaleString()}</td>
      <td>${r.maxHp}/${r.maxMp}</td>
      <td><span class="pill ${r.online ? "on" : ""}">${r.online ? "在线" : "离线"}</span></td>
    </tr>`).join("");
  $$("#charRows tr").forEach(tr => {
    const id = Number(tr.dataset.id);
    tr.addEventListener("click", e => {
      if (e.target.closest("input")) return;
      refreshAndPickChar(id);
    });
    tr.querySelector("input").addEventListener("click", e => {
      e.stopPropagation();
      const row = state.characters.find(r => r.id === id);
      toggleSelect(row);
    });
  });
  syncListInspector("chars");
}

async function refreshAndPickChar(id) {
  const now = performance.now();
  if (now - state.charRefreshAt < 100) {
    toast("数据刷新间隔低于0.1s，请重试", true);
    return;
  }
  state.charRefreshAt = now;
  try {
    const fresh = await api(`/api/character?id=${id}`);
    const index = state.characters.findIndex(r => r.id === id);
    if (index >= 0) state.characters[index] = fresh;
    const selected = state.selected.findIndex(r => r.id === id);
    if (selected >= 0) state.selected[selected] = fresh;
    pickChar(fresh.id);
    toast(`${fresh.account || fresh.name} 账号数据已刷新`);
  } catch (err) { fail(err); }
}

function pickChar(id) {
  state.pickedChar = state.characters.find(r => r.id === id) || null;
  renderChars();
  const r = state.pickedChar;
  if (!r) {
    syncListInspector("chars");
    return;
  }
  setInspector("chars", true);
  $("#charInspectName").textContent = r.name;
  $("#charKv").innerHTML = [
    ["账号", r.account], ["职业", r.className], ["等级", r.level],
    ["经验", Number(r.exp).toLocaleString()],
    ["位置", `${r.map}:${r.x},${r.y}`],
    ["权限", `角色 ${r.access} / 账号 ${r.accountAccess}`]
  ].map(([k, v]) => `<div><dt>${k}</dt><dd>${esc(v)}</dd></div>`).join("");
  $("#accessLevel").value = r.access;
  $("#statHp").value = r.hp;
  $("#statMaxHp").value = r.maxHp;
  $("#statMp").value = r.mp;
  $("#statMaxMp").value = r.maxMp;
  $("#statNote").textContent = r.online
    ? "在线改血蓝走 .角色更改o，改的是上限并回满。权限改动进待办。"
    : "离线写入库。若之后上线会被内存覆盖前的值。";
  $("#saveAccess").disabled = false;
  $("#saveStats").disabled = false;
}

async function followTo(targetId) {
  if (!state.selected.length) return toast("先选定角色", true);
  try {
    const data = await api("/api/follow", {
      method: "POST",
      body: JSON.stringify({
        targetId,
        operatorId: operator()?.id,
        selectedIds: selectedIds()
      })
    });
    const blocked = data.results.filter(r => r.action === "blocked");
    const pending = data.results.filter(r => r.action === "pending");
    const follow = data.results.filter(r => r.action === "follow");
    const skipped = data.results.filter(r => r.action === "skip");
    if (follow.length) toast(follow.map(r => r.message || r.command).join("；"));
    if (pending.length) toast(pending.length + " 名离线号已进待办");
    skipped.forEach(r => toast(r.message, true));
    blocked.forEach(r => toast(r.message, true));
    if (!follow.length && !pending.length && !blocked.length && !skipped.length) {
      toast("没有可执行的移动", true);
    }
    loadPending();
  } catch (e) { fail(e); }
}

function setMapTab(tab) {
  state.mapTab = tab;
  $$(".insp-tabs button").forEach(b => {
    const on = b.dataset.tab === tab;
    b.classList.toggle("active", on);
    b.setAttribute("aria-selected", String(on));
  });
  $("#mapPickPane").hidden = tab !== "pick";
  $("#mapGotoPane").hidden = tab !== "goto";
  if (tab === "goto") {
    if (!state.characters.length) loadChars().then(renderGotoList).catch(fail);
    else renderGotoList();
  }
}

function renderGotoList() {
  const box = $("#gotoList");
  const hint = $("#gotoHint");
  if (!box || !hint) return;
  const canFollow = state.selected.length > 0;
  const canMap = Boolean(state.mapClick);
  hint.textContent = canFollow
    ? `点名字：已选 ${state.selected.length} 名跟过去 · 传至地图：送到左边点过的格`
    : "勾选后点名字跟到此人；传至地图不依赖勾选，送到左边点过的格";
  const q = (state.gotoQuery || "").trim().toLowerCase();
  const rows = state.characters.filter(r => {
    if (!q) return true;
    return String(r.name).toLowerCase().includes(q) || String(r.account).toLowerCase().includes(q);
  });
  box.innerHTML = rows.map(r => `
    <div class="goto-row">
      <button type="button" class="goto-who" data-go="${r.id}" ${canFollow ? "" : "disabled"}>
        <span class="who"><b>${esc(r.name)}</b><span>${esc(r.className)} · ${r.online ? "在线" : "离线"} · ${r.map}:${r.x},${r.y}</span></span>
      </button>
      <button type="button" class="goto-mapbtn" data-to="${r.id}" ${canMap ? "" : "disabled"} title="${canMap ? "送到 " + state.mapClick.map + ":" + state.mapClick.x + "," + state.mapClick.y : "先在地图上点一格"}">传至地图</button>
    </div>`).join("") || `<div class="empty" style="position:static;padding:16px">没有匹配角色</div>`;
  $$("#gotoList .goto-who").forEach(b => b.onclick = () => followTo(Number(b.dataset.go)));
  $$("#gotoList .goto-mapbtn").forEach(b => b.onclick = e => {
    e.stopPropagation();
    sendOneToMap(Number(b.dataset.to));
  });
}

async function sendOneToMap(characterId) {
  if (!state.mapClick) return toast("先在地图上点一格", true);
  try {
    const data = await api("/api/teleport", {
      method: "POST",
      body: JSON.stringify({
        x: state.mapClick.x, y: state.mapClick.y, map: state.mapId, characterId
      })
    });
    toast(data.message || data.command);
    if (data.mode === "pending") loadPending();
  } catch (e) { fail(e); }
}

function syncFilterDraft() {
  $("#fltOnline").checked = state.charOnline.includes("1");
  $("#fltOffline").checked = state.charOnline.includes("0");
  $$("input[name=fltJob]").forEach(r => { r.checked = r.value === (state.charType || ""); });
}

function markFilterBtn() {
  const def = state.charOnline.includes("1") && state.charOnline.includes("0") && !state.charType;
  $(".filter-wrap")?.classList.toggle("has-filter", !def);
}

function setCharFilterOpen(open) {
  const pop = $("#charFilterPop");
  const wrap = $(".filter-wrap");
  const btn = $("#charFilterBtn");
  if (!pop || !wrap || !btn) return;
  pop.hidden = !open;
  wrap.classList.toggle("is-open", open);
  btn.setAttribute("aria-expanded", String(open));
  if (open) syncFilterDraft();
}

function applyCharFilter() {
  const online = [];
  if ($("#fltOnline").checked) online.push("1");
  if ($("#fltOffline").checked) online.push("0");
  state.charOnline = online;
  state.charType = $$("input[name=fltJob]:checked")[0]?.value || "";
  state.charPage = 0;
  markFilterBtn();
  setCharFilterOpen(false);
  loadChars().then(() => scrollListTop("charWrap")).catch(fail);
}

async function loadItems() {
  state.itemPageSwap = true;
  $("#itemRows")?.classList.add("page-switch");
  const q = new URLSearchParams({
    q: state.itemQuery, category: state.itemCategory,
    offset: state.itemPage * state.itemLimit, limit: state.itemLimit
  });
  const data = await api("/api/items?" + q);
  state.items = data.rows;
  state.itemTotal = data.total;
  renderItems();
}

function renderItems() {
  $("#itemCount").textContent = state.itemTotal.toLocaleString() + " 项";
  $("#itemEmpty").hidden = state.items.length !== 0;
  const rows = $("#itemRows");
  rows.classList.remove("page-switch", "page-enter");
  rows.innerHTML = state.items.map(it => `
    <tr data-id="${it.id}" class="${state.pickedItem?.id === it.id && !state.creatingItem ? "selected" : ""}">
      <td><code>${it.id}</code></td>
      <td>${esc(it.name)}</td>
      <td>${cats[it.category] || it.category}</td>
      <td>${esc(typeName(it.type))}</td>
      <td>${it.weight}</td>
      <td>${it.stackable ? "可堆叠" : "单件"}</td>
    </tr>`).join("");
  if (state.itemPageSwap) {
    rows.classList.add("page-enter");
    setTimeout(() => rows.classList.remove("page-enter"), 280);
  }
  $$("#itemRows tr").forEach(tr => tr.addEventListener("click", () => {
    state.creatingItem = false;
    state.pickedItem = state.items.find(it => it.id === Number(tr.dataset.id));
    renderItems();
    setInspector("items", true);
    renderItemInspector();
  }));
  syncListInspector("items");
  const pages = Math.max(1, Math.ceil(state.itemTotal / state.itemLimit));
  $("#itemPage").textContent = `${state.itemPage + 1} / ${pages}`;
  $("#itemPrev").disabled = state.itemPage <= 0;
  $("#itemNext").disabled = state.itemPage >= pages - 1;
  state.itemPageSwap = false;
}

function pngSrc(b64) { return b64 ? `data:image/png;base64,${b64}` : ""; }

async function showIcon(el, id) {
  if (!el || !id) return;
  try {
    const data = await api("/api/preview/icon?id=" + id);
    if (data.png) el.innerHTML = `<img alt="" src="${pngSrc(data.png)}">`;
  } catch { /* placeholder stays */ }
}

function renderItemInspector() {
  const box = $("#itemInspector");
  if (state.creatingItem) {
    box.innerHTML = itemCreateForm();
    bindItemCreate();
    return;
  }
  const it = state.pickedItem;
  if (!it) {
    box.innerHTML = `<div class="inspect-name">现有物品</div><p class="note">点表中一行发放，或按新建物品。</p>`;
    syncListInspector("items");
    return;
  }
  const op = operator();
  const adena = it.id === 40308;
  box.innerHTML = `
    <div class="preview" id="itemPrevBox"></div>
    <div class="inspect-name">${esc(it.name)}</div>
    <dl class="kv">
      <div><dt>ID</dt><dd>${it.id}</dd></div>
      <div><dt>类型</dt><dd>${esc(typeName(it.type))}</dd></div>
      <div><dt>图标</dt><dd>${it.invgfx}</dd></div>
      <div><dt>负重</dt><dd>${it.weight}</dd></div>
    </dl>
    <div class="row2">
      <label class="field">数量<input type="number" id="giveCount" value="1" min="1"${adena ? ' max="2000000000"' : ""}></label>
      <label class="field">强化<input type="number" id="giveEnchant" value="0"></label>
    </div>
    <label class="field">仓库目标角色
      <select id="giveTarget">${state.selected.length ? state.selected.map(r => `<option value="${r.id}" ${r.id === op?.id ? "selected" : ""}>${esc(r.name)} · ${r.online ? "在线" : "离线"}</option>`).join("") : "<option value=\"\">先选定角色</option>"}</select>
    </label>
    <p class="note">${adena ? "金币走专属指令 .钱o（L1Adena），上限 20 亿；仓库发放仍走 .给予o。" : ".do 进操作者背包；仓库发放可选择顶栏任一角色。禁止给在线角色插 character_items。"}</p>
    <div class="actions">
      <button class="primary" id="giveDo" ${canAct() ? "" : "disabled"}>${adena ? ".钱o 到操作者" : ".do 到操作者"}</button>
      <button class="secondary" id="giveWh" ${canAct() && state.selected.length ? "" : "disabled"}>.给予o 到仓库</button>
    </div>`;
  showIcon($("#itemPrevBox"), it.invgfx);
  $("#giveDo").onclick = () => sendItem("do");
  $("#giveWh").onclick = () => sendItem("warehouse");
}

function itemCreateForm() {
  const m = state.itemMeta || {};
  const opt = (arr, cur) => (arr || []).map(v => `<option value="${esc(v)}" ${v === cur ? "selected" : ""}>${esc(typeName(v))}</option>`).join("");
  return `
    <div class="preview" id="newIconPrev"></div>
    <div class="inspect-name">新建物品</div>
    <label class="field">落表
      <select id="newTable"><option value="etcitem">道具 etcitem</option><option value="weapon">武器 weapon</option><option value="armor">防具 armor</option></select>
    </label>
    <label class="field">名称<input id="newName"></label>
    <label class="field">name_id<input id="newNameId" placeholder="可空，默认同名称"></label>
    <label class="field">图标 invgfx<input id="newInvgfx" type="number" value="0"></label>
    <label class="field">类型<select id="newType"></select></label>
    <label class="field" id="useTypeWrap">use_type<select id="newUseType">${opt(m.useTypes, "none")}</select></label>
    <div class="row2">
      <label class="field">负重<input id="newWeight" type="number" value="0"></label>
      <label class="field">材料<select id="newMat">${opt(m.materials, "none")}</select></label>
    </div>
    <label class="field">classname<select id="newClass"><option>0</option>${opt(m.classnames)}</select></label>
    <label class="field"><input type="checkbox" id="newStack"> 可堆叠</label>
    <div class="row2" id="useJobs">
      <label class="field">王族<input type="checkbox" id="uj0" checked></label>
      <label class="field">骑士<input type="checkbox" id="uj1" checked></label>
      <label class="field">法师<input type="checkbox" id="uj2" checked></label>
      <label class="field">妖精<input type="checkbox" id="uj3" checked></label>
      <label class="field">黑妖<input type="checkbox" id="uj4" checked></label>
      <label class="field">龙骑<input type="checkbox" id="uj5" checked></label>
      <label class="field">幻术<input type="checkbox" id="uj6" checked></label>
    </div>
    <label class="field" id="acWrap" hidden>AC<input id="newAc" type="number" value="0"></label>
    <p class="note">保存是 INSERT 模板。重启游戏服后才进内存。</p>
    <div class="actions"><button class="primary" id="saveItem">写入物品表</button></div>`;
}

function bindItemCreate() {
  const m = state.itemMeta || {};
  const fillTypes = () => {
    const table = $("#newTable").value;
    const types = table === "weapon" ? m.weaponTypes : table === "armor" ? m.armorTypes : m.etcTypes;
    $("#newType").innerHTML = (types || []).map(v => `<option value="${esc(v)}">${esc(typeName(v))}</option>`).join("");
    $("#useTypeWrap").hidden = table !== "etcitem";
    $("#acWrap").hidden = table !== "armor";
    $("#useJobs").hidden = table === "etcitem";
  };
  fillTypes();
  $("#newTable").onchange = fillTypes;
  $("#newInvgfx").oninput = debounce(() => showIcon($("#newIconPrev"), Number($("#newInvgfx").value)), 240);
  $("#saveItem").onclick = async () => {
    try {
      const table = $("#newTable").value;
      const body = {
        table,
        name: $("#newName").value.trim(),
        nameId: $("#newNameId").value.trim(),
        invgfx: Number($("#newInvgfx").value) || 0,
        type: $("#newType").value,
        useType: $("#newUseType").value,
        weight: Number($("#newWeight").value) || 0,
        material: $("#newMat").value,
        classname: $("#newClass").value,
        stackable: $("#newStack").checked,
        ac: Number($("#newAc")?.value) || 0,
        useRoyal: $("#uj0").checked, useKnight: $("#uj1").checked, useMage: $("#uj2").checked,
        useElf: $("#uj3").checked, useDarkelf: $("#uj4").checked, useDragonknight: $("#uj5").checked,
        useIllusionist: $("#uj6").checked
      };
      const created = await api("/api/create-item", {method: "POST", body: JSON.stringify(body)});
      toast(`已写入 ${created.itemId}，${created.hint}`);
      state.creatingItem = false;
      loadItems();
    } catch (e) { fail(e); }
  };
}

async function sendItem(mode) {
  const it = state.pickedItem;
  const op = operator();
  if (!it || !op) return;
  try {
    const recipientId = Number($("#giveTarget")?.value) || op.id;
    const body = {
      mode, itemId: it.id, characterId: recipientId, operatorId: op.id,
      count: Number($("#giveCount").value) || 1,
      enchant: Number($("#giveEnchant").value) || 0
    };
    const data = await api("/api/send-item", {method: "POST", body: JSON.stringify(body)});
    toast(data.command);
  } catch (e) { fail(e); }
}

function npcBag(kind) {
  const mob = kind === "monster";
  return {
    kind,
    list: mob ? state.mobs : state.npcs,
    total: mob ? state.mobTotal : state.npcTotal,
    page: mob ? state.mobPage : state.npcPage,
    query: mob ? state.mobQuery : state.npcQuery,
    picked: mob ? state.pickedMob : state.pickedNpc,
    creating: mob ? state.creatingMob : state.creatingNpc,
    swap: mob ? state.mobPageSwap : state.npcPageSwap,
    rows: mob ? "#mobRows" : "#npcRows",
    count: mob ? "#mobCount" : "#npcCount",
    pager: mob ? "#mobPage" : "#npcPage",
    prev: mob ? "#mobPrev" : "#npcPrev",
    next: mob ? "#mobNext" : "#npcNext",
    inspector: mob ? "#mobInspector" : "#npcInspector"
  };
}

async function loadNpcs(kind = state.view === "mobs" ? "monster" : "npc") {
  const mob = kind === "monster";
  if (mob) state.mobPageSwap = true; else state.npcPageSwap = true;
  const bag = npcBag(kind);
  $(bag.rows)?.classList.add("page-switch");
  const q = new URLSearchParams({
    q: bag.query, offset: bag.page * state.npcLimit, limit: state.npcLimit, kind
  });
  const data = await api("/api/npcs?" + q);
  if (mob) { state.mobs = data.rows; state.mobTotal = data.total; }
  else { state.npcs = data.rows; state.npcTotal = data.total; }
  renderNpcs(kind);
}

function renderNpcs(kind = state.view === "mobs" ? "monster" : "npc") {
  const bag = npcBag(kind);
  const mob = kind === "monster";
  const count = $(bag.count);
  if (count) count.textContent = bag.total.toLocaleString() + " 名";
  const rows = $(bag.rows);
  if (!rows) return;
  rows.classList.remove("page-switch", "page-enter");
  rows.innerHTML = bag.list.map(n => {
    const typeCell = mob ? "" : `<td>${esc(implName(n.impl))}</td>`;
    return `
    <tr data-id="${n.id}" class="${bag.picked?.id === n.id && !bag.creating ? "selected" : ""}">
      <td><code>${n.id}</code></td>
      <td>${esc(npcLabel(n))}</td>
      ${typeCell}
      <td>${n.gfxid}</td>
      <td>${n.shops}</td>
      <td>${n.spawns}</td>
    </tr>`;
  }).join("");
  if (bag.swap) {
    rows.classList.add("page-enter");
    setTimeout(() => rows.classList.remove("page-enter"), 280);
  }
  $$(bag.rows + " tr").forEach(tr => tr.addEventListener("click", () => {
    if (kind === "monster") {
      state.creatingMob = false;
      state.pickedMob = state.mobs.find(n => n.id === Number(tr.dataset.id));
    } else {
      state.creatingNpc = false;
      state.pickedNpc = state.npcs.find(n => n.id === Number(tr.dataset.id));
    }
    renderNpcs(kind);
    setInspector(mob ? "mobs" : "npcs", true);
    renderNpcInspector(kind);
  }));
  const pages = Math.max(1, Math.ceil(bag.total / state.npcLimit));
  const pager = $(bag.pager);
  if (pager) pager.textContent = `${bag.page + 1} / ${pages}`;
  const prev = $(bag.prev);
  const next = $(bag.next);
  if (prev) prev.disabled = bag.page <= 0;
  if (next) next.disabled = bag.page >= pages - 1;
  if (kind === "monster") state.mobPageSwap = false; else state.npcPageSwap = false;
  syncListInspector(mob ? "mobs" : "npcs");
}

async function renderNpcInspector(kind = state.view === "mobs" ? "monster" : "npc") {
  const bag = npcBag(kind);
  const box = $(bag.inspector);
  if (!box) return;
  if (bag.creating) {
    box.innerHTML = npcCreateForm(kind);
    bindNpcCreate(kind);
    return;
  }
  const mob = kind === "monster";
  const n = bag.picked;
  if (!n) {
    box.innerHTML = `<p class="note">点一行查看，或新建。</p>`;
    syncListInspector(mob ? "mobs" : "npcs");
    return;
  }
  if (!mob) state.shop = n.shops ? await api("/api/shop?npcId=" + n.id) : [];
  const liveLabel = mob ? "放置一只" : "放置一位";
  const dbLabel = mob ? "设为刷怪点" : "设为NPC刷新点";
  const clearLabel = mob ? "清空全部刷怪点" : "清空全部NPC刷新点";
  const p = mob ? "mob" : "npc";
  const shopBtn = mob ? "" : `<button class="secondary" id="editShop">${n.impl.includes("Merchant") || n.shops ? "货单" : "当作商人编货单"}</button>`;
  const typeRow = mob ? "" : `<div><dt>类型</dt><dd>${esc(implName(n.impl))}</dd></div>`;
  box.innerHTML = `
    <div class="inspect-name">${esc(npcLabel(n))}</div>
    <dl class="kv">
      <div><dt>编号</dt><dd>${n.id}</dd></div>
      ${typeRow}
      <div><dt>外形编号</dt><dd>${n.gfxid}</dd></div>
    </dl>
    <label class="field">放置目标角色
      <select id="${p}SpawnTarget">${state.selected.length ? state.selected.map(r => `<option value="${r.id}" ${r.id === operator()?.id ? "selected" : ""}>${esc(r.name)} · ${r.online ? "在线" : "离线"}</option>`).join("") : "<option value=\"\">先选定角色</option>"}</select>
    </label>
    <div class="actions">
      <button class="primary" id="${p}SpawnLive" ${canAct() ? "" : "disabled"}>${liveLabel}</button>
      <button class="secondary" id="${p}SpawnDb" ${state.selected.length ? "" : "disabled"}>${dbLabel}</button>
      ${shopBtn}
      <button class="secondary" id="${p}DisableSpawn">${clearLabel}</button>
    </div>
    <div id="shopBox"></div>`;
  $(`#${p}SpawnLive`).onclick = () => spawnNpc(true, kind);
  $(`#${p}SpawnDb`).onclick = async () => {
    const who = $(`#${p}SpawnTarget`)?.selectedOptions?.[0]?.textContent || "选定角色";
    const ok = await confirmAction({
      title: dbLabel,
      body: mob
        ? `会在 ${who} 脚下写入刷怪点（spawnlist），并立刻在场上放一只。重启后仍按这个点刷。待办里可一并撤回刷怪点和当场那只。确定设立？`
        : `会在 ${who} 脚下写入 NPC 刷新点，并立刻在场上放一位。重启后仍按这个点刷。待办里可一并撤回刷新点和当场那位。确定设立？`,
      ok: "设立并放置"
    });
    if (!ok) return;
    spawnNpc(false, kind);
  };
  $("#editShop")?.addEventListener("click", () => renderShopEditor(n.id));
  $(`#${p}DisableSpawn`).onclick = async () => {
    const ok = await confirmAction({
      title: clearLabel,
      body: mob
        ? `会把怪物 ${n.id} 在 spawnlist 里的全部刷怪点 count 置 0，包括地图上原来就有的。已刷出来的不会立刻消失。确定清空？`
        : `会把 NPC ${n.id} 在 spawnlist_npc 里的全部刷新点 count 置 0，包括地图上原来就有的。已刷出来的不会立刻消失。确定清空？`,
      ok: "清空"
    });
    if (!ok) return;
    try {
      await api("/api/disable-spawns", {method: "POST", body: JSON.stringify({npcId: n.id})});
      toast(mob ? "已清空刷怪点" : "已清空 NPC 刷新点");
      loadNpcs(kind);
      loadPending();
    } catch (e) { fail(e); }
  };
}

function npcCreateForm(kind = "npc") {
  const m = state.npcMeta || {};
  const impls = (m.impls || ["L1Merchant","L1Teleporter","L1Monster","L1Dwarf"]).filter(v =>
    kind === "monster" ? v === "L1Monster" : v !== "L1Monster"
  );
  const def = kind === "monster" ? "L1Monster" : "L1Merchant";
  return `
    <label class="field">头顶名称<input id="newNpcName"></label>
    <label class="field">类型
      <select id="newImpl">${impls.map(v => `<option value="${esc(v)}" ${v===def?"selected":""}>${esc(implName(v))}</option>`).join("")}</select>
    </label>
    <label class="field">朝向 0-7<input id="newHead" type="number" min="0" max="7" value="5"></label>
    <label class="field">外形编号<input id="newGfx" type="number" value="${(m.gfxids||[0])[0]||0}"></label>
    <div class="gfx-list" id="gfxList">${(m.gfxids||[]).slice(0,400).map(g => `<button data-g="${g}">${g}</button>`).join("")}</div>
    <p class="note">商人可在保存后编货单。外形编号写入 npc.gfxid，实际样子只在游戏客户端里画，这里不再解码 SPR。</p>
    <div class="actions"><button class="primary" id="saveNpc">写入 NPC 表</button></div>`;
}

function bindNpcCreate(kind = "npc") {
  $$("#gfxList button").forEach(b => b.onclick = () => { $("#newGfx").value = b.dataset.g; });
  $("#saveNpc").onclick = async () => {
    try {
      const body = {
        name: $("#newNpcName").value.trim(),
        nameid: $("#newNpcName").value.trim(),
        impl: $("#newImpl").value,
        gfxid: Number($("#newGfx").value),
        heading: Number($("#newHead").value) || 0,
        merchant: $("#newImpl").value.includes("Merchant")
      };
      const created = await api("/api/create-npc", {method: "POST", body: JSON.stringify(body)});
      toast(`已写入 ${created.npcId}，${created.hint}`);
      if (kind === "monster") {
        state.creatingMob = false;
        state.mobQuery = String(created.npcId);
        $("#mobSearch").value = state.mobQuery;
        await loadNpcs("monster");
        state.pickedMob = state.mobs.find(n => n.id === created.npcId) || {id: created.npcId, name: body.name, nameid: body.nameid, impl: body.impl, gfxid: body.gfxid, shops: 0, spawns: 0};
      } else {
        state.creatingNpc = false;
        state.npcQuery = String(created.npcId);
        $("#npcSearch").value = state.npcQuery;
        await loadNpcs("npc");
        state.pickedNpc = state.npcs.find(n => n.id === created.npcId) || {id: created.npcId, name: body.name, nameid: body.nameid, impl: body.impl, gfxid: body.gfxid, shops: 0, spawns: 0};
      }
      loadPending();
      renderNpcInspector(kind);
    } catch (e) { fail(e); }
  };
}

async function spawnNpc(live, kind = state.view === "mobs" ? "monster" : "npc") {
  const mob = kind === "monster";
  const n = mob ? state.pickedMob : state.pickedNpc;
  const op = operator();
  if (!n) return;
  const targetSel = $(mob ? "#mobSpawnTarget" : "#npcSpawnTarget");
  try {
    const payload = {
      npcId: n.id,
      live,
      kind,
      operatorId: op?.id,
      characterId: Number(targetSel?.value) || op?.id || state.selected[0]?.id
    };
    if (mob) {
      payload.x = state.mapClick?.x || 0;
      payload.y = state.mapClick?.y || 0;
      payload.map = state.mapId || 0;
    }
    const data = await api("/api/spawn-npc", {
      method: "POST",
      body: JSON.stringify(payload)
    });
    toast(data.command || data.hint || "已处理");
    loadPending();
    loadNpcs(kind).catch(() => {});
  } catch (e) { fail(e); }
}

function renderShopEditor(npcId) {
  const box = $("#shopBox");
  box.innerHTML = `
    <div class="inspect-name" style="margin-top:8px">货单</div>
    <button class="secondary" id="copy90043">从 90043 复制</button>
    <label class="search" style="margin:8px 0"><input id="shopQ" placeholder="搜索物品加入货单"></label>
    <div class="gfx-list" id="shopHits"></div>
    <div id="shopRows"></div>
    <button class="primary" id="saveShop" style="margin-top:8px">保存货单</button>`;
  const draw = () => {
    $("#shopRows").innerHTML = state.shop.map((s, i) => `
      <div class="shop-edit" data-i="${i}">
        <span>${esc(s.name)} (${s.itemId})</span>
        <input type="number" data-k="selling" value="${s.selling}" title="售价 -1=不卖">
        <input type="number" data-k="purchasing" value="${s.purchasing}" title="收购 -1=不收">
      </div>`).join("");
    $$("#shopRows input").forEach(inp => inp.oninput = () => {
      const i = Number(inp.closest(".shop-edit").dataset.i);
      state.shop[i][inp.dataset.k] = Number(inp.value);
    });
  };
  draw();
  $("#copy90043").onclick = async () => {
    try {
      await api("/api/copy-shop", {method: "POST", body: JSON.stringify({fromNpcId: 90043, toNpcId: npcId})});
      state.shop = await api("/api/shop?npcId=" + npcId);
      draw();
      toast("已复制 90043");
      loadPending();
    } catch (e) { fail(e); }
  };
  $("#shopQ").oninput = debounce(async () => {
    const q = $("#shopQ").value.trim();
    if (!q) { $("#shopHits").innerHTML = ""; return; }
    const data = await api("/api/items?q=" + encodeURIComponent(q) + "&limit=20");
    $("#shopHits").innerHTML = data.rows.map(it => `<button class="shop-pick" data-id="${it.id}">${esc(it.name)} ${it.id}</button>`).join("");
    $$("#shopHits button").forEach(b => b.onclick = () => {
      const it = data.rows.find(x => x.id === Number(b.dataset.id));
      if (!it || state.shop.some(s => s.itemId === it.id)) return;
      state.shop.push({itemId: it.id, name: it.name, orderId: state.shop.length, selling: 1, purchasing: -1, pack: 0, invgfx: it.invgfx});
      draw();
    });
  }, 200);
  $("#saveShop").onclick = async () => {
    try {
      await api("/api/save-shop", {method: "POST", body: JSON.stringify({npcId, items: state.shop})});
      toast("货单已写库，重启或 .商店表o");
      loadPending();
    } catch (e) { fail(e); }
  };
}

const pendingType = {
  loc: "坐标", hpmp: "血蓝", access: "权限",
  "spawn-live": "放置", spawnlist: "刷新点",
  "create-npc": "新建NPC", "disable-spawns": "清空刷新点", "save-shop": "货单"
};
const pendingStatus = {
  "wait-offline": "待执行", undoable: "可撤回", done: "已执行",
  cancelled: "已取消", undone: "已撤回", failed: "失败"
};
function pendingCanUndo(t) {
  return t.status === "wait-offline" || t.status === "undoable";
}
function setDrawer(open) {
  state.drawer = open;
  document.querySelector(".shell").classList.toggle("drawer-closed", !open);
  const btn = $("#pendingToggle");
  if (btn) btn.setAttribute("aria-expanded", String(open));
}

async function loadPending() {
  state.pending = await api("/api/pending");
  const live = state.pending.filter(pendingCanUndo);
  $("#pendingCount").textContent = live.length;
  const ids = new Set(live.map(t => String(t.id)));
  if (state.pendingIds) {
    const added = [...ids].some(id => !state.pendingIds.has(id));
    if (added) {
      setDrawer(true);
      const btn = $("#pendingToggle");
      if (btn) {
        btn.classList.remove("pulse");
        void btn.offsetWidth;
        btn.classList.add("pulse");
      }
    }
  }
  state.pendingIds = ids;
  const trash = $("#pendingClear");
  if (trash) trash.disabled = state.pending.length === 0;
  $("#pendingList").innerHTML = state.pending.length ? state.pending.map(t => `
    <div class="task ${t.status === "done" || t.status === "cancelled" || t.status === "undone" ? "done" : ""}">
      ${pendingCanUndo(t) ? `<button class="task-undo" data-u="${t.id}" title="撤回" aria-label="撤回"><svg><use href="/icons.svg#undo"/></svg></button>` : ""}
      <div>${esc(t.characterName)} · ${esc(pendingType[t.type] || t.type)}</div>
      <div class="meta">${esc(t.detail)} · ${esc(pendingStatus[t.status] || t.status)}</div>
    </div>`).join("") : `<p class="note">没有记录</p>`;
  $$("#pendingList button.task-undo").forEach(b => b.onclick = async () => {
    try {
      const data = await api("/api/pending/undo", {method: "POST", body: JSON.stringify({id: b.dataset.u})});
      toast(data.hint || "已撤回");
      await loadPending();
      if (!state.pending.filter(pendingCanUndo).length) setDrawer(false);
      if (state.view === "npcs" || state.view === "mobs") loadNpcs().catch(() => {});
    } catch (e) { fail(e); }
  });
}

function findMapRow(id) {
  return state.mapCatalogAll.find(m => m.id === id) || state.mapCatalog.find(m => m.id === id) || null;
}

function setMapTitle(row) {
  const title = $("#mapTitle");
  const hud = $("#mapHudTitle");
  if (title) title.textContent = row ? `${row.name} / ${row.parent}` : "未选地图";
  if (hud) hud.textContent = row ? row.name : "未选地图";
}

function catalogGroupOrder(rows) {
  const seen = [];
  const has = new Set();
  rows.forEach(m => {
    if (!has.has(m.parent)) {
      has.add(m.parent);
      seen.push(m.parent);
    }
  });
  const named = seen.filter(p => p !== "未分类");
  return has.has("未分类") ? [...named, "未分类"] : named;
}

async function loadMapCatalog(force = false) {
  if (!state.mapQuery) {
    if (force || !state.mapCatalogAll.length) {
      state.mapCatalogAll = await api("/api/maps?q=");
    }
    state.mapCatalog = state.mapCatalogAll;
  } else {
    state.mapCatalog = await api(`/api/maps?q=${encodeURIComponent(state.mapQuery)}`);
  }
  if (state.mapGroup !== "全部" && !state.mapCatalog.some(m => m.parent === state.mapGroup)) {
    state.mapGroup = "全部";
  }
  renderMapCatalog();
}

function renderMapCatalog() {
  const groups = ["全部", ...catalogGroupOrder(state.mapCatalog)];
  $("#mapGroups").innerHTML = groups.map(g => `<button class="${state.mapGroup === g ? "active" : ""}" data-group="${esc(g)}">${esc(g)}</button>`).join("");
  $$("#mapGroups button").forEach(b => b.onclick = () => {
    state.mapGroup = b.dataset.group;
    if (state.mapGroup !== "全部") state.mapOpenGroups.add(state.mapGroup);
    renderMapCatalog();
  });
  const rows = state.mapCatalog.filter(m => state.mapGroup === "全部" || m.parent === state.mapGroup);
  const grouped = new Map();
  catalogGroupOrder(rows).forEach(parent => grouped.set(parent, []));
  rows.forEach(m => grouped.get(m.parent).push(m));
  $("#mapCatalog").innerHTML = [...grouped.entries()].filter(([, maps]) => maps.length).map(([parent, maps]) => {
    const open = Boolean(state.mapQuery) || state.mapOpenGroups.has(parent) || maps.some(m => m.id === state.mapId);
    return `<div class="map-group ${open ? "open" : "closed"}" data-parent="${esc(parent)}"><button class="map-group-head">${esc(parent)} <span class="muted">${maps.length}</span></button>${maps.map(m => `<button class="map-child ${m.id === state.mapId ? "active" : ""}" data-map="${m.id}">${m.id} · ${esc(m.name)}</button>`).join("")}</div>`;
  }).join("");
  $$("#mapCatalog .map-group-head").forEach(b => b.onclick = () => {
    const group = b.parentElement.dataset.parent;
    if (state.mapOpenGroups.has(group)) state.mapOpenGroups.delete(group); else state.mapOpenGroups.add(group);
    renderMapCatalog();
  });
  $$("#mapCatalog .map-child").forEach(b => b.onclick = () => selectMap(Number(b.dataset.map)));
}

function setMapCellInput(mapId, x, y) {
  const input = $("#mapCellInput");
  if (input) input.value = mapId + ":" + x + "," + y;
}

function parseMapCoord(text) {
  const s = String(text || "").trim().replace(/：/g, ":").replace(/，/g, ",");
  if (!s) return null;
  let m = s.match(/^(\d+)\s*:\s*(\d+)\s*,\s*(\d+)$/);
  if (m) return {map: Number(m[1]), x: Number(m[2]), y: Number(m[3])};
  m = s.match(/^(\d+)\s+(\d+)\s+(\d+)$/);
  if (m) return {map: Number(m[1]), x: Number(m[2]), y: Number(m[3])};
  m = s.match(/^(\d+)\s*,\s*(\d+)$/);
  if (m) return {map: state.mapId, x: Number(m[1]), y: Number(m[2])};
  return null;
}

async function goToCoord(mapId, x, y) {
  state.mapClick = {x, y, map: mapId};
  setMapCellInput(mapId, x, y);
  if (mapId !== state.mapId || !state.map) {
    await selectMap(mapId, {keepClick: true});
  } else {
    state.mapView.panX = 0;
    state.mapView.panY = 0;
    drawMap();
  }
  $("#mapGo").disabled = !canAct();
  if (state.mapTab === "goto") renderGotoList();
}

function submitMapCoord() {
  const input = $("#mapCellInput");
  if (!input) return;
  const parsed = parseMapCoord(input.value);
  if (!parsed) {
    toast("坐标格式：地图:X,Y  或  X,Y", true);
    return;
  }
  goToCoord(parsed.map, parsed.x, parsed.y).catch(fail);
}

async function selectMap(id, opts = {}) {
  const row = findMapRow(id) || {id, name: "地图 " + id, parent: "未分类"};
  const gen = ++state.mapLoadGen;
  state.mapId = id;
  if (!opts.keepClick) {
    state.mapClick = null;
    const input = $("#mapCellInput");
    if (input) input.value = "";
  }
  setMapTitle(row);
  renderMapCatalog();
  try {
    const meta = await api("/api/map/" + id + ".json");
    if (gen !== state.mapLoadGen) return;
    state.map = meta;
    const img = new Image();
    img.src = "/api/map/" + id + ".png" + (opts.force ? ("?force=1&t=" + Date.now()) : "");
    await img.decode();
    if (gen !== state.mapLoadGen) return;
    state.mapImg = img;
    const mapRect = $("#mapCanvas").parentElement.getBoundingClientRect();
    const fit = Math.min(
      (mapRect.width - 30) / Math.max(1, state.map.width),
      (mapRect.height - 30) / Math.max(1, state.map.height)
    );
    state.mapView = {
      scale: Math.max(.35, Math.min(id === 4 ? 2.2 : 8, fit || 1.2)),
      panX: 0,
      panY: 0
    };
    drawMap();
  } catch (e) {
    if (gen !== state.mapLoadGen) return;
    fail(e);
  }
}

async function refreshMaps() {
  const btn = $("#mapRefresh");
  if (state.mapRefreshing) return;
  state.mapRefreshing = true;
  if (btn) {
    btn.disabled = true;
    btn.classList.add("spinning");
  }
  const keepId = state.mapId;
  try {
    const data = await api("/api/maps/refresh", {method: "POST", body: "{}"});
    state.mapCatalogAll = Array.isArray(data.maps) ? data.maps : [];
    state.mapCatalog = state.mapQuery
      ? await api(`/api/maps?q=${encodeURIComponent(state.mapQuery)}`)
      : state.mapCatalogAll;
    if (state.mapGroup !== "全部" && !state.mapCatalog.some(m => m.parent === state.mapGroup)) {
      state.mapGroup = "全部";
    }
    renderMapCatalog();
    const next = findMapRow(keepId) ? keepId : 4;
    await selectMap(next, {force: true, keepClick: next === keepId && Boolean(state.mapClick)});
    toast("已刷新地图列表与样貌");
  } catch (e) {
    fail(e);
  } finally {
    state.mapRefreshing = false;
    if (btn) {
      btn.disabled = false;
      btn.classList.remove("spinning");
    }
  }
}

async function loadMap() {
  await loadMapCatalog();
  if (!state.map) await selectMap(4);
}

function drawMap() {
  const canvas = $("#mapCanvas");
  const m = state.map;
  if (!m || !state.mapImg) return;
  const rect = canvas.parentElement.getBoundingClientRect();
  const w = Math.max(1, Math.round(rect.width));
  const h = Math.max(1, Math.round(rect.height));
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  canvas.style.width = w + "px";
  canvas.style.height = h + "px";
  const g = canvas.getContext("2d");
  g.fillStyle = "#0a0c0e";
  g.fillRect(0, 0, canvas.width, canvas.height);
  const scale = state.mapView.scale;
  const focusX = (state.mapClick?.x ?? m.giranX ?? ((m.startX + m.endX) / 2)) - m.startX;
  const focusY = (state.mapClick?.y ?? m.giranY ?? ((m.startY + m.endY) / 2)) - m.startY;
  const sx = focusX * scale - canvas.width / 2 - state.mapView.panX;
  const sy = focusY * scale - canvas.height / 2 - state.mapView.panY;
  g.imageSmoothingEnabled = false;
  g.drawImage(state.mapImg, -sx, -sy, m.width * scale, m.height * scale);
  if (state.mapClick) {
    const px = (state.mapClick.x - m.startX) * scale - sx;
    const py = (state.mapClick.y - m.startY) * scale - sy;
    g.strokeStyle = "#c45c4a";
    g.strokeRect(px - 3, py - 3, 7, 7);
  }
  canvas._map = {scale, sx, sy, m};
}

function cancelMapPanAnim() {
  if (state.mapPanAnim) {
    cancelAnimationFrame(state.mapPanAnim);
    state.mapPanAnim = 0;
  }
}

function animateMapPanTo(tx, ty, ms = 340) {
  cancelMapPanAnim();
  const fromX = state.mapView.panX;
  const fromY = state.mapView.panY;
  if (Math.abs(fromX - tx) < 0.6 && Math.abs(fromY - ty) < 0.6) {
    state.mapView.panX = tx;
    state.mapView.panY = ty;
    drawMap();
    return;
  }
  const t0 = performance.now();
  const ease = t => 1 - Math.pow(1 - t, 3);
  const step = now => {
    const u = Math.min(1, (now - t0) / ms);
    const e = ease(u);
    state.mapView.panX = fromX + (tx - fromX) * e;
    state.mapView.panY = fromY + (ty - fromY) * e;
    drawMap();
    if (u < 1) state.mapPanAnim = requestAnimationFrame(step);
    else state.mapPanAnim = 0;
  };
  state.mapPanAnim = requestAnimationFrame(step);
}

function onMapClick(ev) {
  if (state.mapJustDragged) return;
  const canvas = $("#mapCanvas");
  const ctx = canvas._map;
  if (!ctx) return;
  const m = ctx.m;
  const r = canvas.getBoundingClientRect();
  const x = Math.floor((ev.clientX - r.left + ctx.sx) / ctx.scale) + m.startX;
  const y = Math.floor((ev.clientY - r.top + ctx.sy) / ctx.scale) + m.startY;
  const oldFocusX = (state.mapClick?.x ?? m.giranX ?? ((m.startX + m.endX) / 2)) - m.startX;
  const oldFocusY = (state.mapClick?.y ?? m.giranY ?? ((m.startY + m.endY) / 2)) - m.startY;
  const scale = state.mapView.scale;
  state.mapClick = {x, y, map: state.mapId};
  state.mapView.panX = (x - m.startX - oldFocusX) * scale + state.mapView.panX;
  state.mapView.panY = (y - m.startY - oldFocusY) * scale + state.mapView.panY;
  setMapCellInput(state.mapId, x, y);
  $("#mapGo").disabled = !canAct();
  if (state.mapTab === "goto") renderGotoList();
  animateMapPanTo(0, 0);
}

function onMapWheel(ev) {
  ev.preventDefault();
  const next = Math.max(.35, Math.min(8, state.mapView.scale * (ev.deltaY < 0 ? 1.12 : .89)));
  state.mapView.scale = next;
  drawMap();
}

function onMapPointerDown(ev) {
  const canvas = $("#mapCanvas");
  cancelMapPanAnim();
  state.mapDrag = {x: ev.clientX, y: ev.clientY, panX: state.mapView.panX, panY: state.mapView.panY, moved: false};
  canvas.classList.add("dragging");
  canvas.setPointerCapture?.(ev.pointerId);
}
function onMapPointerMove(ev) {
  if (!state.mapDrag) return;
  const dx = ev.clientX - state.mapDrag.x;
  const dy = ev.clientY - state.mapDrag.y;
  if (Math.abs(dx) + Math.abs(dy) > 3) state.mapDrag.moved = true;
  state.mapView.panX = state.mapDrag.panX + dx;
  state.mapView.panY = state.mapDrag.panY + dy;
  drawMap();
}
function onMapPointerUp(ev) {
  $("#mapCanvas").classList.remove("dragging");
  state.mapJustDragged = Boolean(state.mapDrag?.moved);
  state.mapDrag = null;
  setTimeout(() => { state.mapJustDragged = false; }, 0);
}

function updateDisabled() {
  const ready = canAct();
  const mapGo = $("#mapGo");
  if (mapGo) mapGo.disabled = !(ready && state.mapClick);
}

function hasListSelection(view) {
  if (view === "chars") return Boolean(state.pickedChar);
  if (view === "items") return Boolean(state.creatingItem || state.pickedItem);
  if (view === "npcs") return Boolean(state.creatingNpc || state.pickedNpc);
  if (view === "mobs") return Boolean(state.creatingMob || state.pickedMob);
  return true;
}

function syncListInspector(view) {
  if (!["chars", "items", "npcs", "mobs"].includes(view)) return;
  if (!hasListSelection(view)) setInspector(view, false);
}

function setInspector(view, open) {
  state.inspectors[view] = open;
  const ws = document.querySelector("#view-" + view + " .workspace");
  if (!ws) return;
  ws.classList.toggle("inspector-closed", !open);
  const btn = ws.querySelector(".inspector-toggle");
  if (btn) {
    btn.setAttribute("aria-expanded", String(open));
    btn.setAttribute("aria-label", open ? "隐藏侧栏" : "显示侧栏");
    btn.title = open ? "隐藏侧栏" : "显示侧栏";
    const icon = btn.querySelector("use");
    if (icon) icon.setAttribute("href", open ? "/icons.svg#chevron-right" : "/icons.svg#chevron-left");
  }
  if (view === "map") {
    requestAnimationFrame(() => drawMap());
    const onEnd = event => {
      if (event.propertyName && event.propertyName !== "grid-template-columns") return;
      drawMap();
    };
    ws.addEventListener("transitionend", onEnd, {once: true});
  }
}

function setCodexOpen(open) {
  state.codexOpen = open;
  const group = $("#codexGroup");
  const btn = $("#codexToggle");
  if (group) group.classList.toggle("open", open);
  if (btn) btn.setAttribute("aria-expanded", String(open));
}

function syncCodexActive() {
  const group = $("#codexGroup");
  if (group) group.classList.toggle("child-active", ["items", "npcs", "mobs"].includes(state.view));
}

function navigate(view) {
  state.view = view;
  $$(".nav-item").forEach(n => n.classList.toggle("active", n.dataset.view === view));
  $$(".view").forEach(n => n.classList.toggle("active", n.id === "view-" + view));
  syncCodexActive();
  if (["items", "npcs", "mobs"].includes(view) && !state.codexOpen) setCodexOpen(true);
  if (view === "map") loadMap().then(drawMap);
  if ((view === "npcs" || view === "mobs") && !state.npcMeta) api("/api/npc-meta").then(m => { state.npcMeta = m; });
  if (view === "npcs") loadNpcs("npc").catch(fail);
  if (view === "mobs") loadNpcs("monster").catch(fail);
  if (view === "items" && !state.itemMeta) api("/api/item-meta").then(m => { state.itemMeta = m; });
  syncListInspector(view);
}

function bind() {
  bindListChrome();
  $$(".nav-item").forEach(n => n.addEventListener("click", () => navigate(n.dataset.view)));
  $("#codexToggle").onclick = () => setCodexOpen(!state.codexOpen);
  syncCodexActive();
  $("#pendingToggle").onclick = () => setDrawer(!state.drawer);
  $("#pendingClear").onclick = async () => {
    if (!state.pending.length) return;
    const ok = await confirmAction({
      title: "清除全部记录",
      body: "只会清空行为列表，已经刷出的 NPC、改过的数据不会撤回。",
      ok: "清除"
    });
    if (!ok) return;
    try {
      await api("/api/pending/clear", {method: "POST", body: JSON.stringify({})});
      await loadPending();
      setDrawer(false);
    } catch (e) { fail(e); }
  };
  $$(".inspector-toggle").forEach(btn => btn.addEventListener("click", () => {
    const view = btn.closest(".workspace")?.dataset.inspector;
    if (!view) return;
    setInspector(view, !state.inspectors[view]);
  }));
  $("#charSearch").oninput = debounce(() => { state.charQuery = $("#charSearch").value.trim(); state.charPage = 0; loadChars().then(() => scrollListTop("charWrap")).catch(fail); });
  $("#charFilterBtn").onclick = e => {
    e.stopPropagation();
    setCharFilterOpen($("#charFilterPop").hidden);
  };
  $$("#charFilterPop .filter-nav button").forEach(b => b.onclick = () => {
    $$("#charFilterPop .filter-nav button").forEach(x => x.classList.remove("active"));
    b.classList.add("active");
    $$("#charFilterPop .filter-pane").forEach(p => { p.hidden = p.dataset.pane !== b.dataset.pane; });
  });
  $("#charFilterApply").onclick = applyCharFilter;
  $("#charFilterReset").onclick = () => {
    $("#fltOnline").checked = true;
    $("#fltOffline").checked = true;
    $$("input[name=fltJob]").forEach(r => { r.checked = r.value === ""; });
  };
  document.addEventListener("click", e => {
    if (!$(".filter-wrap")?.contains(e.target)) setCharFilterOpen(false);
  });
  $("#charPrev").onclick = () => { if (state.charPage > 0) { state.charPage--; renderChars(); scrollListTop("charWrap"); } };
  $("#charNext").onclick = () => { state.charPage++; renderChars(); scrollListTop("charWrap"); };
  $$(".insp-tabs button").forEach(b => b.onclick = () => setMapTab(b.dataset.tab));
  $("#gotoSearch").oninput = debounce(() => { state.gotoQuery = $("#gotoSearch").value.trim(); renderGotoList(); }, 120);
  markFilterBtn();
  $$("th[data-sort]").forEach(th => th.addEventListener("click", () => {
    const key = th.dataset.sort;
    state.charSort = state.charSort === "-" + key ? key : "-" + key;
    loadChars().catch(fail);
  }));
  $("#saveAccess").onclick = async () => {
    if (!state.pickedChar) return;
    try {
      const data = await api("/api/access", {method: "POST", body: JSON.stringify({characterId: state.pickedChar.id, level: Number($("#accessLevel").value) || 0})});
      toast(data.mode === "pending" ? "权限进待办，下线后写" : "权限已写库");
      loadChars(); loadPending();
    } catch (e) { fail(e); }
  };
  $("#saveStats").onclick = async () => {
    if (!state.pickedChar) return;
    try {
      const data = await api("/api/stats", {method: "POST", body: JSON.stringify({
        characterId: state.pickedChar.id, operatorId: operator()?.id,
        hp: Number($("#statHp").value), maxHp: Number($("#statMaxHp").value),
        mp: Number($("#statMp").value), maxMp: Number($("#statMaxMp").value)
      })});
      toast(data.mode === "pending" ? "血蓝进待办" : "已发送 .角色更改o");
      loadPending();
    } catch (e) { fail(e); }
  };
  $("#itemSearch").oninput = debounce(() => { state.itemQuery = $("#itemSearch").value.trim(); state.itemPage = 0; loadItems().catch(fail); });
  $$("#itemCats button").forEach(b => b.onclick = () => {
    $$("#itemCats button").forEach(x => x.classList.remove("active"));
    b.classList.add("active");
    state.itemCategory = b.dataset.category;
    state.itemPage = 0;
    loadItems().catch(fail);
  });
  $("#itemPrev").onclick = () => { if (state.itemPage > 0) { state.itemPage--; loadItems().then(() => scrollListTop("itemWrap")).catch(fail); } };
  $("#itemNext").onclick = () => { state.itemPage++; loadItems().then(() => scrollListTop("itemWrap")).catch(fail); };
  $("#newItemBtn").onclick = async () => {
    if (!state.itemMeta) state.itemMeta = await api("/api/item-meta");
    state.creatingItem = true;
    setInspector("items", true);
    renderItemInspector();
  };
  $("#npcSearch").oninput = debounce(() => { state.npcQuery = $("#npcSearch").value.trim(); state.npcPage = 0; loadNpcs("npc").catch(fail); });
  $("#npcPrev").onclick = () => { if (state.npcPage > 0) { state.npcPage--; loadNpcs("npc").then(() => scrollListTop("npcWrap")).catch(fail); } };
  $("#npcNext").onclick = () => { state.npcPage++; loadNpcs("npc").then(() => scrollListTop("npcWrap")).catch(fail); };
  $("#newNpcBtn").onclick = async () => {
    if (!state.npcMeta) state.npcMeta = await api("/api/npc-meta");
    state.creatingNpc = true;
    setInspector("npcs", true);
    renderNpcInspector("npc");
  };
  $("#mobSearch").oninput = debounce(() => { state.mobQuery = $("#mobSearch").value.trim(); state.mobPage = 0; loadNpcs("monster").catch(fail); });
  $("#mobPrev").onclick = () => { if (state.mobPage > 0) { state.mobPage--; loadNpcs("monster").then(() => scrollListTop("mobWrap")).catch(fail); } };
  $("#mobNext").onclick = () => { state.mobPage++; loadNpcs("monster").then(() => scrollListTop("mobWrap")).catch(fail); };
  $("#newMobBtn").onclick = async () => {
    if (!state.npcMeta) state.npcMeta = await api("/api/npc-meta");
    state.creatingMob = true;
    setInspector("mobs", true);
    renderNpcInspector("monster");
  };
  $("#mapSearch").oninput = debounce(() => { state.mapQuery = $("#mapSearch").value.trim(); loadMapCatalog().catch(fail); }, 180);
  $("#mapCellInput").addEventListener("keydown", e => {
    if (e.key === "Enter") {
      e.preventDefault();
      submitMapCoord();
    }
  });
  $("#mapCellInput").addEventListener("blur", () => {
    const v = $("#mapCellInput").value.trim();
    if (!v) return;
    if (state.mapClick && v === `${state.mapClick.map}:${state.mapClick.x},${state.mapClick.y}`) return;
    submitMapCoord();
  });
  $("#mapRefresh").onclick = () => refreshMaps();
  $("#mapCanvas").addEventListener("click", onMapClick);
  $("#mapCanvas").addEventListener("wheel", onMapWheel, {passive: false});
  $("#mapCanvas").addEventListener("pointerdown", onMapPointerDown);
  $("#mapCanvas").addEventListener("pointermove", onMapPointerMove);
  $("#mapCanvas").addEventListener("pointerup", onMapPointerUp);
  $("#mapCanvas").addEventListener("pointercancel", onMapPointerUp);
  $("#mapGo").onclick = async () => {
    if (!state.mapClick) return;
    try {
      const data = await api("/api/teleport", {method: "POST", body: JSON.stringify({
        x: state.mapClick.x, y: state.mapClick.y, map: state.mapId, operatorId: operator()?.id
      })});
      toast(data.command);
    } catch (e) { fail(e); }
  };
  window.addEventListener("resize", () => { if (state.view === "map") drawMap(); });
  const mapPane = document.querySelector(".map-pane");
  if (mapPane && window.ResizeObserver) {
    new ResizeObserver(() => { if (state.view === "map") drawMap(); }).observe(mapPane);
  }
}

async function boot() {
  bind();
  setDrawer(false);
  ["chars", "items", "npcs", "mobs"].forEach(v => setInspector(v, false));
  loadSavedSelection();
  await loadStatus();
  await Promise.all([loadChars(), loadItems(), loadNpcs("npc"), loadNpcs("monster"), loadPending()]);
  renderItemInspector();
  renderNpcInspector("npc");
  renderNpcInspector("monster");
  setInterval(async () => {
    await loadStatus(true);
    await loadChars().catch(() => {});
    await loadPending().catch(() => {});
    updateDisabled();
  }, 5000);
}

boot().catch(fail);
