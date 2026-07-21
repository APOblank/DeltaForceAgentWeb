const storedUser = JSON.parse(localStorage.getItem("delta-force-user") || "null");
const state = {
  user: storedUser,
  users: [],
  selectedUserId: "",
  selectedSessionId: "",
  databases: [],
  alertTimer: null,
};

const panelTitles = {
  overview: "控制台",
  content: "内容管理",
  users: "用户管理",
  database: "数据库管理",
  market: "市场数据",
};

const queryExamples = {
  labels: "CALL db.labels() YIELD label RETURN label ORDER BY label",
  nodes: "MATCH (n) RETURN n ORDER BY id(n) DESC LIMIT 25",
  users: "MATCH (u:AgentUser) RETURN u ORDER BY u.createdAt DESC LIMIT 50",
};

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;",
  })[char]);
}

function formatDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN", { hour12: false });
}

async function api(path, options = {}) {
  const token = state.user?.token;
  const response = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!response.ok) {
    if (response.status === 401) {
      localStorage.removeItem("delta-force-user");
      state.user = null;
      showGuard();
    }
    throw new Error(data?.message || data?.error || String(data || response.statusText));
  }
  return data;
}

function showAlert(message, kind = "success") {
  const alert = document.querySelector("#admin-alert");
  clearTimeout(state.alertTimer);
  alert.textContent = message;
  alert.className = `admin-alert ${kind}`;
  alert.hidden = false;
  state.alertTimer = setTimeout(() => { alert.hidden = true; }, 4200);
}

function showGuard() {
  document.querySelector("#admin-workspace").hidden = true;
  document.querySelector("#admin-guard").hidden = false;
}

function showWorkspace() {
  document.querySelector("#admin-guard").hidden = true;
  document.querySelector("#admin-workspace").hidden = false;
  document.querySelector("#admin-identity").textContent = state.user?.displayName || state.user?.username || "管理员";
}

function setPanel(panel, updateHash = true) {
  if (!panelTitles[panel]) panel = "overview";
  document.querySelectorAll("[data-panel-view]").forEach((item) => item.classList.toggle("active", item.dataset.panelView === panel));
  document.querySelectorAll("[data-panel]").forEach((item) => {
    const active = item.dataset.panel === panel;
    item.classList.toggle("active", active);
    active ? item.setAttribute("aria-current", "page") : item.removeAttribute("aria-current");
  });
  document.querySelector("#admin-title").textContent = panelTitles[panel];
  document.title = `${panelTitles[panel]} · 三角洲后台管理`;
  if (updateHash) history.replaceState(null, "", `#${panel}`);
  closeSidebar();
  if (panel === "content") loadContent();
  if (panel === "users") loadUsers();
  if (panel === "database") loadDatabases();
}

function openSidebar() {
  document.body.classList.add("sidebar-open");
  document.querySelector("#admin-menu").setAttribute("aria-expanded", "true");
  document.querySelector("#sidebar-scrim").hidden = false;
}

function closeSidebar() {
  document.body.classList.remove("sidebar-open");
  document.querySelector("#admin-menu").setAttribute("aria-expanded", "false");
  document.querySelector("#sidebar-scrim").hidden = true;
}

async function loadOverview() {
  const button = document.querySelector("#overview-refresh");
  button.disabled = true;
  try {
    const [users, databases, content] = await Promise.all([
      api("/api/admin/users"),
      api("/api/admin/neo4j/databases"),
      api("/api/admin/daily-content"),
    ]);
    state.users = users;
    state.databases = databases.databases || [];
    document.querySelector("#metric-users").textContent = users.length.toLocaleString("zh-CN");
    document.querySelector("#metric-sessions").textContent = users.reduce((sum, user) => sum + Number(user.sessionCount || 0), 0).toLocaleString("zh-CN");
    document.querySelector("#metric-databases").textContent = state.databases.length;
    document.querySelector("#metric-updated").textContent = content.updatedAt ? formatDate(content.updatedAt).split(" ")[0] : "未发布";
  } catch (error) {
    showAlert(error.message, "error");
  } finally {
    button.disabled = false;
  }
}

async function loadContent() {
  const form = document.querySelector("#content-form");
  if (!form || form.dataset.loading === "true") return;
  form.dataset.loading = "true";
  try {
    const content = await api("/api/admin/daily-content");
    const fields = {
      "github-url": "githubUrl", "password-az3": "passwordAz3", "password-zero-dam": "passwordZeroDam",
      "password-space-city": "passwordSpaceCity", "password-tide-prison": "passwordTidePrison",
      "password-brakkesh": "passwordBrakkesh", "password-longbow": "passwordLongbow",
      "ai-recommendation": "aiRecommendation", "ai-prompt": "aiPrompt", "news-content": "newsContent",
    };
    Object.entries(fields).forEach(([id, key]) => { document.querySelector(`#${id}`).value = content[key] || ""; });
    document.querySelector("#content-status").textContent = content.updatedAt ? `上次更新：${formatDate(content.updatedAt)}` : "尚未发布";
  } catch (error) {
    showAlert(error.message, "error");
  } finally {
    form.dataset.loading = "false";
  }
}

async function saveContent(event) {
  event.preventDefault();
  const button = event.submitter || event.currentTarget.querySelector("[type=submit]");
  const status = document.querySelector("#content-status");
  button.disabled = true;
  status.textContent = "正在保存…";
  const value = (id) => document.querySelector(`#${id}`).value.trim();
  const payload = {
    githubUrl: value("github-url"), passwordAz3: value("password-az3"), passwordZeroDam: value("password-zero-dam"),
    passwordSpaceCity: value("password-space-city"), passwordTidePrison: value("password-tide-prison"),
    passwordBrakkesh: value("password-brakkesh"), passwordLongbow: value("password-longbow"),
    aiRecommendation: value("ai-recommendation"), aiPrompt: value("ai-prompt"), newsContent: value("news-content"),
  };
  if (payload.githubUrl && !/^https?:\/\//i.test(payload.githubUrl)) {
    status.textContent = "GitHub 链接必须以 http:// 或 https:// 开头";
    status.className = "inline-status error";
    button.disabled = false;
    return;
  }
  try {
    const content = await api("/api/admin/daily-content", { method: "POST", body: JSON.stringify(payload) });
    status.textContent = `已保存：${formatDate(content.updatedAt)}`;
    status.className = "inline-status success";
    showAlert("今日内容已保存并发布");
  } catch (error) {
    status.textContent = error.message;
    status.className = "inline-status error";
  } finally {
    button.disabled = false;
  }
}

async function loadUsers() {
  const body = document.querySelector("#users-body");
  body.innerHTML = '<tr><td colspan="3">正在读取用户…</td></tr>';
  try {
    state.users = await api("/api/admin/users");
    renderUsers();
  } catch (error) {
    body.innerHTML = `<tr><td colspan="3">${escapeHtml(error.message)}</td></tr>`;
  }
}

function renderUsers() {
  const keyword = document.querySelector("#user-search").value.trim().toLowerCase();
  const users = state.users.filter((user) => `${user.username} ${user.displayName}`.toLowerCase().includes(keyword));
  document.querySelector("#users-body").innerHTML = users.length ? users.map((user) => `
    <tr data-user-id="${escapeHtml(user.userId)}" class="${state.selectedUserId === user.userId ? "selected" : ""}" tabindex="0">
      <td><strong>${escapeHtml(user.username)}</strong><small>${escapeHtml(user.displayName || user.userId)}</small></td>
      <td>${Number(user.sessionCount || 0)}</td><td>${escapeHtml(formatDate(user.lastLoginAt))}</td>
    </tr>`).join("") : '<tr><td colspan="3">没有匹配用户</td></tr>';
}

async function selectUser(userId) {
  state.selectedUserId = userId;
  state.selectedSessionId = "";
  renderUsers();
  const user = state.users.find((item) => item.userId === userId);
  document.querySelector("#selected-user-name").textContent = user?.displayName || user?.username || "用户会话";
  document.querySelector("#selected-session-title").textContent = "会话内容";
  document.querySelector("#session-list").innerHTML = '<p class="empty-state">正在读取会话…</p>';
  document.querySelector("#message-list").innerHTML = '<p class="empty-state">选择会话以查看消息。</p>';
  try {
    const sessions = await api(`/api/admin/users/${encodeURIComponent(userId)}/sessions?includeArchived=true`);
    document.querySelector("#session-list").innerHTML = sessions.length ? sessions.map((session) => `
      <button class="session-button" type="button" data-session-id="${escapeHtml(session.sessionId)}" data-session-title="${escapeHtml(session.title)}">
        <span><strong>${escapeHtml(session.title || "未命名会话")}</strong>${session.archived ? '<em class="archived-label">已归档</em>' : ""}</span>
        <small>${escapeHtml(formatDate(session.updatedAt))}</small>
      </button>`).join("") : '<p class="empty-state">该用户暂无会话。</p>';
  } catch (error) {
    document.querySelector("#session-list").innerHTML = `<p class="empty-state">${escapeHtml(error.message)}</p>`;
  }
}

async function selectSession(sessionId, title) {
  state.selectedSessionId = sessionId;
  document.querySelectorAll(".session-button").forEach((button) => button.classList.toggle("active", button.dataset.sessionId === sessionId));
  document.querySelector("#selected-session-title").textContent = title || "会话内容";
  const list = document.querySelector("#message-list");
  list.innerHTML = '<p class="empty-state">正在读取消息…</p>';
  try {
    const messages = await api(`/api/admin/users/${encodeURIComponent(state.selectedUserId)}/sessions/${encodeURIComponent(sessionId)}/messages`);
    list.innerHTML = messages.length ? messages.map((message) => `
      <article class="message-item ${escapeHtml(message.role)}"><div class="message-meta"><strong>${message.role === "user" ? "用户" : "智能体"}</strong><span>${escapeHtml(formatDate(message.createdAt))}</span></div><p class="message-content">${escapeHtml(message.content)}</p></article>`).join("") : '<p class="empty-state">该会话暂无消息。</p>';
  } catch (error) {
    list.innerHTML = `<p class="empty-state">${escapeHtml(error.message)}</p>`;
  }
}

async function loadDatabases() {
  try {
    const result = await api("/api/admin/neo4j/databases");
    state.databases = result.databases || [];
    const select = document.querySelector("#database-select");
    const current = select.value;
    select.innerHTML = state.databases.map((database) => `<option value="${escapeHtml(database)}">${escapeHtml(database)}</option>`).join("");
    if (state.databases.includes(current)) select.value = current;
  } catch (error) {
    showAlert(error.message, "error");
  }
}

function parseJsonField(selector, label) {
  try {
    const value = JSON.parse(document.querySelector(selector).value || "{}");
    if (!value || Array.isArray(value) || typeof value !== "object") throw new Error();
    return value;
  } catch {
    throw new Error(`${label}必须是有效的 JSON 对象`);
  }
}

async function executeQuery(payload, button) {
  button.disabled = true;
  document.querySelector("#query-summary").textContent = "正在执行…";
  try {
    const result = await api("/api/admin/neo4j/query", { method: "POST", body: JSON.stringify(payload) });
    renderQueryResult(result);
    showAlert(payload.write ? "数据库修改已执行" : "查询执行完成");
    return result;
  } catch (error) {
    document.querySelector("#query-summary").textContent = "执行失败";
    document.querySelector("#query-result").innerHTML = `<p class="result-empty">${escapeHtml(error.message)}</p>`;
    showAlert(error.message, "error");
    return null;
  } finally {
    button.disabled = false;
  }
}

function renderQueryResult(result) {
  const summary = result.summary || {};
  const updates = summary.containsUpdates ? ` · 属性 ${summary.propertiesSet || 0} · 新建节点 ${summary.nodesCreated || 0}` : "";
  document.querySelector("#query-summary").textContent = `${result.database} · ${result.mode === "write" ? "写入" : "只读"} · ${result.rowCount} 行${result.truncated ? "（已截断）" : ""}${updates}`;
  if (!result.columns?.length) {
    document.querySelector("#query-result").innerHTML = '<p class="result-empty">语句执行成功，没有返回列。</p>';
    return;
  }
  const cell = (value) => typeof value === "string" ? value : JSON.stringify(value, null, 2);
  document.querySelector("#query-result").innerHTML = `<div class="result-scroll"><table class="result-table"><thead><tr>${result.columns.map((column) => `<th>${escapeHtml(column)}</th>`).join("")}</tr></thead><tbody>${result.rows.map((row) => `<tr>${result.columns.map((column) => `<td>${escapeHtml(cell(row[column]))}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
}

async function submitCypher(event) {
  event.preventDefault();
  const write = document.querySelector("#write-mode").checked;
  let parameters;
  try { parameters = parseJsonField("#cypher-parameters", "查询参数"); } catch (error) { showAlert(error.message, "error"); return; }
  if (write && !window.confirm("确认以写入模式执行此 Cypher？数据库内容可能发生不可逆变化。")) return;
  await executeQuery({ database: document.querySelector("#database-select").value, query: document.querySelector("#cypher-query").value, parameters, write, limit: Number(document.querySelector("#query-limit").value || 100) }, document.querySelector("#query-submit"));
}

async function updateNode(event) {
  event.preventDefault();
  let properties;
  try { properties = parseJsonField("#node-properties", "节点属性"); } catch (error) { showAlert(error.message, "error"); return; }
  const nodeId = Number(document.querySelector("#node-id").value);
  if (!Number.isInteger(nodeId) || nodeId < 0) { showAlert("请输入有效的节点 ID", "error"); return; }
  if (!window.confirm(`确认更新节点 #${nodeId} 的属性？`)) return;
  const button = event.submitter || event.currentTarget.querySelector("button[type=submit]");
  const result = await executeQuery({
    database: document.querySelector("#database-select").value,
    query: "MATCH (n) WHERE id(n) = $nodeId SET n += $properties RETURN n",
    parameters: { nodeId, properties }, write: true, limit: 1,
  }, button);
  if (result) document.querySelector("#cypher-query").value = `MATCH (n) WHERE id(n) = ${nodeId} RETURN n`;
}

async function refreshMarket(event) {
  event.preventDefault();
  const button = document.querySelector("#market-submit");
  const output = document.querySelector("#market-result");
  const payload = {};
  const kind = document.querySelector("#refresh-kind").value;
  const pages = Number(document.querySelector("#refresh-pages").value);
  if (kind) payload.kind = kind;
  if (pages > 0) payload.max_pages = pages;
  if (document.querySelector("#refresh-save-partial").checked) payload.save_partial = true;
  button.disabled = true;
  output.textContent = "正在刷新，请勿关闭页面…";
  try {
    const result = await api("/api/admin/market/refresh", { method: "POST", body: JSON.stringify(payload) });
    output.textContent = JSON.stringify(result, null, 2);
    showAlert("市场数据刷新完成");
  } catch (error) {
    output.textContent = error.message;
    showAlert(error.message, "error");
  } finally {
    button.disabled = false;
  }
}

document.querySelectorAll("[data-panel]").forEach((button) => button.addEventListener("click", () => setPanel(button.dataset.panel)));
document.querySelectorAll("[data-jump]").forEach((button) => button.addEventListener("click", () => setPanel(button.dataset.jump)));
document.querySelector("#overview-refresh").addEventListener("click", loadOverview);
document.querySelector("#content-form").addEventListener("submit", saveContent);
document.querySelector("#content-reload").addEventListener("click", loadContent);
document.querySelector("#users-refresh").addEventListener("click", loadUsers);
document.querySelector("#user-search").addEventListener("input", renderUsers);
document.querySelector("#users-body").addEventListener("click", (event) => { const row = event.target.closest("[data-user-id]"); if (row) selectUser(row.dataset.userId); });
document.querySelector("#users-body").addEventListener("keydown", (event) => { const row = event.target.closest("[data-user-id]"); if (row && ["Enter", " "].includes(event.key)) { event.preventDefault(); selectUser(row.dataset.userId); } });
document.querySelector("#session-list").addEventListener("click", (event) => { const button = event.target.closest("[data-session-id]"); if (button) selectSession(button.dataset.sessionId, button.dataset.sessionTitle); });
document.querySelector("#cypher-form").addEventListener("submit", submitCypher);
document.querySelector("#node-editor-form").addEventListener("submit", updateNode);
document.querySelector("#market-form").addEventListener("submit", refreshMarket);
document.querySelector("#write-mode").addEventListener("change", (event) => {
  const button = document.querySelector("#query-submit");
  button.textContent = event.target.checked ? "执行写入语句" : "执行只读查询";
  button.className = event.target.checked ? "danger-button" : "primary-button";
});
document.querySelectorAll("[data-query-example]").forEach((button) => button.addEventListener("click", () => {
  document.querySelector("#cypher-query").value = queryExamples[button.dataset.queryExample];
  document.querySelector("#write-mode").checked = false;
  document.querySelector("#write-mode").dispatchEvent(new Event("change"));
}));
document.querySelector("#admin-menu").addEventListener("click", () => document.body.classList.contains("sidebar-open") ? closeSidebar() : openSidebar());
document.querySelector("#sidebar-scrim").addEventListener("click", closeSidebar);
document.querySelector("#admin-logout").addEventListener("click", () => { localStorage.removeItem("delta-force-user"); location.href = "/#/login"; });
document.addEventListener("keydown", (event) => { if (event.key === "Escape") closeSidebar(); });

if (!state.user || state.user.username !== "admin" || !state.user.token) {
  showGuard();
} else {
  showWorkspace();
  const initialPanel = location.hash.slice(1);
  setPanel(panelTitles[initialPanel] ? initialPanel : "overview", false);
  loadOverview();
}
