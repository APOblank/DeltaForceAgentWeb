const view = document.querySelector("#app-view");
const title = document.querySelector("#page-title");
const appShell = document.querySelector(".app-shell");
const sidebar = document.querySelector(".sidebar");
const menuButton = document.querySelector(".menu-button");
const sidebarCollapseButton = document.querySelector("#sidebar-collapse-button");
const topActions = document.querySelector("#top-actions");
const sidebarBackdrop = document.querySelector(".sidebar-backdrop");
const API_BASE = "";

function readSessionJson(key) {
  try { return JSON.parse(sessionStorage.getItem(key) || "null"); }
  catch { sessionStorage.removeItem(key); return null; }
}

// Older versions persisted bearer tokens indefinitely. Remove that legacy copy.
localStorage.removeItem("delta-force-user");

const state = {
  user: readSessionJson("delta-force-user"),
  activeSessionId: sessionStorage.getItem("delta-force-session") || "",
  sessions: [],
  showArchivedSessions: false,
  githubUrl: "",
  locale: "zh-CN",
  theme: localStorage.getItem("delta-force-theme") || "light",
  marketItems: [],
  marketPage: 1,
  reactEvents: [],
  selectedReactEventId: "",
  selectedReactMessageId: "",
  messageReactEvents: {},
  pendingChats: {},
  conversation: { activeTaskId: "", taskStatus: "" },
  nextConversationMode: "auto",
  chatSending: false,
  modifierFirearms: [],
  modifierCurrent: null,
  modifierSelected: {},
  modifierExpandedSlot: "",
  modifierSearch: "",
  gunModReplies: {},
  gunModImageCatalog: [],
  gunModImageCatalogReady: false,
  gunModImageCatalogPromise: null,
  dataCenterTab: "overview",
  dataCenterPage: 1,
  dataCenterSize: 20,
  dataCenterSummary: null,
  dataCenterFilters: { map: "", result: "", start: "", end: "" },
};

let mobileChatMediaQuery = null;

const pageTitles = {
  "/": "三角洲行动智能体",
  "/chat": "智能对话",
  "/market": "市场数据查看",
  "/data-center": "个人数据中心",
  "/gun-modifier": "改枪台",
  "/knowledge": "知识图谱查询",
  "/readiness": "卡战备",
  "/news": "今日资讯",
  "/admin": "管理后台",
  "/login": "账号登录",
};

const pageTitlesEn = {
  "/": "Delta Force Agent",
  "/chat": "AI Chat",
  "/market": "Market Data",
  "/data-center": "Player Data Center",
  "/gun-modifier": "Gun Bench",
  "/knowledge": "Knowledge Graph",
  "/readiness": "Readiness Builder",
  "/news": "Daily Intel",
  "/admin": "Admin Console",
  "/login": "Account Login",
};

const publicRoutes = new Set(["/", "/news", "/login"]);

const mockMarketItems = [
  { name: "0.0", type: "枪械", price: 0, change: 0.0 },

];

const MARKET_PAGE_SIZE = 10;

function api(path, options = {}) {
  const token = state.user?.token;
  const authorization = token && !path.startsWith("/api/auth/") ? { Authorization: `Bearer ${token}` } : {};
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...authorization,
      ...(options.headers || {}),
    },
  }).then(async (response) => {
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); }
      catch { data = { message: "服务返回了无法识别的响应" }; }
    }
    if (!response.ok) {
      if (response.status === 401 && state.user) {
        setUser(null);
        location.hash = "#/login";
      }
      const retry = response.headers.get("Retry-After");
      const requestId = response.headers.get("X-Request-ID") || data?.requestId;
      const suffix = requestId ? `（请求编号 ${requestId}）` : "";
      const retryMessage = response.status === 429 && retry ? `，请在 ${retry} 秒后重试` : "";
      throw new Error(`${data?.message || data?.error || response.statusText}${retryMessage}${suffix}`);
    }
    return data;
  });
}

function money(value) {
  return Number(value || 0).toLocaleString("zh-CN");
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#039;",
  })[char]);
}

function itemImageUrl(item) {
  const type = String(item?.type || "").toLowerCase();
  const imageFolder = ["firearm", "firearms"].includes(type) ? "firearm-images" : ["attachment", "attachments"].includes(type) ? "attachment-images" : "";
  if (!imageFolder) return "";
  const imageName = String(item?.name || "").replace(/[\/／*＊]/g, "_");
  return `${API_BASE}/${imageFolder}/${encodeURIComponent(imageName)}.png`;
}

function itemImageMarkup(item) {
  const type = String(item?.type || "").toLowerCase();
  if (!["attachment", "attachments", "firearm", "firearms"].includes(type)) return "";
  const name = escapeHtml(item.name || "物品");
  const src = escapeHtml(itemImageUrl(item));
  return `<span class="attachment-thumbnail"><img src="${src}" alt="${name}" loading="lazy" decoding="async" draggable="false" /></span>`;
}

function attachmentImageMarkup(item) {
  return itemImageMarkup({ ...item, type: "attachment" });
}

function dailyLoadoutMarkup(recommendation, attachments) {
  const text = String(recommendation || "今日 AI 改枪推荐待发布");
  return `<div class="daily-loadout-lines">${text.split(/\r?\n/).map((line, index) => dailyLoadoutLineMarkup(line, attachments, index === 0)).join("")}</div>`;
}

function dailyLoadoutLineMarkup(line, attachments, isFirearmLine = false) {
  const asciiColon = line.indexOf(":");
  const chineseColon = line.indexOf("：");
  const colonIndex = asciiColon < 0 ? chineseColon : chineseColon < 0 ? asciiColon : Math.min(asciiColon, chineseColon);
  const parenthesisIndex = line.indexOf("（", colonIndex + 1);
  if (isFirearmLine && colonIndex >= 0) {
    const firearmName = line.slice(0, colonIndex).trim();
    const firearm = (attachments || []).find((item) => ["firearm", "firearms"].includes(String(item.type || "").toLowerCase()) && modifierKey(item.name) === modifierKey(firearmName));
    if (firearm) {
      const afterName = escapeHtml(line.slice(colonIndex));
      return `<p class="daily-loadout-line"><span class="daily-inline-attachment daily-inline-firearm">${itemImageMarkup(firearm)}<span>${escapeHtml(firearmName)}</span></span>${afterName}</p>`;
    }
  }
  if (colonIndex < 0 || parenthesisIndex < 0) return `<p class="daily-loadout-line">${escapeHtml(line) || "&nbsp;"}</p>`;

  const attachmentName = line.slice(colonIndex + 1, parenthesisIndex).trim();
  const attachment = (attachments || []).find((item) => modifierKey(item.name) === modifierKey(attachmentName));
  if (!attachment) return `<p class="daily-loadout-line">${escapeHtml(line)}</p>`;

  const beforeName = escapeHtml(line.slice(0, colonIndex + 1));
  const afterName = escapeHtml(line.slice(parenthesisIndex));
  return `<p class="daily-loadout-line">${beforeName}<span class="daily-inline-attachment">${itemImageMarkup(attachment)}<span>${escapeHtml(attachmentName)}</span></span>${afterName}</p>`;
}

function setActive(route) {
  document.querySelectorAll(".nav-item").forEach((item) => {
    const active = item.dataset.route === route;
    item.classList.toggle("active", active);
    if (active) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
  title.textContent = pageTitle(route);
  document.title = route === "/" ? "三角洲行动智能体" : `${pageTitle(route)} · 三角洲行动智能体`;
}

function pageTitle(route) {
  return pageTitles[route] || pageTitles["/"];
}

function setUser(user) {
  state.user = user;
  if (user) {
    sessionStorage.setItem("delta-force-user", JSON.stringify(user));
  } else {
    sessionStorage.removeItem("delta-force-user");
    sessionStorage.removeItem("delta-force-session");
    state.activeSessionId = "";
    state.sessions = [];
  }
  renderTopActions();
}

function isAdminUser(user = state.user) {
  return user?.role === "ADMIN" || user?.username?.toLowerCase() === "admin";
}

function renderTopActions() {
  renderSidebarUser();
  syncSidebarLabels();
  const adminNav = document.querySelector('[data-admin-link]');
  if (adminNav) adminNav.hidden = !isAdminUser();
  if (!state.user) {
    topActions.innerHTML = topToolsMarkup();
    bindTopTools();
    return;
  }
  topActions.innerHTML = `
    <div class="user-pill">
      <span class="avatar small">${escapeHtml(state.user.displayName?.slice(0, 1) || "用")}</span>
      <span>${escapeHtml(state.user.displayName || state.user.username)}</span>
      <button class="chip-button" id="logout-button" type="button">退出</button>
    </div>
    ${topToolsMarkup()}
  `;
  bindTopTools();
  document.querySelector("#logout-button")?.addEventListener("click", () => {
    logout();
  });
}

function topToolsMarkup() {
  const githubUrl = safeExternalUrl(state.githubUrl);
  const github = githubUrl
    ? `<a class="header-github-link" href="${escapeHtml(githubUrl)}" target="_blank" rel="noopener noreferrer"><img src="/github.svg" alt="GitHub" width="16" height="16" />GitHub</a>`
    : `<span class="header-github-link is-disabled" title="${state.locale === "en" ? "Configure GitHub URL in Admin" : "请在管理后台配置 GitHub 链接"}"><img src="/github.svg" alt="GitHub" width="16" height="16" />GitHub</span>`;
  const themeTitle = state.theme === "dark" ? "Light Mode" : "Dark Mode";
  const themeIcon = state.theme === "dark"
    ? `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a6.8 6.8 0 0 0 9 9A9 9 0 1 1 12 3Z"></path></svg>`
    : `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="m4.93 4.93 1.41 1.41"></path><path d="m17.66 17.66 1.41 1.41"></path><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="m6.34 17.66-1.41 1.41"></path><path d="m19.07 4.93-1.41 1.41"></path></svg>`;
  return `<div class="top-tool-group" role="toolbar" aria-label="页面工具">${github}<span class="top-tools-divider" aria-hidden="true"></span><button id="theme-toggle" class="top-icon-button" type="button" title="${themeTitle}" aria-label="${themeTitle}"><span class="top-icon-button-content">${themeIcon}</span><span class="top-icon-button-hover" aria-hidden="true"></span></button></div>`;
}

function bindTopTools() {
  document.querySelector("#theme-toggle")?.addEventListener("click", () => {
    state.theme = state.theme === "dark" ? "light" : "dark";
    localStorage.setItem("delta-force-theme", state.theme);
    applyTheme();
    renderTopActions();
  });
}

function applyTheme() {
  document.documentElement.dataset.theme = state.theme;
  document.documentElement.style.colorScheme = state.theme;
}

function applyLocale() {
  document.documentElement.lang = "zh-CN";
  const labels = { "/": "首页", "/chat": "智能对话", "/market": "市场数据", "/data-center": "数据中心", "/gun-modifier": "改枪台", "/knowledge": "图谱查询", "/readiness": "卡战备", "/news": "今日资讯", "/admin": "管理后台" };
  document.querySelectorAll(".nav-item").forEach((item) => {
    const text = item.querySelectorAll("span")[1];
    if (text && labels[item.dataset.route]) text.textContent = labels[item.dataset.route];
  });
  const brand = document.querySelector(".brand strong");
  if (brand) brand.textContent = "三角洲行动智能体";
  syncSidebarLabels();
  applySidebarState();
}

function syncSidebarLabels() {
  document.querySelectorAll(".nav-item").forEach((item) => {
    const label = item.querySelectorAll("span")[1]?.textContent.trim();
    if (label) updateSidebarTooltip(item, label);
  });
  const loginButton = document.querySelector(".sidebar-login-button");
  if (loginButton) updateSidebarTooltip(loginButton, state.locale === "en" ? "Sign in" : "\u767b\u5f55");
}

function updateSidebarTooltip(control, label) {
  let tooltip = control.querySelector(".sidebar-tooltip");
  if (!tooltip) {
    tooltip = document.createElement("span");
    tooltip.className = "sidebar-tooltip";
    tooltip.setAttribute("aria-hidden", "true");
    control.append(tooltip);
  }
  tooltip.textContent = label;
}

function applySidebarState(collapsed = localStorage.getItem("delta-force-sidebar-collapsed") !== "false") {
  appShell.classList.toggle("sidebar-collapsed", collapsed);
  const label = collapsed
    ? (state.locale === "en" ? "Expand sidebar" : "\u5c55\u5f00\u4fa7\u8fb9\u680f")
    : (state.locale === "en" ? "Collapse sidebar" : "\u6536\u8d77\u4fa7\u8fb9\u680f");
  sidebarCollapseButton.setAttribute("aria-expanded", String(!collapsed));
  sidebarCollapseButton.setAttribute("aria-label", label);
  sidebarCollapseButton.title = label;
}

function safeExternalUrl(value) {
  try {
    const url = new URL(String(value || "").trim());
    return ["http:", "https:"].includes(url.protocol) ? url.href : "";
  } catch {
    return "";
  }
}

function githubLinkMarkup() {
  const url = safeExternalUrl(state.githubUrl);
  return url ? `<a class="ghost-button github-link" href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">GitHub</a>` : "";
}

function loadSiteConfig() {
  return api("/api/daily-content").then((content) => {
    state.githubUrl = content.githubUrl || "";
    renderTopActions();
  }).catch(() => {});
}

function logout() {
  setUser(null);
  location.hash = "#/login";
}

function renderSidebarUser() {
  const area = document.querySelector("#sidebar-user-area");
  if (!area) return;
  if (!state.user) {
    area.innerHTML = `<a class="sidebar-login-button" href="#/login"><span class="sidebar-user-avatar">&#30331;</span><span>${state.locale === "en" ? "Sign in" : "登录"}</span><span class="sidebar-login-arrow" aria-hidden="true">→</span></a>`;
    return;
  }
  const displayName = state.user.displayName || state.user.username;
  const accountName = state.user.username || displayName;
  area.innerHTML = `
    <div id="sidebar-user-card" class="sidebar-user-card" tabindex="0" title="&#21491;&#38190;&#25110;&#28857;&#20987;&#19977;&#28857;&#26597;&#30475;&#29992;&#25143;&#20449;&#24687;">
      <span class="sidebar-user-avatar">${escapeHtml(displayName.slice(0, 1) || "U")}</span>
      <span class="sidebar-user-name" title="${escapeHtml(accountName)}">${escapeHtml(accountName)}</span>
      <button id="sidebar-user-more" class="sidebar-user-more" type="button" aria-label="&#29992;&#25143;&#25805;&#20316;" aria-expanded="false">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><circle cx="3.4" cy="8" r="1.15" fill="currentColor"></circle><circle cx="8" cy="8" r="1.15" fill="currentColor"></circle><circle cx="12.6" cy="8" r="1.15" fill="currentColor"></circle></svg>
      </button>
    </div>
    <div id="sidebar-user-menu" class="sidebar-user-menu" hidden>
      <button id="sidebar-logout-button" class="ghost-button" type="button">&#36864;&#20986;&#30331;&#24405;</button>
    </div>
  `;
  const card = document.querySelector("#sidebar-user-card");
  const menu = document.querySelector("#sidebar-user-menu");
  const moreButton = document.querySelector("#sidebar-user-more");
  const toggleMenu = () => {
    menu.hidden = !menu.hidden;
    moreButton.setAttribute("aria-expanded", String(!menu.hidden));
  };
  card?.addEventListener("contextmenu", (event) => {
    event.preventDefault();
    toggleMenu();
  });
  card?.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      toggleMenu();
    }
  });
  moreButton?.addEventListener("click", (event) => {
    event.stopPropagation();
    toggleMenu();
  });
  document.querySelector("#sidebar-logout-button")?.addEventListener("click", logout);
}

function metricLine(label, value) {
  return `
    <div class="intel-line">
      <span>${label}</span>
      <div class="bar"><span style="width:${value}%"></span></div>
      <strong>${value}%</strong>
    </div>
  `;
}

function homePage() {
  return `
    <div class="tac-hero">
      <div class="tac-hero-bg">
        <div class="tac-hero-grid"></div>
        <div class="tac-hero-glow tac-glow-1"></div>
        <div class="tac-hero-glow tac-glow-2"></div>
        <div class="tac-hero-glow tac-glow-3"></div>
        <div class="tac-hero-corner tac-tl"><span class="tac-dot"></span><span>SYSTEM ONLINE</span></div>
        <div class="tac-hero-corner tac-tr"><span>DF-AGENT // TACTICAL OPS</span><div class="tac-hlines"><i></i><i></i><i></i></div></div>
        <div class="tac-hero-watermark">DELTA FORCE</div>
      </div>
      <div class="tac-hero-content">
        <div class="tac-hero-badge">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2 2 7l10 5 10-5-10-5Z"/><path d="m2 17 10 5 10-5"/><path d="m2 12 10 5 10-5"/></svg>
          <span>DELTA FORCE TACTICAL INTELLIGENCE</span>
        </div>
        <h1 class="tac-hero-title">
          <span class="tac-hero-title-main">三角洲行动</span>
          <span class="tac-hero-title-sub">智能体</span>
        </h1>
        <p class="tac-hero-desc">像 ChatGPT 一样的三角洲 AI Bot — 支持根据你的需求改枪、市场行情提问、知识图谱问答、个人战绩分析。AI 驱动，实时响应。</p>
        <div class="tac-hero-actions">
          <a class="tac-btn-signal" href="#/chat">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 5h16v12H8l-4 3V5Z"/><path d="M8 9h8"/><path d="M8 13h5"/></svg>
            开始对话
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>
          </a>
          <a class="tac-btn-ghost" href="#/gun-modifier">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 16 16 4l4 4L8 20H4v-4Z"/><path d="m13 7 4 4"/></svg>
            改枪台
          </a>
          <a class="tac-btn-ghost" href="#/market">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19 10 13l4 3 6-9"/><path d="M15 7h5v5"/></svg>
            市场数据
          </a>
        </div>
      </div>
    </div>

    <section class="tac-modules">
      <div class="tac-section-head"><h2>核心模块</h2><div class="tac-section-line"></div></div>
      <div class="tac-module-grid">
        <a class="tac-card tac-card-wide" href="#/chat">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 5h16v12H8l-4 3V5Z"/><path d="M8 9h8"/><path d="M8 13h5"/></svg></div>
          <h3>智能对话</h3>
          <span class="tac-card-tag">AI 驱动</span>
          <p>像 ChatGPT 一样对话，支持多轮会话、任务拆解。ReAct 引擎自动调用工具返回精准结果，复杂问题得到清晰、可追踪的回答。</p>
        </a>
        <a class="tac-card" href="#/market">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19 10 13l4 3 6-9"/><path d="M15 7h5v5"/></svg></div>
          <h3>市场情报</h3>
          <span class="tac-card-tag">实时行情</span>
          <p>追踪物品行情、战备价与均价变化趋势，快速识别交易机会。</p>
        </a>
        <a class="tac-card" href="#/gun-modifier">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 16 16 4l4 4L8 20H4v-4Z"/><path d="m13 7 4 4"/></svg></div>
          <h3>改枪台</h3>
          <span class="tac-card-tag">一键配装</span>
          <p>自己修改改枪方案，支持对话方案导入。</p>
        </a>
        <a class="tac-card" href="#/knowledge">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><circle cx="6" cy="12" r="3"/><circle cx="18" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><path d="m9 11 6-4"/><path d="m9 13 6 4"/></svg></div>
          <h3>战术图谱</h3>
          <span class="tac-card-tag">图谱查询</span>
          <p>在图谱数据库中搜索枪械、配件与属性关系，可视化关系网络。</p>
        </a>
        <a class="tac-card tac-card-wide" href="#/data-center">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19V9"/><path d="M10 19V5"/><path d="M16 19v-7"/><path d="M22 19V3"/><path d="M2 19h22"/></svg></div>
          <h3>数据中心</h3>
          <span class="tac-card-tag">战绩分析</span>
          <p>个人数据中心 — 查看对局记录、胜率、击杀数据、地图表现，支持多维度筛选与统计分析。</p>
        </a>
        <a class="tac-card" href="#/readiness">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/></svg></div>
          <h3>卡战备</h3>
          <span class="tac-card-tag">战备检查</span>
          <p>基于实时行情生成总花费最低的装备组合方案。</p>
        </a>
        <a class="tac-card" href="#/news">
          <div class="tac-card-icon"><svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 4h14v16H5z"/><path d="M8 8h8"/><path d="M8 12h8"/><path d="M8 16h5"/></svg></div>
          <h3>今日资讯</h3>
          <span class="tac-card-tag">每日更新</span>
          <p>地图密码、AI 改枪推荐与社区热点，每日更新不遗漏。</p>
        </a>
      </div>
    </section>

    <section class="tac-arch">
      <div class="tac-arch-grid">
        <div class="tac-arch-text">
          <span class="tac-arch-label">ARCHITECTURE</span>
          <h2>AI 驱动的战术架构</h2>
          <p>采用 ReAct（推理 + 行动）架构，AI 在对话中自动拆解任务、调用工具、观察结果并迭代推理。从市场查询到改枪方案，从图谱搜索到战绩分析，一切都在一次对话中完成。</p>
          <p>底层接入 Neo4j 知识图谱、实时市场数据 API 与 LLM 推理引擎，为三角洲行动玩家提供全方位的数据智能服务。</p>
        </div>
        <div class="tac-terminal">
          <div class="tac-terminal-head"><span>SYSTEM_LOGS</span><div class="tac-dots"><i></i><i></i><i></i></div></div>
          <div class="tac-terminal-body">
            <p class="tg">&gt; Initializing ReAct Engine...</p>
            <p class="tm">&gt; Loading tools: MarketAPI, GunBuilder, KnowledgeGraph</p>
            <p class="tg">&gt; Connecting to Neo4j database...</p>
            <p class="tw">&gt; QUERY: 查询 AKM 最优改枪方案</p>
            <p class="tm">&gt; Thinking... decomposing task into 3 steps</p>
            <p class="tg">&gt; Tool call: MarketAPI.getFirearm("AKM")</p>
            <p class="tg">&gt; Tool call: GunBuilder.optimize(budget=50000)</p>
            <p class="tm">&gt; Efficiency: +280% vs manual search</p>
            <p class="tw">&gt; Status: OPERATIONAL</p>
            <div class="tac-cursor"></div>
          </div>
        </div>
      </div>
    </section>
  `;
}

function defaultChatMessages() {
  return `
    <div class="message">
      <div class="avatar">Δ</div>
      <div class="bubble">我是三角洲行动智能体。你可以问我物资价格、装备属性、或者让我按预算生成改枪方案。</div>
    </div>
  `;
}

function chatPage() {
  return `
    <div class="chat-layout">
      <aside class="panel chat-list mobile-chat-panel" data-mobile-chat-panel>
        <button class="mobile-chat-panel-toggle" type="button" aria-expanded="false" aria-controls="history-panel-content">
          <span class="mobile-chat-panel-label">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h10M4 18h7"></path></svg>
            <span><strong>历史记录</strong><small id="history-panel-summary">点击展开会话列表</small></span>
          </span>
          <svg class="mobile-chat-panel-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4"></path></svg>
        </button>
        <div class="mobile-chat-panel-content chat-list-content" id="history-panel-content">
          <div class="session-header">
            <h3>历史记录</h3>
            <button class="chip-button" id="new-session-button" type="button">新建</button>
          </div>
          <button id="archive-toggle-button" class="chip-button session-archive-toggle" type="button">查看归档</button>
          <div id="session-list">
            ${state.user ? `<div class="empty-state compact">正在读取会话...</div>` : `<div class="empty-state compact">登录后保存和读取历史对话</div>`}
          </div>
        </div>
      </aside>
      <section class="panel chat-main">
        <div class="messages" id="messages">${defaultChatMessages()}</div>
        <form class="composer" id="chat-form">
          <button class="tool-button" type="button" title="添加工具">＋</button>
          <input id="chat-input" placeholder="询问价格、装备、图谱知识或改枪方案..." autocomplete="off" ${state.chatSending ? "disabled" : ""} />
          <button class="primary-button" type="submit" ${state.chatSending ? "disabled" : ""}>发送</button>
        </form>
      </section>
      <aside class="panel react-panel mobile-chat-panel" data-mobile-chat-panel>
        <button class="mobile-chat-panel-toggle" type="button" aria-expanded="false" aria-controls="react-panel-content">
          <span class="mobile-chat-panel-label">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h6v6H5zM13 13h6v6h-6zM14 5h5v5M5 14h5v5"></path></svg>
            <span><strong>执行过程</strong><small id="react-panel-summary">点击查看任务执行详情</small></span>
          </span>
          <svg class="mobile-chat-panel-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4"></path></svg>
        </button>
        <div class="mobile-chat-panel-content react-panel-content" id="react-panel-content">
          <div class="react-head">
            <div>
              <span class="badge blue">ReAct</span>
              <h3>执行过程</h3>
              <span id="conversation-status" class="conversation-status" hidden></span>
            </div>
            <button class="chip-button" id="export-react-button" type="button">导出</button>
          </div>
          <div class="react-timeline" id="react-timeline">
            <div class="empty-state compact">发送问题后展示任务拆解、工具调用和观察结果。</div>
          </div>
          <div class="react-detail" id="react-detail">
            <div class="empty-state compact">点击事件查看 payload JSON。</div>
          </div>
        </div>
      </aside>
    </div>
  `;
}

function renderSessionList() {
  const container = document.querySelector("#session-list");
  const summary = document.querySelector("#history-panel-summary");
  if (!container) return;
  if (!state.user) {
    if (summary) summary.textContent = "登录后查看历史会话";
    container.innerHTML = `<div class="empty-state compact">登录后保存和读取历史对话</div>`;
    return;
  }
  if (!state.sessions.length) {
    if (summary) summary.textContent = state.showArchivedSessions ? "暂无归档会话" : "暂无历史会话";
    container.innerHTML = `<div class="empty-state compact">暂无历史记录</div>`;
    return;
  }
  if (summary) summary.textContent = `${state.sessions.length} 条${state.showArchivedSessions ? "归档" : "历史"}会话`;
  container.innerHTML = state.sessions.map((session) => `
    <article class="session-item ${session.sessionId === state.activeSessionId ? "active" : ""} ${state.pendingChats[session.sessionId]?.status === "running" ? "pending" : ""}" data-session-id="${session.sessionId}">
      <strong>${escapeHtml(session.title || "新的对话")}</strong>
      <small>${state.pendingChats[session.sessionId]?.status === "running" ? "正在思考…" : escapeHtml((session.updatedAt || "").replace("T", " ").slice(0, 16))}</small>
      <button class="session-more-button" type="button" data-session-options="${session.sessionId}" aria-label="对话操作">···</button>
      <div class="session-action-menu" data-session-menu="${session.sessionId}" hidden>
        <button type="button" data-session-action="${session.archived ? "restore" : "archive"}" data-session-action-id="${session.sessionId}">${session.archived ? "恢复" : "归档"}</button>
        <button class="danger" type="button" data-session-action="delete" data-session-action-id="${session.sessionId}">删除</button>
      </div>
    </article>
  `).join("");
  container.querySelectorAll("[data-session-id]").forEach((item) => {
    item.addEventListener("click", () => loadSessionMessages(item.dataset.sessionId));
  });
  container.querySelectorAll("[data-session-options]").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      const menu = container.querySelector(`[data-session-menu="${button.dataset.sessionOptions}"]`);
      const opening = menu.hidden;
      container.querySelectorAll("[data-session-menu]").forEach((item) => { item.hidden = true; });
      menu.hidden = !opening;
    });
  });
  container.querySelectorAll("[data-session-action]").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      const { sessionAction: action, sessionActionId: sessionId } = button.dataset;
      if (action === "delete") {
        const session = state.sessions.find((item) => item.sessionId === sessionId);
        if (window.confirm(`确定删除“${session?.title || "该对话"}”吗？此操作不可恢复。`)) deleteChatSession(sessionId);
      } else {
        setChatSessionArchived(sessionId, action === "archive");
      }
    });
  });
}

function setChatSessionArchived(sessionId, archived) {
  if (!state.user || !sessionId) return;
  api(`/api/chat/sessions/${encodeURIComponent(sessionId)}/archive?archived=${archived}`, { method: "PATCH" })
    .then((result) => {
      if (!result?.archived) throw new Error("操作失败。");
      if (archived && state.activeSessionId === sessionId) {
        state.activeSessionId = "";
        sessionStorage.removeItem("delta-force-session");
        document.querySelector("#messages").innerHTML = defaultChatMessages();
      }
      return loadSessions();
    })
    .catch((error) => window.alert(error.message || "操作失败。"));
}

function deleteChatSession(sessionId) {
  if (!state.user || !sessionId) return;
  api(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" })
    .then((result) => {
      if (!result?.deleted) throw new Error("\u672a\u627e\u5230\u53ef\u5220\u9664\u7684\u5bf9\u8bdd\u8bb0\u5f55\u3002");
      state.sessions = state.sessions.filter((session) => session.sessionId !== sessionId);
      if (state.activeSessionId === sessionId) {
        state.activeSessionId = "";
        sessionStorage.removeItem("delta-force-session");
        state.reactEvents = [];
        state.selectedReactEventId = "";
        state.selectedReactMessageId = "";
        state.messageReactEvents = {};
        state.gunModReplies = {};
        const messages = document.querySelector("#messages");
        if (messages) messages.innerHTML = defaultChatMessages();
        renderReactTimeline();
      }
      renderSessionList();
    })
    .catch((error) => window.alert(error.message || "\u5220\u9664\u5bf9\u8bdd\u5931\u8d25\u3002"));
}

function loadSessions() {
  if (!state.user) return Promise.resolve();
  return api(`/api/chat/sessions?archived=${state.showArchivedSessions}`)
    .then((sessions) => {
      state.sessions = sessions || [];
      renderSessionList();
    })
    .catch(() => {
      document.querySelector("#session-list").innerHTML = `<div class="empty-state compact">无法连接 Neo4j 会话库</div>`;
    });
}

function loadSessionMessages(sessionId) {
  if (!state.user || !sessionId) return;
  state.activeSessionId = sessionId;
  sessionStorage.setItem("delta-force-session", sessionId);
  state.conversation = { activeTaskId: "", taskStatus: "" };
  renderConversationStatus();
  renderSessionList();
  api(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`)
    .then((messages) => {
      if (state.activeSessionId !== sessionId) return;
      const box = document.querySelector("#messages");
      state.messageReactEvents = {};
      state.selectedReactMessageId = "";
      state.reactEvents = [];
      state.selectedReactEventId = "";
      state.gunModReplies = {};
      let lastUserPrompt = "";
      (messages || []).forEach((message) => {
        if (message.role === "user") lastUserPrompt = message.content || "";
        const events = normalizeReactEvents(message.reactEvents);
        if (message.messageId && events.length) {
          state.messageReactEvents[message.messageId] = events;
        }
        if (message.messageId && message.role !== "user") state.gunModReplies[message.messageId] = { content: message.content || "", events, prompt: lastUserPrompt };
      });
      const pending = state.pendingChats[sessionId];
      const savedMessages = messages.length ? messages.map(messageMarkup).join("") : defaultChatMessages();
      if (pending) {
        state.reactEvents = pending.events;
        state.selectedReactEventId = pending.events.length ? pending.events[pending.events.length - 1].event_id : "";
        state.selectedReactMessageId = pending.assistantId;
        state.messageReactEvents[pending.assistantId] = pending.events;
        state.gunModReplies[pending.assistantId] = { content: pending.content, events: pending.events, prompt: pending.prompt };
      }
      box.innerHTML = `${savedMessages}${pending ? pendingAssistantMarkup(pending) : ""}`;
      bindMessageReactClicks();
      renderReactTimeline();
      loadSessionTasks();
      box.scrollTop = box.scrollHeight;
    })
    .catch(() => {
      if (state.activeSessionId === sessionId) {
        document.querySelector("#messages").innerHTML = `<div class="empty-state">读取历史消息失败，请确认 Neo4j 已启动。</div>`;
      }
    });
}

function messageMarkup(message) {
  const isUser = message.role === "user";
  const reactEvents = normalizeReactEvents(message.reactEvents);
  const hasReact = !isUser && reactEvents.length > 0;
  const messageId = message.messageId || "";
  if (messageId && !isUser) state.gunModReplies[messageId] = { ...(state.gunModReplies[messageId] || {}), content: message.content || "", events: reactEvents };
  return `
    <div class="message ${isUser ? "user" : ""} ${hasReact ? "has-react" : ""} ${messageId && messageId === state.selectedReactMessageId ? "selected-react-message" : ""}" ${messageId ? `data-message-id="${escapeHtml(messageId)}"` : ""}>
      <div class="avatar">${isUser ? "你" : "Δ"}</div>
      <div class="bubble">${isUser ? escapeHtml(message.content) : gunModReplyContentMarkup(message.content, reactEvents)}${hasReact ? `<span class="message-react-tag">ReAct</span>${thinkingMarkup(reactEvents)}` : ""}${gunModCtaMarkup(messageId, reactEvents)}</div>
    </div>
  `;
}

function assistantStreamMarkup(id) {
  return `
    <div class="message has-react selected-react-message" data-message-id="${id}">
      <div class="avatar">Δ</div>
      <div class="bubble" id="${id}"><span class="message-react-tag">ReAct</span>${thinkingMarkup([])}</div>
    </div>
  `;
}

function pendingAssistantMarkup(pending) {
  const content = pending.error
    ? escapeHtml(pending.error)
    : `${gunModReplyContentMarkup(pending.content || "", pending.events)}<span class="message-react-tag">ReAct</span>${pending.status === "running" ? thinkingMarkup([]) : ""}`;
  return `
    <div class="message has-react selected-react-message" data-message-id="${escapeHtml(pending.assistantId)}">
      <div class="avatar">Δ</div>
      <div class="bubble" id="${escapeHtml(pending.assistantId)}">${content}</div>
    </div>
  `;
}

function pendingIsVisible(pending) {
  return pending.sessionId
    ? state.activeSessionId === pending.sessionId
    : !state.activeSessionId && state.pendingChats[pending.key] === pending;
}

function refreshPendingChat(pending) {
  if (!pendingIsVisible(pending)) return;
  const bubble = document.getElementById(pending.assistantId);
  if (bubble) {
    bubble.innerHTML = pending.error
      ? escapeHtml(pending.error)
      : `${gunModReplyContentMarkup(pending.content || "", pending.events)}<span class="message-react-tag">ReAct</span>${pending.status === "running" ? thinkingMarkup([]) : ""}`;
    bindGunModButtons(bubble);
  }
  const messages = document.querySelector("#messages");
  if (messages) messages.scrollTop = messages.scrollHeight;
}

function attachPendingSession(pending, sessionId) {
  if (!sessionId || pending.sessionId === String(sessionId)) return;
  const wasVisible = pendingIsVisible(pending);
  delete state.pendingChats[pending.key];
  pending.sessionId = String(sessionId);
  pending.key = pending.sessionId;
  state.pendingChats[pending.key] = pending;
  if (wasVisible) {
    state.activeSessionId = pending.sessionId;
    sessionStorage.setItem("delta-force-session", pending.sessionId);
    renderSessionList();
  }
  loadSessions();
}

function applyPendingConversationMetadata(pending, value) {
  let metadata = value;
  if (typeof metadata === "string") {
    try { metadata = JSON.parse(metadata); } catch { return; }
  }
  if (!metadata || typeof metadata !== "object") return;
  attachPendingSession(pending, metadata.session_id || metadata.sessionId);
  const conversation = metadata.conversation && typeof metadata.conversation === "object" ? metadata.conversation : metadata;
  const activeTaskId = conversation.active_task_id || conversation.activeTaskId;
  const taskStatus = conversation.task_status || conversation.taskStatus;
  if (activeTaskId) pending.activeTaskId = String(activeTaskId);
  if (taskStatus) pending.taskStatus = String(taskStatus);
  if (pendingIsVisible(pending)) {
    state.conversation.activeTaskId = pending.activeTaskId || "";
    state.conversation.taskStatus = pending.taskStatus || "";
    renderConversationStatus();
  }
}

function updatePendingReactTimeline(pending, event) {
  if (!event || event.type !== "react_event") return;
  pending.events = mergeReactEvent(pending.events, event);
  const phase = String(event.phase || "");
  if (event.status === "waiting_input" || phase === "supervisor_ask_user") pending.taskStatus = "awaiting_input";
  else if (phase === "supervisor_fail") pending.taskStatus = "failed";
  else if (phase === "supervisor_finish" || phase === "final_answer") pending.taskStatus = "completed";
  else pending.taskStatus = "running";
  if (!pendingIsVisible(pending)) return;
  state.reactEvents = pending.events;
  state.selectedReactEventId = event.event_id;
  state.selectedReactMessageId = pending.assistantId;
  state.messageReactEvents[pending.assistantId] = pending.events;
  state.gunModReplies[pending.assistantId] = { content: pending.content, events: pending.events, prompt: pending.prompt };
  refreshPendingChat(pending);
  renderReactTimeline();
}

function isGunModHandoff(events) {
  return (events || []).some((event) => {
    if ((event.status || "").toLowerCase() !== "running") return false;
    const text = `${event.title || ""} ${event.summary || ""} ${event.node || ""} ${JSON.stringify(event.payload || {})}`.toLowerCase();
    return (text.includes("supervisor") || text.includes("intentagent")) && text.includes("gunmodagent");
  });
}

function hasFinalAnswer(events) {
  return (events || []).some((event) => event.phase === "final_answer" && (event.status || "").toLowerCase() === "success");
}

function isCompletedGunModReply(events) {
  return isGunModHandoff(events) && hasFinalAnswer(events);
}

function gunModReplyContentMarkup(content, events) {
  if (!isCompletedGunModReply(events)) return escapeHtml(content);
  ensureGunModImageCatalog();
  if (!state.gunModImageCatalogReady) return escapeHtml(content);
  return dailyLoadoutMarkup(content, state.gunModImageCatalog);
}

function ensureGunModImageCatalog() {
  if (state.gunModImageCatalogReady || state.gunModImageCatalogPromise) return;
  state.gunModImageCatalogPromise = Promise.all([
    api("/api/market/items?limit=1000&type=attachments").catch(() => []),
    api("/api/market/items?limit=1000&type=firearms").catch(() => []),
  ]).then(([attachments, firearms]) => {
    state.gunModImageCatalog = [...attachments, ...firearms];
    state.gunModImageCatalogReady = true;
    Object.keys(state.gunModReplies).forEach((messageId) => refreshReplyStatus(messageId));
  }).finally(() => { state.gunModImageCatalogPromise = null; });
}

function thinkingMarkup(events) {
  if (hasFinalAnswer(events)) return "";
  return `<span class="message-thinking" role="status" aria-live="polite"><i></i>正在思考中</span>`;
}

function gunModCtaMarkup(messageId, events) {
  if (!messageId || !isGunModHandoff(events) || !hasFinalAnswer(events)) return "";
  const publish = isAdminUser() ? `<button class="message-gunmod-button secondary" type="button" data-publish-gunmod-message="${escapeHtml(messageId)}">发布到推荐</button>` : "";
  return `<span class="message-gunmod-actions"><button class="message-gunmod-button" type="button" data-gunmod-message="${escapeHtml(messageId)}">在改枪台中装配</button>${publish}</span>`;
}

function bindGunModButtons(scope = document) {
  scope.querySelectorAll("[data-gunmod-message]").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      openGunModifierFromReply(button.dataset.gunmodMessage);
    });
  });
  scope.querySelectorAll("[data-publish-gunmod-message]").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      publishGunModRecommendation(button.dataset.publishGunmodMessage, button);
    });
  });
}

function publishGunModRecommendation(messageId, button) {
  const reply = state.gunModReplies[messageId] || {};
  if (!reply.content || !state.user?.userId) return;
  button.disabled = true;
  button.textContent = "发布中…";
  api("/api/daily-content").then((content) => api("/api/admin/daily-content", {
    method: "POST",
    body: JSON.stringify({ ...content, aiRecommendation: reply.content, aiPrompt: reply.prompt || "智能对话中的改枪任务" }),
  })).then(() => { button.textContent = "已发布"; }).catch((error) => { button.disabled = false; button.textContent = error.message || "发布失败"; });
}

function refreshReplyStatus(messageId) {
  const message = document.querySelector(`[data-message-id="${messageId}"]`);
  const reply = state.gunModReplies[messageId];
  const bubble = message?.querySelector(".bubble");
  if (!bubble) return;
  const events = reply?.events || [];
  const hasReact = events.length > 0;
  bubble.innerHTML = `${gunModReplyContentMarkup(reply?.content || "", events)}${hasReact ? `<span class="message-react-tag">ReAct</span>${thinkingMarkup(events)}` : ""}${gunModCtaMarkup(messageId, events)}`;
  bindGunModButtons(bubble);
}

function openGunModifierFromReply(messageId) {
  const reply = state.gunModReplies[messageId] || {};
  sessionStorage.setItem("delta-force-pending-gun-build", JSON.stringify({
    content: reply.content || "",
  }));
  location.hash = "#/gun-modifier";
}

function normalizeReactEvents(value) {
  if (Array.isArray(value)) {
    return value.reduce((events, event) => mergeReactEvent(events, event), []);
  }
  if (!value) {
    return [];
  }
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? parsed.reduce((events, event) => mergeReactEvent(events, event), []) : [];
    } catch {
      return [];
    }
  }
  return [];
}

function mergeReactEvent(events, event) {
  if (!event || event.type !== "react_event") return Array.isArray(events) ? events : [];
  const merged = Array.isArray(events) ? [...events] : [];
  const identity = event.event_id || `${event.run_id || "legacy"}:${event.sequence ?? ""}:${event.phase || ""}`;
  const existingIndex = merged.findIndex((item) => (item.event_id || `${item.run_id || "legacy"}:${item.sequence ?? ""}:${item.phase || ""}`) === identity);
  if (existingIndex >= 0) merged[existingIndex] = event;
  else merged.push(event);
  merged.sort((left, right) => {
    const leftSequence = Number(left.sequence);
    const rightSequence = Number(right.sequence);
    if (Number.isFinite(leftSequence) && Number.isFinite(rightSequence)) return leftSequence - rightSequence;
    return Number(left.created_at || 0) - Number(right.created_at || 0);
  });
  return merged;
}

function phaseName(phase) {
  const names = {
    workspace_ready: "工作区准备",
    memory_retrieval: "读取上下文",
    task_plan_ready: "任务拆解",
    agent_plan: "Agent 计划",
    handoff: "Agent 交接",
    tool_call: "工具调用",
    tool_result: "工具结果",
    observation: "观察",
    agent_result: "Agent 完成",
    agent_update: "状态更新",
    final_answer: "最终回答",
    supervisor_dispatch: "Supervisor 调度",
    supervisor_retry: "Supervisor 重试",
    supervisor_ask_user: "等待用户输入",
    supervisor_finish: "Supervisor 完成",
    supervisor_fail: "Supervisor 终止",
  };
  return names[phase] || phase || "事件";
}

function updateReactTimeline(event) {
  if (!event || event.type !== "react_event") return;
  state.reactEvents = mergeReactEvent(state.reactEvents, event);
  if (state.selectedReactMessageId) {
    state.messageReactEvents[state.selectedReactMessageId] = state.reactEvents;
    const reply = state.gunModReplies[state.selectedReactMessageId] || { content: "", events: [] };
    reply.events = state.reactEvents;
    state.gunModReplies[state.selectedReactMessageId] = reply;
    refreshReplyStatus(state.selectedReactMessageId);
  }
  state.selectedReactEventId = event.event_id;
  renderReactTimeline();
}

function bindMessageReactClicks() {
  document.querySelectorAll(".message.has-react[data-message-id]").forEach((message) => {
    message.addEventListener("click", () => {
      selectReactMessage(message.dataset.messageId);
    });
  });
  bindGunModButtons();
}

function selectReactMessage(messageId) {
  state.selectedReactMessageId = messageId || "";
  state.reactEvents = normalizeReactEvents(state.messageReactEvents[state.selectedReactMessageId]);
  state.selectedReactEventId = state.reactEvents.length ? state.reactEvents[state.reactEvents.length - 1].event_id : "";
  document.querySelectorAll(".message.has-react[data-message-id]").forEach((message) => {
    message.classList.toggle("selected-react-message", message.dataset.messageId === state.selectedReactMessageId);
  });
  renderReactTimeline();
  loadSessionTasks();
}

function applyConversationMetadata(value) {
  let metadata = value;
  if (typeof metadata === "string") {
    try { metadata = JSON.parse(metadata); } catch { return; }
  }
  if (!metadata || typeof metadata !== "object") return;
  const sessionId = metadata.session_id || metadata.sessionId;
  if (sessionId) {
    state.activeSessionId = String(sessionId);
    sessionStorage.setItem("delta-force-session", state.activeSessionId);
  }
  const conversation = metadata.conversation && typeof metadata.conversation === "object" ? metadata.conversation : metadata;
  const activeTaskId = conversation.active_task_id || conversation.activeTaskId;
  const taskStatus = conversation.task_status || conversation.taskStatus;
  if (activeTaskId) state.conversation.activeTaskId = String(activeTaskId);
  if (taskStatus) state.conversation.taskStatus = String(taskStatus);
  renderConversationStatus();
}

function renderConversationStatus() {
  const status = document.querySelector("#conversation-status");
  if (!status) return;
  const taskStatus = state.conversation.taskStatus;
  const labels = {
    awaiting_input: "等待补充参数",
    running: "任务执行中",
    completed: "任务已完成",
    cancelled: "任务已取消",
    failed: "任务失败",
  };
  status.hidden = !taskStatus;
  status.className = `conversation-status ${taskStatus || ""}`;
  status.textContent = labels[taskStatus] || taskStatus;
}

function loadSessionTasks() {
  if (!state.user || !state.activeSessionId) return Promise.resolve();
  return api(`/api/chat/sessions/${encodeURIComponent(state.activeSessionId)}/tasks`)
    .then((tasks) => {
      const activeTask = (tasks || []).find((task) => task.taskId === state.conversation.activeTaskId || task.task_id === state.conversation.activeTaskId || task.id === state.conversation.activeTaskId) || (tasks || [])[0];
      if (activeTask) {
        state.conversation.activeTaskId = String(activeTask.taskId || activeTask.task_id || activeTask.id || "");
        state.conversation.taskStatus = String(activeTask.status || "");
      } else {
        state.conversation = { activeTaskId: "", taskStatus: "" };
      }
      renderConversationStatus();
    })
    .catch(() => {});
}

function renderReactTimeline() {
  const timeline = document.querySelector("#react-timeline");
  const detail = document.querySelector("#react-detail");
  const summary = document.querySelector("#react-panel-summary");
  if (!timeline || !detail) return;
  if (!state.reactEvents.length) {
    if (summary) summary.textContent = "点击查看任务执行详情";
    timeline.innerHTML = `<div class="empty-state compact">点击带 ReAct 标识的回复，查看这条回复的执行过程。</div>`;
    detail.innerHTML = `<div class="empty-state compact">点击事件查看 payload JSON。</div>`;
    return;
  }
  const latest = state.reactEvents[state.reactEvents.length - 1];
  if (summary) summary.textContent = `${state.reactEvents.length} 个事件 · ${statusName(latest?.status)} · ${phaseName(latest?.phase)}`;
  timeline.innerHTML = state.reactEvents.map((event, index) => `
    <button class="react-event ${event.status || "running"} ${event.event_id === state.selectedReactEventId ? "active" : ""}" type="button" data-react-id="${escapeHtml(event.event_id)}" aria-pressed="${event.event_id === state.selectedReactEventId}">
      <span class="react-event-marker" aria-hidden="true">
        <span class="react-dot"></span>
        <span class="react-event-index">${escapeHtml(String(event.sequence || index + 1))}</span>
      </span>
      <span class="react-event-content">
        <strong>${escapeHtml(event.title || phaseName(event.phase))}</strong>
        <small>${escapeHtml(phaseName(event.phase))}${event.node ? ` · ${escapeHtml(event.node)}` : ""}${event.task_id ? ` · ${escapeHtml(event.task_id)}` : ""}${event.attempt ? ` · 第 ${escapeHtml(String(event.attempt))} 次` : ""}</small>
        ${event.summary ? `<em>${escapeHtml(event.summary)}</em>` : ""}
      </span>
    </button>
  `).join("");
  timeline.querySelectorAll("[data-react-id]").forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedReactEventId = button.dataset.reactId;
      renderReactTimeline();
    });
  });
  const selected = state.reactEvents.find((event) => event.event_id === state.selectedReactEventId) || state.reactEvents[state.reactEvents.length - 1];
  detail.innerHTML = `
    <div class="react-detail-head">
      <strong>${escapeHtml(selected.title || phaseName(selected.phase))}</strong>
      <span class="react-status ${selected.status || "running"}">${escapeHtml(statusName(selected.status))}</span>
    </div>
    <p>${escapeHtml(selected.summary || "")}</p>
    <pre>${escapeHtml(JSON.stringify(selected.payload || {}, null, 2))}</pre>
  `;
}

function statusName(status) {
  const names = {
    running: "执行中",
    success: "已完成",
    retrying: "重试中",
    waiting_input: "等待输入",
    warning: "部分完成",
    error: "失败",
    cancelled: "已取消",
  };
  return names[status] || status || "执行中";
}

function reactLogText(events) {
  const exportedAt = new Date().toISOString();
  const lines = [
    "Delta Force Agent - ReAct Execution Log",
    `Exported at: ${exportedAt}`,
    `Session ID: ${state.activeSessionId || "(unsaved)"}`,
    `Event count: ${events.length}`,
    "",
  ];
  events.forEach((event, index) => {
    lines.push(`[${index + 1}] ${event.title || phaseName(event.phase) || "Event"}`);
    lines.push(`Event ID: ${event.event_id || ""}`);
    lines.push(`Phase: ${event.phase || ""}`);
    lines.push(`Node: ${event.node || ""}`);
    lines.push(`Status: ${event.status || ""}`);
    lines.push(`Summary: ${event.summary || ""}`);
    lines.push("Payload:");
    lines.push(JSON.stringify(event.payload || {}, null, 2));
    lines.push("".padEnd(72, "-"));
    lines.push("");
  });
  return lines.join("\n");
}

function exportReactLog() {
  if (!state.reactEvents.length) {
    window.alert("当前没有可导出的 ReAct 执行过程。请先选择一条带 ReAct 标识的回复。");
    return;
  }
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const blob = new Blob(["\ufeff", reactLogText(state.reactEvents)], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `react-execution-${timestamp}.txt`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

async function streamChat(pending, messageId, conversationMode) {
  const token = state.user?.token;
  const controller = new AbortController();
  pending.abortController = controller;
  const timeoutId = window.setTimeout(() => controller.abort("智能体请求超时"), 140000);
  const response = await fetch(`${API_BASE}/api/chat/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({
      message: pending.prompt,
      sessionId: pending.sessionId || "",
      messageId,
      conversationMode,
      reactEvents: true,
    }),
    signal: controller.signal,
  });
  if (!response.ok || !response.body) {
    let errorBody = null;
    try { errorBody = await response.json(); } catch { errorBody = null; }
    const retryAfter = response.headers.get("Retry-After");
    const requestId = response.headers.get("X-Request-ID") || errorBody?.requestId;
    const retryText = retryAfter ? `，请在 ${retryAfter} 秒后重试` : "";
    const requestText = requestId ? `（请求编号 ${requestId}）` : "";
    throw new Error(`${errorBody?.message || response.statusText || "智能体请求失败"}${retryText}${requestText}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let sawDone = false;

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split(/\r?\n\r?\n/);
      buffer = parts.pop() || "";

      for (const part of parts) {
      const dataLines = part.split(/\r?\n/).filter((line) => line.startsWith("data:"));
      if (!dataLines.length) continue;
      const raw = dataLines.map((line) => line.slice("data:".length).trim()).join("\n");
      if (!raw) continue;
      if (raw === "[DONE]") {
        sawDone = true;
        continue;
      }

      const chunk = JSON.parse(raw);
      if (chunk.sessionId) {
        applyPendingConversationMetadata(pending, chunk);
        continue;
      }
      if (chunk.assistantMessageId) {
        pending.savedAssistantMessageId = String(chunk.assistantMessageId);
        continue;
      }
      if (chunk.error) {
        throw new Error(chunk.error);
      }

      const delta = chunk.choices?.[0]?.delta || {};
      if (delta.error) {
        const retryText = chunk.retry_after ? `，请在 ${chunk.retry_after} 秒后重试` : "";
        const requestText = chunk.request_id ? `（请求编号 ${chunk.request_id}）` : "";
        throw new Error(`${delta.error.message || "智能体请求失败"}${retryText}${requestText}`);
      }
      if (delta.session) {
        applyPendingConversationMetadata(pending, delta.session);
      }
      if (delta.react_event) {
        updatePendingReactTimeline(pending, delta.react_event);
      }
      if (delta.content) {
        pending.content += delta.content;
        if (pendingIsVisible(pending)) {
          state.gunModReplies[pending.assistantId] = { content: pending.content, events: pending.events, prompt: pending.prompt };
          refreshPendingChat(pending);
        }
      }
      }
    }
  } finally {
    window.clearTimeout(timeoutId);
    delete pending.abortController;
  }

  if (!sawDone) throw new Error("智能体流式响应意外中断");
  if (!pending.content) pending.content = "后端没有返回内容";
  pending.status = "completed";
  refreshPendingChat(pending);
  delete state.pendingChats[pending.key];
  await loadSessions();
  if (pending.sessionId && state.activeSessionId === pending.sessionId) {
    return loadSessionMessages(pending.sessionId);
  }
}

function marketPage() {
  return `
    <section class="panel">
      <div class="market-toolbar">
        <div class="field"><input id="market-search" placeholder="搜索物品、枪械、配件..." /></div>
        <div class="field"><select id="market-type"><option value="firearms">枪械</option><option value="attachments">配件</option><option value="armor">护甲</option><option value="helmets">头盔</option><option value="chest_rigs">胸挂</option><option value="backpacks">背包</option></select></div>
        <div class="field"><select><option>按战备价排序</option><option>按当前价排序</option><option>按价差排序</option></select></div>
        <button class="ghost-button" id="market-refresh" type="button">刷新行情</button>
      </div>
      <table class="data-table">
        <thead>
          <tr><th>物品</th><th>类型</th><th>当前价格</th><th>战备价格</th><th>3天均价 / 涨幅</th><th>7天均价 / 涨幅</th></tr>
        </thead>
        <tbody id="market-body">${marketRows(mockMarketItems)}</tbody>
      </table>
      <div id="market-pagination" class="market-pagination" hidden>
        <span id="market-page-info"></span>
        <div class="market-page-actions">
          <button id="market-prev" class="ghost-button" type="button">上一页</button>
          <button id="market-next" class="ghost-button" type="button">下一页</button>
        </div>
      </div>
    </section>
  `;
}

function marketRows(items) {
  if (!items.length) {
    return `<tr><td colspan="6"><div class="empty-state">没有匹配的数据</div></td></tr>`;
  }
  return items.map((item) => {
    const currentPrice = item.currentPrice ?? item.price ?? 0;
    const combatPrice = item.combatPrice ?? Math.round(currentPrice * (1 + (item.change || 0) / 100));
    const typeMap = { firearm: "枪械", firearms: "枪械", attachment: "配件", attachments: "配件", armor: "护甲", helmets: "头盔", chest_rigs: "胸挂", backpacks: "背包" };
    const type = typeMap[item.type] || item.type;
    const price3d = item.price3d ?? 0;
    const change3d = item.change3dPercent ?? item.change ?? 0;
    const price7d = item.price7d ?? 0;
    const change7d = item.change7dPercent ?? item.change ?? 0;
    return `
      <tr>
        <td><span class="market-item-name">${itemImageMarkup(item)}<strong>${escapeHtml(item.name)}</strong></span></td>
        <td>${escapeHtml(type)}</td>
        <td>${money(currentPrice)}</td>
        <td>${money(combatPrice)}</td>
        <td>${money(price3d)} <span class="${change3d >= 0 ? "trend-up" : "trend-down"}">${change3d >= 0 ? "+" : ""}${Number(change3d).toFixed(1)}%</span></td>
        <td>${money(price7d)} <span class="${change7d >= 0 ? "trend-up" : "trend-down"}">${change7d >= 0 ? "+" : ""}${Number(change7d).toFixed(1)}%</span></td>
      </tr>
    `;
  }).join("");
}

function readinessPage() {
  return `
    <section class="panel readiness-intro">
      <span class="badge amber">READINESS</span>
      <h2>卡战备</h2>
      <p>基于当前行情接口的市场价与战备价，为主武器、头盔、防弹衣等装备生成总花费最低的组合方案。</p>
    </section>
    <section class="panel">
      <form id="readiness-form" class="readiness-form">
        <div class="field"><label for="readiness-target">目标战备</label><input id="readiness-target" type="number" min="1" step="1000" value="120000" required /></div>
        <div class="field"><label for="readiness-topk">方案数量</label><select id="readiness-topk"><option value="1">1 套</option><option value="3" selected>3 套</option><option value="5">5 套</option></select></div>
        <button id="readiness-submit" class="primary-button" type="submit">生成方案</button>
      </form>
      <p class="readiness-hint">必选：主武器、头盔、防弹衣。胸挂与背包为可选槽位；无实时市场价时按接口返回的战备价估算。</p>
    </section>
    <section id="readiness-result" class="readiness-results" aria-live="polite"><div class="empty-state">输入目标战备后生成方案</div></section>
  `;
}

function readinessPlanMarkup(plan, index, target) {
  const gap = Number(plan.gap || 0);
  const gapText = gap >= 0 ? `超出 ${money(gap)}` : `不足 ${money(Math.abs(gap))}`;
  const status = plan.qualified ? "已达标" : "未达标";
  return `
    <article class="panel readiness-plan">
      <div class="readiness-plan-head">
        <div><span class="badge ${plan.qualified ? "blue" : "amber"}">方案 ${index + 1}</span><h3>${status}</h3></div>
        <strong>${money(plan.totalCost)} <small>预计花费</small></strong>
      </div>
      <div class="readiness-metrics">
        <div><span>目标战备</span><strong>${money(target)}</strong></div>
        <div><span>实际战备</span><strong>${money(plan.totalCombat)}</strong></div>
        <div><span>战备差额</span><strong class="${gap >= 0 ? "trend-up" : "trend-down"}">${gapText}</strong></div>
      </div>
      <div class="readiness-entry-list">
        ${(plan.entries || []).map((entry) => `<div class="readiness-entry"><span class="readiness-slot">${escapeHtml(entry.slot)}</span><strong>${escapeHtml(entry.name)}</strong><span>战备 ${money(entry.combatPrice)}</span><span>市场 ${money(entry.currentPrice)}</span><small>${escapeHtml(entry.priceSource || "")}</small></div>`).join("")}
      </div>
    </article>
  `;
}

function readinessResultMarkup(result) {
  if ((result.missingRequiredSlots || []).length) {
    return `<div class="empty-state">必选槽位缺少行情数据：${escapeHtml(result.missingRequiredSlots.join("、"))}。请确认行情接口已提供这些分类。</div>`;
  }
  if (!(result.plans || []).length) return `<div class="empty-state">${escapeHtml(result.message || "未能生成方案")}</div>`;
  return `<div class="readiness-result-summary">已根据 ${money(result.candidateCount)} 件接口行情物品完成计算，达标方案按预计花费从低到高排列。</div>${result.plans.map((plan, index) => readinessPlanMarkup(plan, index, result.targetCombat)).join("")}`;
}

function bindReadiness() {
  const form = document.querySelector("#readiness-form");
  const resultView = document.querySelector("#readiness-result");
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const button = document.querySelector("#readiness-submit");
    const targetCombat = Number(document.querySelector("#readiness-target").value);
    const topK = Number(document.querySelector("#readiness-topk").value);
    if (!Number.isFinite(targetCombat) || targetCombat <= 0) return;
    button.disabled = true;
    resultView.innerHTML = `<div class="empty-state">正在读取行情并计算方案…</div>`;
    api("/api/readiness/plans", { method: "POST", body: JSON.stringify({ targetCombat, topK }) })
      .then((result) => { resultView.innerHTML = readinessResultMarkup(result); })
      .catch((error) => { resultView.innerHTML = `<div class="empty-state">${escapeHtml(error.message || "生成方案失败")}</div>`; })
      .finally(() => { button.disabled = false; });
  });
}

const MODIFIER_STAT_ALIASES = {
  "后坐力控制": ["后坐力控制", "后坐力", "垂直后坐力控制", "水平后坐力控制"],
  "操控速度": ["操控速度", "操控", "开镜速度", "举镜瞄准速度"],
  "据枪稳定性": ["据枪稳定性", "据枪", "枪身稳定性", "视野稳定性", "稳定性"],
  "腰际射击精度": ["腰际射击精度", "腰射精度", "腰际", "腰射"],
  "枪口初速": ["枪口初速", "初速"],
  "优势射程": ["优势射程", "有效射程", "射程"],
  "射速": ["射速"], "弹匣容量": ["弹匣容量", "容量"], "重量": ["重量"],
  "基础伤害": ["基础伤害", "伤害"], "护甲伤害": ["护甲伤害"],
};
const MODIFIER_EXCLUSIVE_SLOTS = [new Set(["护木", "护木套件"])];

function modifierPage() {
  return `<div class="modifier-layout">
    <aside class="panel modifier-guns"><div class="modifier-sidebar-head"><span class="badge">LOADOUT</span><h3>选择枪械</h3></div>
      <div class="field"><input id="modifier-search" placeholder="搜索枪械" autocomplete="off" /></div><div id="modifier-gun-list" class="modifier-gun-list"><div class="empty-state compact">正在读取枪械数据…</div></div></aside>
    <section class="modifier-workbench"><div class="panel modifier-header"><div><span class="eyebrow">GRAPH-DRIVEN GUN BENCH</span><h2 id="modifier-gun-title">请选择一把枪械</h2><p id="modifier-gun-meta">槽位关系来自知识图谱，价格同步自实时数据服务。</p></div><button class="ghost-button" id="modifier-clear" type="button">清空装配</button></div>
      <section class="panel modifier-stats"><div class="section-title"><h3>基础属性变化</h3><span id="modifier-total">0 个配件</span></div><div id="modifier-stats-grid" class="modifier-stats-grid"><div class="empty-state compact">选择枪械后显示属性对比</div></div></section>
      <div class="modifier-content"><section><div class="section-title"><h3>可用配件槽位</h3><span id="modifier-slot-count">0 个可用</span></div><div id="modifier-slot-list" class="modifier-slot-list"><div class="empty-state">选择枪械开始装配</div></div></section>
      <aside><div class="section-title"><h3>当前装配</h3><span id="modifier-hidden-count">0 个隐藏</span></div><div id="modifier-build-list" class="modifier-build-list empty-state compact">尚未安装配件</div></aside></div></section></div>`;
}

function modifierPrice(price) { return price ? `市场 ${money(price.current_price)} / 战备 ${money(price.combat_price)}` : "暂无实时价格"; }
function modifierSelectedAttachments() {
  const current = state.modifierCurrent;
  if (!current) return [];
  return Object.entries(state.modifierSelected).map(([slotId, attachmentId]) => {
    const slot = current.slots.find((item) => item.id === slotId);
    const attachment = slot?.attachments.find((item) => item.id === attachmentId);
    return slot && attachment ? { slot, attachment } : null;
  }).filter(Boolean);
}
function modifierAvailable(slot) {
  const selected = modifierSelectedAttachments(); const ids = new Set(selected.map((item) => item.attachment.id)); const names = new Set(selected.map((item) => item.attachment.name));
  const otherSlots = new Set(selected.filter((item) => item.slot.id !== slot.id).map((item) => item.slot.name));
  if (MODIFIER_EXCLUSIVE_SLOTS.some((group) => group.has(slot.name) && [...group].some((name) => name !== slot.name && otherSlots.has(name)))) return false;
  if ((slot.disabledBy || []).some((id) => ids.has(id))) return false;
  return slot.defaultAvailable || (!!slot.parentAttachment && names.has(slot.parentAttachment));
}
function modifierCleanup() {
  if (!state.modifierCurrent) return;
  let changed = true; while (changed) { changed = false; state.modifierCurrent.slots.forEach((slot) => { if (state.modifierSelected[slot.id] && !modifierAvailable(slot)) { delete state.modifierSelected[slot.id]; changed = true; } }); }
}
function modifierEffects(attachment) {
  const raw = String(attachment.rawEffect || ""); const effects = [];
  Object.entries(MODIFIER_STAT_ALIASES).forEach(([stat, aliases]) => { if (aliases.some((alias) => raw.includes(alias))) { const match = raw.match(/([+-])\s*(\d+(?:\.\d+)?)(%)?/); if (match) effects.push({ stat, value: (match[1] === "-" ? -1 : 1) * Number(match[2]), percent: !!match[3] }); } });
  return effects;
}
function modifierStats() {
  const base = state.modifierCurrent?.baseStats || {}, final = { ...base }, deltas = {};
  modifierSelectedAttachments().forEach(({ attachment }) => modifierEffects(attachment).forEach((effect) => { const delta = effect.percent ? Number(base[effect.stat] || 0) * effect.value / 100 : effect.value; final[effect.stat] = Number(final[effect.stat] || 0) + delta; deltas[effect.stat] = Number(deltas[effect.stat] || 0) + delta; }));
  return { base, final, deltas };
}
function modifierBuildTotal() { const total = { current: Number(state.modifierCurrent?.price?.current_price || 0), combat: Number(state.modifierCurrent?.price?.combat_price || 0) }; modifierSelectedAttachments().forEach(({ attachment }) => { total.current += Number(attachment.price?.current_price || 0); total.combat += Number(attachment.price?.combat_price || 0); }); return total; }
function modifierRender() {
  modifierCleanup(); const current = state.modifierCurrent, list = document.querySelector("#modifier-gun-list"); if (!list) return;
  const keyword = state.modifierSearch.toLowerCase(); list.innerHTML = state.modifierFirearms.filter((gun) => gun.name.toLowerCase().includes(keyword)).map((gun) => `<button type="button" class="modifier-gun-button ${current?.id === gun.id ? "active" : ""}" data-modifier-gun="${escapeHtml(gun.id)}"><span class="modifier-gun-name">${itemImageMarkup({ ...gun, type: "firearm" })}<strong>${escapeHtml(gun.name)}</strong></span><small>${gun.slotCount || 0} 个槽位 · ${modifierPrice(gun.price)}</small></button>`).join("") || `<div class="empty-state compact">没有匹配的枪械</div>`;
  list.querySelectorAll("[data-modifier-gun]").forEach((button) => button.addEventListener("click", () => modifierLoadGun(button.dataset.modifierGun)));
  if (!current) return;
  const total = modifierBuildTotal(), selected = modifierSelectedAttachments(); document.querySelector("#modifier-gun-title").textContent = current.name; document.querySelector("#modifier-gun-meta").textContent = `整枪价格：市场 ${money(total.current)} / 战备 ${money(total.combat)} · ${modifierPrice(current.price)}`; document.querySelector("#modifier-total").textContent = `${selected.length} 个配件 · 市场 ${money(total.current)}`;
  const { base, final, deltas } = modifierStats(); document.querySelector("#modifier-stats-grid").innerHTML = current.statFields.filter((field) => base[field] !== undefined || final[field] !== undefined).map((field) => { const delta = Number(deltas[field] || 0); return `<div class="modifier-stat"><small>${field}</small><strong>${Number(final[field] ?? base[field]).toFixed(2).replace(/\.00$/, "")}</strong><span class="${delta >= 0 ? "trend-up" : "trend-down"}">${delta ? `${delta > 0 ? "+" : ""}${delta.toFixed(2).replace(/\.00$/, "")}` : "—"}</span><em>基础 ${base[field] ?? "—"}</em></div>`; }).join("") || `<div class="empty-state compact">该枪械暂无可计算的基础属性</div>`;
  const available = current.slots.filter(modifierAvailable); document.querySelector("#modifier-slot-count").textContent = `${available.length} 个可用`; document.querySelector("#modifier-hidden-count").textContent = `${current.slots.length - available.length} 个隐藏`;
  document.querySelector("#modifier-slot-list").innerHTML = available.map((slot) => { const active = state.modifierSelected[slot.id] || ""; const selectedAttachment = slot.attachments.find((item) => item.id === active); const expanded = state.modifierExpandedSlot === slot.id; const installedMarkup = selectedAttachment ? `${attachmentImageMarkup({ ...selectedAttachment, type: "attachment" })}<span><strong>${escapeHtml(selectedAttachment.name)}</strong><small>${modifierPrice(selectedAttachment.price)}</small></span>` : `<span class="modifier-slot-uninstalled"><strong>未安装配件</strong><small>点击选择可安装配件</small></span>`; return `<article class="modifier-slot-card"><div><strong>${escapeHtml(slot.name)}</strong>${slot.parentAttachment ? `<small>由 ${escapeHtml(slot.parentAttachment)} 解锁</small>` : ""}</div><button type="button" class="modifier-slot-summary ${selectedAttachment ? "installed" : ""}" data-modifier-toggle-slot="${escapeHtml(slot.id)}" aria-expanded="${expanded}" aria-controls="modifier-slot-options-${escapeHtml(slot.id)}">${installedMarkup}<span class="modifier-slot-chevron" aria-hidden="true">${expanded ? "⌃" : "⌄"}</span></button>${expanded ? `<div id="modifier-slot-options-${escapeHtml(slot.id)}" class="modifier-attachment-options" role="group" aria-label="${escapeHtml(slot.name)} 配件"><button type="button" class="modifier-attachment-option empty ${!active ? "active" : ""}" data-modifier-slot="${escapeHtml(slot.id)}" data-modifier-attachment="">不安装</button>${slot.attachments.map((item) => `<button type="button" class="modifier-attachment-option ${item.id === active ? "active" : ""}" data-modifier-slot="${escapeHtml(slot.id)}" data-modifier-attachment="${escapeHtml(item.id)}">${attachmentImageMarkup({ ...item, type: "attachment" })}<span><strong>${escapeHtml(item.name)}</strong><small>${modifierPrice(item.price)}</small></span></button>`).join("")}</div>` : ""}</article>`; }).join("") || `<div class="empty-state compact">当前没有可用槽位</div>`;
  document.querySelectorAll("[data-modifier-toggle-slot]").forEach((button) => button.addEventListener("click", () => { const slotId = button.dataset.modifierToggleSlot; state.modifierExpandedSlot = state.modifierExpandedSlot === slotId ? "" : slotId; modifierRender(); }));
  document.querySelectorAll("[data-modifier-attachment]").forEach((button) => button.addEventListener("click", () => { const { modifierSlot, modifierAttachment } = button.dataset; if (modifierAttachment) state.modifierSelected[modifierSlot] = modifierAttachment; else delete state.modifierSelected[modifierSlot]; state.modifierExpandedSlot = ""; modifierRender(); }));
  document.querySelector("#modifier-build-list").innerHTML = selected.length ? selected.map(({ slot, attachment }) => `<div class="modifier-build-item"><small>${escapeHtml(slot.name)}</small><div class="modifier-build-attachment">${attachmentImageMarkup({ ...attachment, type: "attachment" })}<strong>${escapeHtml(attachment.name)}</strong></div><span>${modifierPrice(attachment.price)}</span></div>`).join("") : "尚未安装配件";
}
function modifierKey(value) { return String(value || "").toLowerCase().replace(/[\s_\-/*＊()（）·]/g, ""); }
function modifierPendingBuild() {
  try { return JSON.parse(sessionStorage.getItem("delta-force-pending-gun-build") || "null"); } catch { return null; }
}
function modifierChooseGun(guns, pending) {
  const textKey = modifierKey(pending?.content);
  return guns.find((gun) => textKey.includes(modifierKey(gun.name))) || guns.find((gun) => gun.slotCount > 0) || guns[0];
}
function modifierApplyPlan(plan) {
  const responseText = modifierKey(plan.text);
  const responseAttachments = [];
  if (responseText) {
    state.modifierCurrent.slots.forEach((slot) => slot.attachments.forEach((attachment) => {
      const name = modifierKey(attachment.name);
      if (name && responseText.includes(name) && !responseAttachments.includes(attachment.name)) responseAttachments.push(attachment.name);
    }));
  }
  const names = responseAttachments;
  const remaining = [...names];
  let installed = true;
  while (installed && remaining.length) {
    installed = false;
    for (let index = remaining.length - 1; index >= 0; index -= 1) {
      const requested = modifierKey(remaining[index]);
      const slot = state.modifierCurrent.slots.find((item) => modifierAvailable(item) && !state.modifierSelected[item.id] && item.attachments.some((attachment) => modifierKey(attachment.name) === requested || modifierKey(attachment.name).includes(requested) || requested.includes(modifierKey(attachment.name))));
      const attachment = slot?.attachments.find((item) => modifierKey(item.name) === requested || modifierKey(item.name).includes(requested) || requested.includes(modifierKey(item.name)));
      if (slot && attachment) { state.modifierSelected[slot.id] = attachment.id; remaining.splice(index, 1); installed = true; }
    }
  }
}
function modifierLoadGun(id, plan = null) { api(`/api/gun-modifier/firearms/${encodeURIComponent(id)}`).then((gun) => { state.modifierCurrent = gun; state.modifierSelected = {}; state.modifierExpandedSlot = ""; if (plan) modifierApplyPlan(plan); modifierRender(); }).catch((error) => { document.querySelector("#modifier-slot-list").innerHTML = `<div class="empty-state">${escapeHtml(error.message || "读取配件关系失败")}</div>`; }); }
function bindModifier() { const pending = modifierPendingBuild(); document.querySelector("#modifier-search").addEventListener("input", (event) => { state.modifierSearch = event.target.value; modifierRender(); }); document.querySelector("#modifier-clear").addEventListener("click", () => { state.modifierSelected = {}; state.modifierExpandedSlot = ""; modifierRender(); }); api("/api/gun-modifier/firearms").then((guns) => { state.modifierFirearms = guns || []; modifierRender(); const first = modifierChooseGun(state.modifierFirearms, pending); if (first) modifierLoadGun(first.id, pending ? { text: pending.content || "" } : null); if (pending) sessionStorage.removeItem("delta-force-pending-gun-build"); }).catch((error) => { document.querySelector("#modifier-gun-list").innerHTML = `<div class="empty-state compact">${escapeHtml(error.message || "无法读取枪械数据")}</div>`; }); }

function knowledgePage() {
  return `
    <div class="graph-explorer">
      <section class="panel graph-search-panel">
        <div>
          <span class="badge blue">sjzdata</span>
          <h3>图谱节点查询</h3>
          <p>输入一个问题或关键词，系统会定位匹配节点，再展示它和其它节点之间的直接关系。</p>
        </div>
        <form class="graph-search-form" id="graph-search-form">
          <div class="field"><input id="graph-question" placeholder="例如：M4A1、枪管、后坐力控制、某个配件名称..." autocomplete="off" /></div>
          <button class="primary-button" type="submit">查询节点</button>
        </form>
        <div class="candidate-list" id="candidate-list">
          <span class="muted-text">查询后会在这里显示匹配到的多个节点。</span>
        </div>
      </section>
      <section class="panel graph-canvas-panel">
        <div class="graph-canvas-head">
          <div>
            <h3>关系图</h3>
            <p id="graph-status">输入关键词后展示中心节点与一跳关系。</p>
          </div>
          <span class="badge" id="knowledge-count">0 relations</span>
        </div>
        <div id="graph-view" class="graph-view">
          <div class="empty-state">查询后将在这里显示圆形节点和关系线。</div>
        </div>
      </section>
      <section class="panel graph-detail-panel">
        <h3>选中详情</h3>
        <div id="graph-detail" class="graph-detail">
          <div class="empty-state compact">点击节点或关系查看属性。</div>
        </div>
      </section>
    </div>
  `;
}

function graphSvg(graph) {
  const nodes = graph.nodes || [];
  const relationships = graph.relationships || [];
  if (!nodes.length) return `<div class="empty-state">没有找到匹配节点。</div>`;
  const width = 980;
  const height = 560;
  const centerX = width / 2;
  const centerY = height / 2;
  const radius = Math.min(width, height) * 0.34;
  const centerNode = nodes.find((node) => node.id === graph.centerId) || nodes[0];
  const otherNodes = nodes.filter((node) => node.id !== centerNode.id);
  const positions = new Map([[centerNode.id, { x: centerX, y: centerY }]]);
  otherNodes.forEach((node, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(otherNodes.length, 1) - Math.PI / 2;
    positions.set(node.id, { x: centerX + Math.cos(angle) * radius, y: centerY + Math.sin(angle) * radius });
  });
  return `
    <div class="graph-stage">
      <svg viewBox="0 0 ${width} ${height}" role="img" aria-label="Neo4j relationship graph">
        <defs><marker id="arrow-head" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L0,6 L9,3 z" fill="#111111"></path></marker></defs>
        <g id="graph-pan-layer">
          ${relationships.map((rel) => graphEdge(rel, positions)).join("")}
          ${nodes.map((node) => graphNode(node, positions.get(node.id), node.id === centerNode.id)).join("")}
        </g>
      </svg>
      <div class="graph-hint">滚轮缩放 · 按住空白处拖动 · 双击复位</div>
    </div>
  `;
}

function graphEdge(rel, positions) {
  const source = positions.get(rel.source);
  const target = positions.get(rel.target);
  if (!source || !target) return "";
  const midX = (source.x + target.x) / 2;
  const midY = (source.y + target.y) / 2;
  return `
    <g class="graph-edge" data-graph-kind="relationship" data-graph-id="${rel.id}">
      <line x1="${source.x}" y1="${source.y}" x2="${target.x}" y2="${target.y}" marker-end="url(#arrow-head)"></line>
      <text x="${midX}" y="${midY - 8}" text-anchor="middle">${escapeHtml(rel.type)}</text>
    </g>
  `;
}

function graphNode(node, pos, isCenter) {
  const labels = (node.labels || []).join(" / ") || "Node";
  const caption = node.caption || node.properties?.name || node.properties?.title || `#${node.id}`;
  const radius = isCenter ? 48 : 38;
  return `
    <g class="graph-node-circle ${isCenter ? "center" : ""}" data-graph-kind="node" data-graph-id="${node.id}" transform="translate(${pos.x}, ${pos.y})">
      <circle r="${radius}"></circle>
      <text y="-4" text-anchor="middle">${escapeHtml(shortText(caption, 13))}</text>
      <text y="14" text-anchor="middle" class="node-label">${escapeHtml(shortText(labels, 16))}</text>
    </g>
  `;
}

function shortText(value, max) {
  const text = String(value ?? "");
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function candidateList(graph) {
  const candidates = graph.candidates || [];
  if (!candidates.length) return `<span class="muted-text">没有匹配到候选节点。</span>`;
  return candidates.map((node) => `
    <button class="candidate-node ${node.id === graph.centerId ? "active" : ""}" type="button" data-center-id="${node.id}">
      <strong>${escapeHtml(node.caption || `#${node.id}`)}</strong>
      <small>${escapeHtml((node.labels || []).join(" / ") || "Node")}</small>
    </button>
  `).join("");
}

function graphDetail(kind, id, graph) {
  if (kind === "node") {
    const node = (graph.nodes || []).find((item) => String(item.id) === String(id));
    if (!node) return "";
    return `<span class="badge">${escapeHtml((node.labels || []).join(" / ") || "Node")}</span><h3>${escapeHtml(node.caption || `#${node.id}`)}</h3><pre>${escapeHtml(JSON.stringify(node.properties || {}, null, 2))}</pre>`;
  }
  const rel = (graph.relationships || []).find((item) => String(item.id) === String(id));
  if (!rel) return "";
  return `<span class="badge blue">${escapeHtml(rel.type)}</span><h3>${escapeHtml(rel.source)} → ${escapeHtml(rel.target)}</h3><pre>${escapeHtml(JSON.stringify(rel.properties || {}, null, 2))}</pre>`;
}

function formatUpdatedAt(value) {
  if (!value) return "未更新";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function newsPage() {
  return `<div class="daily-news-layout">
    <section class="panel daily-news-head"><div><span class="eyebrow">DAILY DELTA FORCE INTEL</span><h2>今日资讯</h2><p>每日地图密码参考 KK 三角洲一图流的地图密码信息。</p></div><span id="daily-updated" class="muted-text">正在加载…</span></section>
    <section class="panel daily-card"><span class="badge amber">DAILY CODE</span><h3>今日密码</h3><div id="daily-password-grid" class="daily-password-grid">正在加载今日地图密码…</div></section>
    <section class="panel daily-card"><span class="badge blue">AI LOADOUT</span><h3>今日 AI 改枪推荐</h3><div id="daily-ai-recommendation" class="daily-copy">正在加载推荐…</div><details><summary>查看 AI 提示词</summary><pre id="daily-ai-prompt"></pre></details></section>
    <section class="panel daily-card daily-empty"><span class="badge">COMING SOON</span><h3>三角洲今日资讯</h3><p id="daily-news-content">更多三角洲今日资讯即将上线。</p></section>
  </div>`;
}

function adminPage() {
  if (!isAdminUser()) return `<section class="panel admin-denied"><span class="badge amber">RESTRICTED</span><h2>管理员权限必需</h2><p>请使用管理员账户登录后访问管理后台。</p></section>`;
  return `<div class="admin-layout">
    <section class="panel admin-github"><div class="section-title"><h3>GitHub &#20179;&#24211;&#38142;&#25509;</h3><span id="admin-github-status" class="form-status"></span></div><form id="admin-github-form" class="admin-form"><div class="field"><label for="admin-github-url">GitHub URL</label><input id="admin-github-url" type="url" placeholder="https://github.com/owner/repository" /></div><button class="primary-button" type="submit">&#20445;&#23384;&#38142;&#25509;</button></form></section>
    <section class="panel admin-head"><div><span class="eyebrow">CONTROL CENTER</span><h2>管理员界面</h2><p>管理用户、今日资讯内容与实时市场数据。</p></div><span class="badge">ADMIN</span></section>
    <section class="panel admin-users"><div class="section-title"><h3>用户信息</h3><button id="admin-users-refresh" class="ghost-button" type="button">刷新列表</button></div><div class="query-table-wrap"><table class="data-table"><thead><tr><th>用户</th><th>角色</th><th>显示名</th><th>会话数</th><th>创建时间</th><th>最近登录</th></tr></thead><tbody id="admin-users-body"><tr><td colspan="6">正在读取用户信息…</td></tr></tbody></table></div></section>
    <div class="admin-grid"><section class="panel admin-content"><div class="section-title"><h3>编辑今日资讯</h3><span id="admin-content-status" class="form-status"></span></div><form id="admin-content-form" class="admin-form"><div class="field"><label>今日地图密码</label><div class="admin-password-fields"><label>AZ3<input id="admin-password-az3" /></label><label>零号大坝<input id="admin-password-zero-dam" /></label><label>航天基地<input id="admin-password-space-city" /></label><label>潮汐监狱<input id="admin-password-tide-prison" /></label><label>巴克什<input id="admin-password-brakkesh" /></label><label>长弓溪谷<input id="admin-password-longbow" /></label></div></div><div class="field"><label for="admin-ai-recommendation">今日 AI 改枪推荐</label><textarea id="admin-ai-recommendation" rows="5"></textarea><small class="field-help">可直接修改；清空后保存即可删除当前推荐。</small></div><div class="field"><label for="admin-ai-prompt">AI 提示词</label><textarea id="admin-ai-prompt" rows="4"></textarea></div><div class="field"><label for="admin-news-content">三角洲今日资讯</label><textarea id="admin-news-content" rows="4"></textarea></div><button class="primary-button" type="submit">保存今日内容</button></form></section>
    <section class="panel admin-refresh"><div class="section-title"><h3>刷新市场数据</h3><span class="badge amber">8010</span></div><p>请求会转发至实时数据服务的 <code>POST /refresh</code>。</p><form id="admin-refresh-form" class="admin-form"><div class="field"><label for="refresh-kind">分类</label><select id="refresh-kind"><option value="">全部分类</option><option value="firearms">枪械</option><option value="attachments">配件</option><option value="armor">护甲</option><option value="helmets">头盔</option><option value="chest_rigs">胸挂</option><option value="backpacks">背包</option></select></div><div class="field"><label for="refresh-pages">测试页数（可选）</label><input id="refresh-pages" type="number" min="1" placeholder="留空即正式刷新" /></div><label class="check-field"><input id="refresh-save-partial" type="checkbox" /> 测试抓取也覆盖正式数据</label><button id="admin-refresh-submit" class="primary-button" type="submit">开始刷新</button><pre id="admin-refresh-result" class="admin-result">尚未执行刷新</pre></form></section></div>
  </div>`;
}

function bindNews() {
  api("/api/daily-content").then((content) => {
    const maps = [["AZ3", "passwordAz3"], ["零号大坝", "passwordZeroDam"], ["航天基地", "passwordSpaceCity"], ["潮汐监狱", "passwordTidePrison"], ["巴克什", "passwordBrakkesh"], ["长弓溪谷", "passwordLongbow"]];
    document.querySelector("#daily-password-grid").innerHTML = maps.map(([name, key]) => `<article class="password-map-card"><span>${name}</span><strong>${escapeHtml(content[key] || "待发布")}</strong></article>`).join("");
    const recommendation = content.aiRecommendation || "今日 AI 改枪推荐待发布";
    const recommendationTarget = document.querySelector("#daily-ai-recommendation");
    recommendationTarget.innerHTML = dailyLoadoutMarkup(recommendation, []);
    Promise.all([
      api("/api/market/items?limit=1000&type=attachments").catch(() => []),
      api("/api/market/items?limit=1000&type=firearms").catch(() => []),
    ]).then(([attachments, firearms]) => {
      recommendationTarget.innerHTML = dailyLoadoutMarkup(recommendation, [...attachments, ...firearms]);
    });
    document.querySelector("#daily-ai-prompt").textContent = content.aiPrompt || "";
    document.querySelector("#daily-news-content").textContent = content.newsContent || "更多三角洲今日资讯即将上线。";
    document.querySelector("#daily-updated").textContent = `更新于 ${formatUpdatedAt(content.updatedAt)}`;
  }).catch((error) => { document.querySelector("#daily-updated").textContent = error.message || "今日内容加载失败"; });
}

function bindAdmin() {
  const loadUsers = () => api("/api/admin/users").then((users) => { document.querySelector("#admin-users-body").innerHTML = users.length ? users.map((user) => `<tr><td><strong>${escapeHtml(user.username)}</strong></td><td><span class="badge">${escapeHtml(user.role || "USER")}</span></td><td>${escapeHtml(user.displayName)}</td><td>${user.sessionCount}</td><td>${escapeHtml(formatUpdatedAt(user.createdAt))}</td><td>${escapeHtml(formatUpdatedAt(user.lastLoginAt))}</td></tr>`).join("") : `<tr><td colspan="6">暂无用户</td></tr>`; }).catch((error) => { document.querySelector("#admin-users-body").innerHTML = `<tr><td colspan="6">${escapeHtml(error.message)}</td></tr>`; });
  const loadContent = () => api("/api/admin/daily-content").then((content) => { document.querySelector("#admin-password-az3").value = content.passwordAz3 || ""; document.querySelector("#admin-password-zero-dam").value = content.passwordZeroDam || ""; document.querySelector("#admin-password-space-city").value = content.passwordSpaceCity || ""; document.querySelector("#admin-password-tide-prison").value = content.passwordTidePrison || ""; document.querySelector("#admin-password-brakkesh").value = content.passwordBrakkesh || ""; document.querySelector("#admin-password-longbow").value = content.passwordLongbow || ""; document.querySelector("#admin-ai-recommendation").value = content.aiRecommendation || ""; document.querySelector("#admin-ai-prompt").value = content.aiPrompt || ""; document.querySelector("#admin-news-content").value = content.newsContent || ""; });
  const loadGithubUrl = () => api("/api/admin/daily-content").then((content) => { document.querySelector("#admin-github-url").value = content.githubUrl || ""; });
  document.querySelector("#admin-users-refresh").addEventListener("click", loadUsers);
  document.querySelector("#admin-github-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const status = document.querySelector("#admin-github-status");
    const githubUrl = document.querySelector("#admin-github-url").value.trim();
    if (githubUrl && !safeExternalUrl(githubUrl)) { status.textContent = "GitHub URL must start with http:// or https://"; return; }
    status.textContent = "Saving...";
    api("/api/admin/daily-content")
      .then((content) => api("/api/admin/daily-content", { method: "POST", body: JSON.stringify({ ...content, githubUrl }) }))
      .then((content) => { state.githubUrl = content.githubUrl || ""; renderTopActions(); status.textContent = "Saved"; })
      .catch((error) => { status.textContent = error.message || "Save failed"; });
  });
  document.querySelector("#admin-content-form").addEventListener("submit", (event) => { event.preventDefault(); const status = document.querySelector("#admin-content-status"); status.textContent = "正在保存…"; api("/api/admin/daily-content", { method: "POST", body: JSON.stringify({ passwordAz3: document.querySelector("#admin-password-az3").value.trim(), passwordZeroDam: document.querySelector("#admin-password-zero-dam").value.trim(), passwordSpaceCity: document.querySelector("#admin-password-space-city").value.trim(), passwordTidePrison: document.querySelector("#admin-password-tide-prison").value.trim(), passwordBrakkesh: document.querySelector("#admin-password-brakkesh").value.trim(), passwordLongbow: document.querySelector("#admin-password-longbow").value.trim(), aiRecommendation: document.querySelector("#admin-ai-recommendation").value.trim(), aiPrompt: document.querySelector("#admin-ai-prompt").value.trim(), newsContent: document.querySelector("#admin-news-content").value.trim() }) }).then(() => { status.textContent = "已保存"; }).catch((error) => { status.textContent = error.message || "保存失败"; }); });
  document.querySelector("#admin-refresh-form").addEventListener("submit", (event) => { event.preventDefault(); const submit = document.querySelector("#admin-refresh-submit"), result = document.querySelector("#admin-refresh-result"), pages = Number(document.querySelector("#refresh-pages").value); const request = {}; if (document.querySelector("#refresh-kind").value) request.kind = document.querySelector("#refresh-kind").value; if (pages > 0) request.max_pages = pages; if (document.querySelector("#refresh-save-partial").checked) request.save_partial = true; submit.disabled = true; result.textContent = "正在刷新，请勿关闭页面…"; api("/api/admin/market/refresh", { method: "POST", body: JSON.stringify(request) }).then((data) => { result.textContent = JSON.stringify(data, null, 2); }).catch((error) => { result.textContent = error.message || "刷新失败"; }).finally(() => { submit.disabled = false; }); });
  loadUsers(); loadContent(); loadGithubUrl();
}

function dataCenterPage() {
  return `
    <div class="data-center-layout">
      <section class="data-center-head" aria-labelledby="data-center-heading">
        <div>
          <div class="eyebrow">PERSONAL COMBAT ARCHIVE</div>
          <h2 id="data-center-heading">个人数据中心</h2>
          <p>战绩、收益与趋势仅绑定当前登录账号。WeGame Cookie 不会上传到本站。</p>
        </div>
        <div class="data-center-actions">
          <label class="dc-account-field" for="dc-account-select"><span>游戏账号</span><select id="dc-account-select" aria-label="当前游戏账号"><option value="">加载中…</option></select></label>
          <button id="dc-import-open" class="primary-button" type="button">导入战绩</button>
          <button id="dc-export" class="ghost-button" type="button">导出备份</button>
        </div>
      </section>
      <nav class="dc-tabs" role="tablist" aria-label="数据中心视图">
        ${[["overview","总览"],["records","战绩"],["analysis","分析"],["trends","趋势"],["items","物品"]].map(([key,label]) => `<button class="dc-tab${state.dataCenterTab === key ? " active" : ""}" type="button" role="tab" aria-selected="${state.dataCenterTab === key}" aria-controls="dc-content" data-dc-tab="${key}">${label}</button>`).join("")}
      </nav>
      <section id="dc-content" class="dc-content" role="tabpanel" aria-live="polite"><div class="dc-loading"><span class="spinner" aria-hidden="true"></span>正在读取个人战绩…</div></section>
      <dialog id="dc-import-dialog" class="dc-dialog">
        <form method="dialog" class="dc-dialog-card" id="dc-import-form">
          <div class="dc-dialog-head"><div><div class="eyebrow">LOCAL IMPORT</div><h3>导入个人战绩</h3></div><button class="dc-icon-button" value="cancel" type="submit" aria-label="关闭导入窗口">×</button></div>
          <p class="dc-help">选择数据中心 JSON 文件。服务端会忽略文件中的用户归属，并自动绑定到当前登录用户。</p>
          <label class="dc-file-drop" for="dc-import-file"><span>选择 JSON 或 ZIP 文件</span><small id="dc-import-file-name">支持本站 JSON，或原数据中心导出的 ZIP 备份</small><input id="dc-import-file" type="file" accept="application/json,.json,application/zip,.zip" /></label>
          <details class="dc-import-contract"><summary>查看导入格式</summary><pre>{
  "account": { "playerId": "游戏玩家ID", "playerName": "玩家名" },
  "battles": [{
    "roomId": "对局ID", "eventTime": "2026-07-21T12:00:00Z",
    "mapName": "零号大坝", "gameResult": 0, "playerKillCount": 3,
    "profitLoss": 1200000, "players": [], "items": []
  }]
}</pre></details>
          <div id="dc-import-status" class="form-status" role="status"></div>
          <div class="dc-dialog-actions"><button class="ghost-button" value="cancel" type="submit">取消</button><button id="dc-import-submit" class="primary-button" value="default" type="submit">开始导入</button></div>
        </form>
      </dialog>
      <dialog id="dc-detail-dialog" class="dc-dialog dc-detail-dialog"><div class="dc-dialog-card"><div class="dc-dialog-head"><div><div class="eyebrow">BATTLE DETAIL</div><h3>单局详情</h3></div><button id="dc-detail-close" class="dc-icon-button" type="button" aria-label="关闭详情">×</button></div><div id="dc-detail-content" class="dc-detail-content"></div></div></dialog>
    </div>`;
}

function dcNumber(value) { return Number(value || 0).toLocaleString("zh-CN"); }
function dcPercent(value) { return `${Number(value || 0).toFixed(1)}%`; }
function dcDuration(value) { const seconds = Math.max(0, Number(value || 0)); return `${Math.floor(seconds / 60)}分${Math.round(seconds % 60)}秒`; }
function dcDate(value) { if (!value) return "—"; const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value).replace("T", " ").slice(0, 19) : date.toLocaleString("zh-CN", { hour12: false }); }
function dcResult(value) { return Number(value) === 0 ? `<span class="dc-result success">成功撤离</span>` : `<span class="dc-result failed">未撤离</span>`; }
function dcQuery(extra = {}) {
  const params = new URLSearchParams();
  const filters = { ...state.dataCenterFilters, ...extra };
  Object.entries(filters).forEach(([key, value]) => { if (value !== "" && value !== null && value !== undefined) params.set(key, value); });
  return params.toString() ? `?${params.toString()}` : "";
}

function dcLoading(label = "正在读取个人战绩…") {
  const content = document.querySelector("#dc-content");
  if (content) content.innerHTML = `<div class="dc-loading"><span class="spinner" aria-hidden="true"></span>${escapeHtml(label)}</div>`;
}

function dcError(error) {
  const content = document.querySelector("#dc-content");
  if (content) content.innerHTML = `<div class="dc-empty"><strong>数据读取失败</strong><p>${escapeHtml(error?.message || "请稍后重试")}</p><button id="dc-retry" class="ghost-button" type="button">重新加载</button></div>`;
  document.querySelector("#dc-retry")?.addEventListener("click", loadDataCenterTab);
}

function bindDataCenter() {
  document.querySelectorAll("[data-dc-tab]").forEach((button) => button.addEventListener("click", () => {
    state.dataCenterTab = button.dataset.dcTab;
    state.dataCenterPage = 1;
    document.querySelectorAll("[data-dc-tab]").forEach((item) => {
      const selected = item === button;
      item.classList.toggle("active", selected);
      item.setAttribute("aria-selected", String(selected));
    });
    loadDataCenterTab();
  }));
  document.querySelector("#dc-account-select")?.addEventListener("change", (event) => {
    const accountId = event.target.value;
    if (!accountId) return;
    event.target.disabled = true;
    api("/api/data-center/accounts/select", { method: "POST", body: JSON.stringify({ accountId }) })
      .then(() => loadDataCenterSummary(true)).catch(dcError).finally(() => { event.target.disabled = false; });
  });
  const importDialog = document.querySelector("#dc-import-dialog");
  document.querySelector("#dc-import-open")?.addEventListener("click", () => importDialog.showModal());
  document.querySelector("#dc-import-file")?.addEventListener("change", (event) => {
    document.querySelector("#dc-import-file-name").textContent = event.target.files?.[0]?.name || "支持本项目导出的备份或规范化采集器数据";
  });
  document.querySelector("#dc-import-form")?.addEventListener("submit", async (event) => {
    if (event.submitter?.value === "cancel") return;
    event.preventDefault();
    const file = document.querySelector("#dc-import-file").files?.[0];
    const status = document.querySelector("#dc-import-status");
    const submit = document.querySelector("#dc-import-submit");
    if (!file) { status.textContent = "请先选择 JSON 文件"; return; }
    submit.disabled = true; status.textContent = "正在校验并导入…";
    try {
      let result;
      if (file.name.toLowerCase().endsWith(".zip")) {
        const form = new FormData(); form.append("file", file);
        const response = await fetch(`${API_BASE}/api/data-center/import/legacy-backup`, { method: "POST", headers: { Authorization: `Bearer ${state.user.token}` }, body: form });
        const data = await response.json(); if (!response.ok) throw new Error(data.message || data.error || "备份导入失败"); result = data;
      } else {
        const payload = JSON.parse(await file.text());
        result = await api("/api/data-center/import", { method: "POST", body: JSON.stringify(payload) });
      }
      status.textContent = `导入完成：新增 ${result.insertedBattles} 局，更新 ${result.updatedBattles} 局。`;
      setTimeout(() => { importDialog.close(); loadDataCenterSummary(true); }, 700);
    } catch (error) { status.textContent = error.message || "导入失败，请检查文件格式"; }
    finally { submit.disabled = false; }
  });
  document.querySelector("#dc-export")?.addEventListener("click", exportDataCenter);
  document.querySelector("#dc-detail-close")?.addEventListener("click", () => document.querySelector("#dc-detail-dialog").close());
  loadDataCenterSummary(false);
}

function loadDataCenterSummary(reloadTab) {
  return api("/api/data-center/summary").then((summary) => {
    state.dataCenterSummary = summary;
    const select = document.querySelector("#dc-account-select");
    const accounts = summary.accounts || [];
    if (select) {
      select.innerHTML = accounts.length ? accounts.map((account) => `<option value="${escapeHtml(account.id)}"${account.active ? " selected" : ""}>${escapeHtml(account.playerName)} · Lv.${dcNumber(account.level)}</option>`).join("") : `<option value="">尚未绑定账号</option>`;
      select.disabled = !accounts.length;
    }
    if (reloadTab || document.querySelector("#dc-content")) loadDataCenterTab();
  }).catch(dcError);
}

function loadDataCenterTab() {
  const summary = state.dataCenterSummary;
  if (!summary) { dcLoading(); return loadDataCenterSummary(false); }
  if (!summary.activeAccount) {
    document.querySelector("#dc-content").innerHTML = `<div class="dc-empty dc-empty-onboarding"><span class="dc-empty-mark">Δ</span><strong>还没有个人战绩</strong><p>从本地采集器或数据中心备份导入 JSON。导入内容将自动绑定当前登录用户。</p><button id="dc-empty-import" class="primary-button" type="button">导入第一份战绩</button></div>`;
    document.querySelector("#dc-empty-import")?.addEventListener("click", () => document.querySelector("#dc-import-dialog").showModal());
    return;
  }
  if (state.dataCenterTab === "overview") return loadDcOverview();
  if (state.dataCenterTab === "records") return loadDcRecords();
  if (state.dataCenterTab === "analysis") return loadDcAnalysis();
  if (state.dataCenterTab === "trends") return loadDcTrends();
  if (state.dataCenterTab === "items") return loadDcItems();
}

function dcMetric(label, value, hint, tone = "") {
  return `<article class="dc-metric ${tone}"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong><small>${escapeHtml(hint || "")}</small></article>`;
}

function dcRecordTable(rows, compact = false) {
  if (!rows?.length) return `<div class="dc-empty compact"><strong>暂无符合条件的战绩</strong><p>调整筛选条件，或导入新的对局数据。</p></div>`;
  return `<div class="dc-table-wrap"><table class="dc-table"><thead><tr><th>时间</th><th>地图 / 干员</th><th>结果</th><th>击杀</th><th>收益</th>${compact ? "" : "<th>时长</th>"}<th><span class="sr-only">操作</span></th></tr></thead><tbody>${rows.map((row) => `<tr><td data-label="时间">${escapeHtml(dcDate(row.eventTime))}</td><td data-label="地图 / 干员"><strong>${escapeHtml(row.mapName || "未知地图")}</strong><small>${escapeHtml(row.roleName || "未知干员")}</small></td><td data-label="结果">${dcResult(row.gameResult)}</td><td data-label="击杀" class="dc-number">${dcNumber(row.playerKillCount)}</td><td data-label="收益" class="dc-number ${Number(row.profitLoss) >= 0 ? "positive" : "negative"}">${Number(row.profitLoss) >= 0 ? "+" : ""}${dcNumber(row.profitLoss)}</td>${compact ? "" : `<td data-label="时长">${escapeHtml(dcDuration(row.durationSeconds))}</td>`}<td data-label="操作"><button class="dc-row-action" type="button" data-battle-id="${escapeHtml(row.id)}">查看详情</button></td></tr>`).join("")}</tbody></table></div>`;
}

function bindDcRecordRows() {
  document.querySelectorAll("[data-battle-id]").forEach((button) => button.addEventListener("click", () => openDcBattle(button.dataset.battleId)));
}

function loadDcOverview() {
  dcLoading("正在汇总战绩…");
  Promise.all([api("/api/data-center/summary"), api("/api/data-center/records?page=1&size=6")]).then(([summary, records]) => {
    state.dataCenterSummary = summary;
    document.querySelector("#dc-content").innerHTML = `
      <div class="dc-metric-grid">
        ${dcMetric("总对局", dcNumber(summary.totalBattles), `${dcNumber(summary.escapedBattles)} 次撤离`)}
        ${dcMetric("撤离率", dcPercent(summary.escapeRate), "成功撤离 / 总对局", "accent")}
        ${dcMetric("玩家击杀", dcNumber(summary.playerKills), "累计对局击杀")}
        ${dcMetric("累计收益", dcNumber(summary.totalProfit), `场均 ${dcNumber(summary.averageProfit)}`, Number(summary.totalProfit) >= 0 ? "positive" : "negative")}
        ${dcMetric("平均时长", dcDuration(summary.averageDurationSeconds), "单局平均用时")}
      </div>
      <section class="dc-panel"><div class="dc-panel-head"><div><h3>最近战绩</h3><p>当前账号最近 6 场对局</p></div><button class="ghost-button" id="dc-view-all" type="button">查看全部</button></div>${dcRecordTable(records.data || [], true)}</section>
      <section class="dc-account-card"><div><span class="dc-account-avatar">${escapeHtml((summary.activeAccount.playerName || "玩").slice(0,1))}</span><div><strong>${escapeHtml(summary.activeAccount.playerName)}</strong><small>玩家 ID：${escapeHtml(summary.activeAccount.gamePlayerId)} · Lv.${dcNumber(summary.activeAccount.level)}</small></div></div><button id="dc-delete-account" class="dc-danger-button" type="button">删除此账号数据</button></section>`;
    bindDcRecordRows();
    document.querySelector("#dc-view-all")?.addEventListener("click", () => { state.dataCenterTab = "records"; document.querySelector('[data-dc-tab="records"]').click(); });
    document.querySelector("#dc-delete-account")?.addEventListener("click", deleteDcAccount);
  }).catch(dcError);
}

function dcFilterBar(extra = "") {
  const f = state.dataCenterFilters;
  return `<form id="dc-filter-form" class="dc-filter-bar">
    <label><span>开始日期</span><input id="dc-filter-start" type="date" value="${escapeHtml(f.start)}" /></label>
    <label><span>结束日期</span><input id="dc-filter-end" type="date" value="${escapeHtml(f.end)}" /></label>
    ${extra}
    <button class="primary-button" type="submit">应用筛选</button><button id="dc-filter-reset" class="ghost-button" type="button">重置</button>
  </form>`;
}

function bindDcFilters(loader) {
  document.querySelector("#dc-filter-form")?.addEventListener("submit", (event) => {
    event.preventDefault(); state.dataCenterFilters.start = document.querySelector("#dc-filter-start")?.value || ""; state.dataCenterFilters.end = document.querySelector("#dc-filter-end")?.value || "";
    const map = document.querySelector("#dc-filter-map"), result = document.querySelector("#dc-filter-result");
    if (map) state.dataCenterFilters.map = map.value; if (result) state.dataCenterFilters.result = result.value;
    state.dataCenterPage = 1; loader();
  });
  document.querySelector("#dc-filter-reset")?.addEventListener("click", () => { state.dataCenterFilters = { map: "", result: "", start: "", end: "" }; state.dataCenterPage = 1; loader(); });
}

function loadDcRecords() {
  dcLoading("正在读取战绩列表…");
  api(`/api/data-center/records${dcQuery({ page: state.dataCenterPage, size: state.dataCenterSize })}`).then((result) => {
    const totalPages = Math.max(1, Math.ceil(Number(result.total || 0) / state.dataCenterSize));
    const maps = result.maps || [];
    document.querySelector("#dc-content").innerHTML = `${dcFilterBar(`<label><span>地图</span><select id="dc-filter-map"><option value="">全部地图</option>${maps.map((name) => `<option${state.dataCenterFilters.map === name ? " selected" : ""}>${escapeHtml(name)}</option>`).join("")}</select></label><label><span>结果</span><select id="dc-filter-result"><option value="">全部结果</option><option value="0"${state.dataCenterFilters.result === "0" ? " selected" : ""}>成功撤离</option><option value="1"${state.dataCenterFilters.result === "1" ? " selected" : ""}>未撤离</option></select></label>`)}` +
      `<section class="dc-panel"><div class="dc-panel-head"><div><h3>战绩列表</h3><p>共 ${dcNumber(result.total)} 场对局</p></div></div>${dcRecordTable(result.data || [])}<div class="dc-pagination"><button id="dc-page-prev" class="ghost-button" type="button"${state.dataCenterPage <= 1 ? " disabled" : ""}>上一页</button><span>第 ${state.dataCenterPage} / ${totalPages} 页</span><button id="dc-page-next" class="ghost-button" type="button"${state.dataCenterPage >= totalPages ? " disabled" : ""}>下一页</button></div></section>`;
    bindDcFilters(loadDcRecords); bindDcRecordRows();
    document.querySelector("#dc-page-prev")?.addEventListener("click", () => { state.dataCenterPage--; loadDcRecords(); });
    document.querySelector("#dc-page-next")?.addEventListener("click", () => { state.dataCenterPage++; loadDcRecords(); });
  }).catch(dcError);
}

function dcBars(title, rows, valueKey, formatter = dcNumber) {
  const max = Math.max(1, ...(rows || []).map((row) => Math.abs(Number(row[valueKey] || 0))));
  return `<section class="dc-panel dc-analysis-panel"><div class="dc-panel-head"><div><h3>${escapeHtml(title)}</h3><p>按当前筛选范围聚合</p></div></div>${rows?.length ? `<div class="dc-bars">${rows.map((row) => `<div class="dc-bar-row"><div><strong>${escapeHtml(row.label)}</strong><span>${dcNumber(row.battles)} 局 · 撤离率 ${dcPercent(row.escapeRate)}</span></div><div class="dc-bar-track"><span style="width:${Math.max(2, Math.abs(Number(row[valueKey] || 0)) / max * 100)}%"></span></div><b>${escapeHtml(formatter(row[valueKey]))}</b></div>`).join("")}</div>` : `<div class="dc-empty compact"><p>暂无分析数据</p></div>`}</section>`;
}

function loadDcAnalysis() {
  dcLoading("正在计算表现分布…");
  api(`/api/data-center/analysis${dcQuery({ map: null, result: null })}`).then((data) => {
    document.querySelector("#dc-content").innerHTML = dcFilterBar() + `<div class="dc-analysis-grid">${dcBars("地图表现", data.maps, "profit")}${dcBars("干员表现", data.roles, "playerKills")}${dcBars("撤离结果", data.results, "battles")}</div><section class="dc-panel"><div class="dc-panel-head"><div><h3>高收益对局</h3><p>当前范围收益最高的 5 局</p></div></div>${dcRecordTable(data.highlights || [], true)}</section>`;
    bindDcFilters(loadDcAnalysis); bindDcRecordRows();
  }).catch(dcError);
}

function dcTrendSvg(points) {
  if (!points?.length) return `<div class="dc-empty compact"><p>暂无趋势数据</p></div>`;
  const width = 900, height = 260, pad = 34; const values = points.map((p) => Number(p.profit || 0)); const min = Math.min(0, ...values), max = Math.max(1, ...values); const range = Math.max(1, max - min);
  const coords = points.map((p, i) => ({ x: points.length === 1 ? width / 2 : pad + i * (width - pad * 2) / (points.length - 1), y: pad + (max - Number(p.profit || 0)) * (height - pad * 2) / range, p }));
  const path = coords.map((c, i) => `${i ? "L" : "M"}${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(" ");
  return `<div class="dc-chart"><svg viewBox="0 0 ${width} ${height}" role="img" aria-label="收益趋势折线图"><line x1="${pad}" y1="${height-pad}" x2="${width-pad}" y2="${height-pad}" class="dc-chart-axis"></line><path d="${path}" class="dc-chart-line"></path>${coords.map((c) => `<circle cx="${c.x}" cy="${c.y}" r="5" tabindex="0"><title>${escapeHtml(c.p.label)}：${dcNumber(c.p.profit)}</title></circle>`).join("")}</svg></div>`;
}

function loadDcTrends() {
  dcLoading("正在生成趋势…");
  const bucket = state.dataCenterFilters.bucket || "day";
  api(`/api/data-center/trends${dcQuery({ map: null, result: null, bucket })}`).then((data) => {
    document.querySelector("#dc-content").innerHTML = dcFilterBar(`<label><span>粒度</span><select id="dc-trend-bucket"><option value="day"${bucket === "day" ? " selected" : ""}>按日</option><option value="week"${bucket === "week" ? " selected" : ""}>按周</option><option value="month"${bucket === "month" ? " selected" : ""}>按月</option></select></label>`) + `<section class="dc-panel"><div class="dc-panel-head"><div><h3>收益趋势</h3><p>折线节点可通过键盘聚焦查看精确数值</p></div></div>${dcTrendSvg(data.data || [])}<div class="dc-table-wrap"><table class="dc-table"><thead><tr><th>周期</th><th>对局</th><th>撤离率</th><th>击杀</th><th>收益</th></tr></thead><tbody>${(data.data || []).map((row) => `<tr><td data-label="周期">${escapeHtml(row.label)}</td><td data-label="对局">${dcNumber(row.battles)}</td><td data-label="撤离率">${dcPercent(row.escapeRate)}</td><td data-label="击杀">${dcNumber(row.playerKills)}</td><td data-label="收益" class="dc-number ${Number(row.profit) >= 0 ? "positive" : "negative"}">${dcNumber(row.profit)}</td></tr>`).join("")}</tbody></table></div></section>`;
    bindDcFilters(() => { state.dataCenterFilters.bucket = document.querySelector("#dc-trend-bucket")?.value || "day"; loadDcTrends(); });
  }).catch(dcError);
}

function loadDcItems() {
  dcLoading("正在汇总带出物品…");
  api(`/api/data-center/items${dcQuery({ map: null, result: null })}`).then((data) => {
    document.querySelector("#dc-content").innerHTML = dcFilterBar() + `<section class="dc-panel"><div class="dc-panel-head"><div><h3>带出物品</h3><p>累计估值 ${dcNumber(data.totalValue)}</p></div></div>${data.data?.length ? `<div class="dc-table-wrap"><table class="dc-table"><thead><tr><th>物品</th><th>品质</th><th>数量</th><th>最高单价</th><th>累计价值</th></tr></thead><tbody>${data.data.map((item) => `<tr><td data-label="物品"><strong>${escapeHtml(item.itemName || item.itemId || "未知物品")}</strong></td><td data-label="品质"><span class="dc-grade grade-${Number(item.grade || 0)}">${dcNumber(item.grade)} 级</span></td><td data-label="数量">${dcNumber(item.quantity)}</td><td data-label="最高单价" class="dc-number">${dcNumber(item.maxUnitPrice)}</td><td data-label="累计价值" class="dc-number positive">${dcNumber(item.totalValue)}</td></tr>`).join("")}</tbody></table></div>` : `<div class="dc-empty compact"><strong>暂无物品数据</strong><p>导入包含 items 的战绩后会显示汇总。</p></div>`}</section>`;
    bindDcFilters(loadDcItems);
  }).catch(dcError);
}

function openDcBattle(battleId) {
  const dialog = document.querySelector("#dc-detail-dialog"), content = document.querySelector("#dc-detail-content");
  content.innerHTML = `<div class="dc-loading"><span class="spinner"></span>正在读取详情…</div>`; dialog.showModal();
  api(`/api/data-center/records/${encodeURIComponent(battleId)}`).then((data) => {
    const b = data.battle;
    content.innerHTML = `<div class="dc-detail-hero"><div><span>${escapeHtml(dcDate(b.eventTime))}</span><h4>${escapeHtml(b.mapName || "未知地图")}</h4><p>${escapeHtml(b.roleName || "未知干员")} · ${escapeHtml(dcDuration(b.durationSeconds))}</p></div>${dcResult(b.gameResult)}</div>
      <div class="dc-detail-metrics">${dcMetric("玩家击杀", dcNumber(b.playerKillCount), `${dcNumber(b.assistCount)} 次助攻`)}${dcMetric("带出价值", dcNumber(b.gainedValue), "本局获得物资")}${dcMetric("净收益", `${Number(b.profitLoss)>=0?"+":""}${dcNumber(b.profitLoss)}`, `战备 ${dcNumber(b.equipmentValue)}`, Number(b.profitLoss)>=0?"positive":"negative")}</div>
      <section><h4>队伍表现</h4>${data.players?.length ? `<div class="dc-table-wrap"><table class="dc-table"><thead><tr><th>玩家</th><th>干员</th><th>击杀</th><th>助攻</th><th>收益</th></tr></thead><tbody>${data.players.map((p) => `<tr><td data-label="玩家"><strong>${escapeHtml(p.playerName || p.gamePlayerId || "未知玩家")}</strong>${p.self ? `<small>本人</small>` : ""}</td><td data-label="干员">${escapeHtml(p.roleName || "—")}</td><td data-label="击杀">${dcNumber(p.playerKillCount)}</td><td data-label="助攻">${dcNumber(p.assistCount)}</td><td data-label="收益" class="dc-number">${dcNumber(p.profitLoss)}</td></tr>`).join("")}</tbody></table></div>` : `<p class="dc-help">该对局没有队伍明细。</p>`}</section>
      <section><h4>带出物品</h4>${data.items?.length ? `<div class="dc-item-grid">${data.items.map((item) => `<article><div><strong>${escapeHtml(item.itemName || "未知物品")}</strong><span>${dcNumber(item.quantity)} 件 · ${dcNumber(item.unitPrice)} / 件</span></div><b>${dcNumber(item.totalValue)}</b></article>`).join("")}</div>` : `<p class="dc-help">该对局没有物品明细。</p>`}</section>`;
  }).catch((error) => { content.innerHTML = `<div class="dc-empty"><strong>详情读取失败</strong><p>${escapeHtml(error.message)}</p></div>`; });
}

function exportDataCenter() {
  const button = document.querySelector("#dc-export"); button.disabled = true;
  api("/api/data-center/export").then((data) => {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }); const url = URL.createObjectURL(blob); const anchor = document.createElement("a");
    anchor.href = url; anchor.download = `delta-force-records-${new Date().toISOString().slice(0,10)}.json`; anchor.click(); URL.revokeObjectURL(url);
  }).catch(dcError).finally(() => { button.disabled = false; });
}

function deleteDcAccount() {
  const account = state.dataCenterSummary?.activeAccount;
  if (!account || !confirm(`确定删除「${account.playerName}」的全部战绩吗？此操作不可撤销。`)) return;
  api(`/api/data-center/accounts/${encodeURIComponent(account.id)}`, { method: "DELETE" }).then(() => { state.dataCenterSummary = null; loadDataCenterSummary(true); }).catch(dcError);
}

function blankPage() {
  return `<div class="blank-view" aria-hidden="true"></div>`;
}

function loginPage() {
  const notice = sessionStorage.getItem("auth-notice") || "";
  sessionStorage.removeItem("auth-notice");
  return `
    <div class="login-view">
      <div class="login-art" aria-hidden="true">
        <div class="eyebrow">PLAYER WORKSPACE</div>
        <h2>一处管理你的对话、行情与战绩。</h2>
        <p>登录后即可安全保存会话、查询知识图谱，并同步个人战绩分析。</p>
        <div class="stat-strip">
          <div class="stat-tile"><strong>安全</strong><span>会话级登录凭证</span></div>
          <div class="stat-tile"><strong>连续</strong><span>保留对话上下文</span></div>
          <div class="stat-tile"><strong>专属</strong><span>隔离个人数据</span></div>
        </div>
      </div>
      <section class="login-box" aria-labelledby="auth-title">
        <h2 id="auth-title">登录</h2>
        ${notice ? `<div class="auth-notice">${escapeHtml(notice)}</div>` : ""}
        <div class="auth-tabs">
          <button class="chip-button active" type="button" data-auth-mode="login">登录</button>
          <button id="registration-tab" class="chip-button" type="button" data-auth-mode="register" aria-describedby="registration-status" disabled>注册</button>
        </div>
        <div id="registration-status" class="registration-status" role="status" aria-live="polite">正在读取注册状态…</div>
        <form id="auth-form">
          <div class="field"><label for="auth-username">账号</label><input id="auth-username" name="username" placeholder="3–64 位字母、数字或中文" autocomplete="username" minlength="3" maxlength="64" required /></div>
          <div class="field"><label for="auth-password">密码</label><div class="password-field"><input id="auth-password" name="password" type="password" placeholder="输入密码" autocomplete="current-password" maxlength="128" required /><button id="password-toggle" class="password-toggle" type="button" aria-label="显示密码" aria-pressed="false">显示</button></div><small id="password-help" class="field-help">请输入你的账号密码。</small></div>
          <button class="primary-button" type="submit" id="auth-submit">登录</button>
          <div class="form-status" id="auth-status" role="status" aria-live="polite"></div>
        </form>
      </section>
    </div>
  `;
}

function render() {
  const route = location.hash.replace("#", "") || "/";
  const normalized = pageTitles[route] ? route : "/";
  if (normalized === "/admin") {
    location.replace("/admin.html");
    return;
  }
  if (!state.user && !publicRoutes.has(normalized)) {
    sessionStorage.setItem("auth-notice", `请先登录后再使用「${pageTitles[normalized]}」。`);
    location.hash = "#/login";
    return;
  }
  setActive(normalized);
  renderTopActions();
  const pages = {
    "/": homePage,
    "/chat": chatPage,
    "/market": marketPage,
    "/data-center": dataCenterPage,
    "/gun-modifier": modifierPage,
    "/knowledge": knowledgePage,
    "/readiness": readinessPage,
    "/news": newsPage,
    "/admin": adminPage,
    "/login": loginPage,
  };
  view.innerHTML = pages[normalized]();
  view.classList.toggle("home-view", normalized === "/");
  bindPageEvents(normalized);
  const drawerWasOpen = sidebar.classList.contains("open");
  closeSidebar();
  if (drawerWasOpen) view.focus({ preventScroll: true });
}

function bindPageEvents(route) {
  if (route === "/chat") bindChat();
  if (route === "/market") bindMarket();
  if (route === "/data-center") bindDataCenter();
  if (route === "/readiness") bindReadiness();
  if (route === "/gun-modifier") bindModifier();
  if (route === "/knowledge") bindKnowledge();
  if (route === "/news") bindNews();
  if (route === "/admin" && isAdminUser()) bindAdmin();
  if (route === "/login") bindAuth();
}

function bindChat() {
  bindMobileChatPanels();
  const archiveToggle = document.querySelector("#archive-toggle-button");
  archiveToggle.textContent = state.showArchivedSessions ? "查看当前对话" : "查看归档";
  archiveToggle.addEventListener("click", () => {
    state.showArchivedSessions = !state.showArchivedSessions;
    archiveToggle.textContent = state.showArchivedSessions ? "查看当前对话" : "查看归档";
    loadSessions();
  });
  document.querySelector("#new-session-button").addEventListener("click", () => {
    state.activeSessionId = "";
    state.reactEvents = [];
    state.selectedReactEventId = "";
    state.selectedReactMessageId = "";
    state.messageReactEvents = {};
    state.conversation = { activeTaskId: "", taskStatus: "" };
    state.nextConversationMode = "new";
    sessionStorage.removeItem("delta-force-session");
    document.querySelector("#messages").innerHTML = defaultChatMessages();
    renderReactTimeline();
    renderConversationStatus();
    renderSessionList();
  });
  document.querySelector("#export-react-button").addEventListener("click", exportReactLog);
  loadSessions().then(() => {
    if (state.activeSessionId) loadSessionMessages(state.activeSessionId);
  });
  document.querySelector("#chat-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const input = document.querySelector("#chat-input");
    const text = input.value.trim();
    if (!text || state.chatSending) return;
    const submit = event.currentTarget.querySelector('button[type="submit"]');
    const messages = document.querySelector("#messages");
    const assistantId = `assistant-${Date.now()}`;
    const messageId = typeof window.crypto?.randomUUID === "function" ? window.crypto.randomUUID() : `message-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const conversationMode = state.nextConversationMode || "auto";
    state.nextConversationMode = "auto";
    state.chatSending = true;
    input.disabled = true;
    if (submit) submit.disabled = true;
    state.reactEvents = [];
    state.selectedReactEventId = "";
    state.selectedReactMessageId = assistantId;
    state.messageReactEvents[assistantId] = state.reactEvents;
    state.gunModReplies[assistantId] = { content: "", events: state.reactEvents, prompt: text };
    const initialSessionId = state.activeSessionId;
    const pending = {
      key: initialSessionId || `pending:${assistantId}`,
      sessionId: initialSessionId,
      assistantId,
      prompt: text,
      content: "",
      events: state.reactEvents,
      status: "running",
      error: "",
      activeTaskId: "",
      taskStatus: "",
    };
    state.pendingChats[pending.key] = pending;
    renderReactTimeline();
    messages.insertAdjacentHTML("beforeend", messageMarkup({ role: "user", content: text }));
    messages.insertAdjacentHTML("beforeend", assistantStreamMarkup(assistantId));
    bindMessageReactClicks();
    input.value = "";
    messages.scrollTop = messages.scrollHeight;
    try {
      await streamChat(pending, messageId, conversationMode);
    } catch (error) {
      pending.status = "failed";
      pending.error = error.message || "后端暂未连接或 FastAPI/Neo4j 不可用，当前无法获得真实回复。";
      refreshPendingChat(pending);
    } finally {
      state.chatSending = false;
      const currentInput = document.querySelector("#chat-input");
      const currentSubmit = document.querySelector('#chat-form button[type="submit"]');
      if (currentInput) {
        currentInput.disabled = false;
        currentInput.focus();
      }
      if (currentSubmit) currentSubmit.disabled = false;
    }
  });
}

function bindMarket() {
  const renderPage = () => {
    const total = state.marketItems.length;
    const pageCount = Math.max(1, Math.ceil(total / MARKET_PAGE_SIZE));
    state.marketPage = Math.min(Math.max(state.marketPage, 1), pageCount);
    const start = (state.marketPage - 1) * MARKET_PAGE_SIZE;
    const pageItems = state.marketItems.slice(start, start + MARKET_PAGE_SIZE);
    document.querySelector("#market-body").innerHTML = marketRows(pageItems);

    const pagination = document.querySelector("#market-pagination");
    pagination.hidden = total === 0;
    document.querySelector("#market-page-info").textContent = total ? `第 ${state.marketPage} / ${pageCount} 页，共 ${total} 件` : "";
    document.querySelector("#market-prev").disabled = state.marketPage <= 1;
    document.querySelector("#market-next").disabled = state.marketPage >= pageCount;
  };

  const load = () => {
    const keyword = document.querySelector("#market-search").value.trim();
    const type = document.querySelector("#market-type").value;
    state.marketPage = 1;
    api(`/api/market/items?limit=1000&keyword=${encodeURIComponent(keyword)}&type=${encodeURIComponent(type)}`)
      .then((items) => {
        state.marketItems = items || [];
        renderPage();
      })
      .catch(() => {
        state.marketItems = mockMarketItems;
        renderPage();
      });
  };
  document.querySelector("#market-search").addEventListener("input", load);
  document.querySelector("#market-type").addEventListener("change", load);
  document.querySelector("#market-refresh").addEventListener("click", load);
  document.querySelector("#market-prev").addEventListener("click", () => { state.marketPage -= 1; renderPage(); });
  document.querySelector("#market-next").addEventListener("click", () => { state.marketPage += 1; renderPage(); });
  load();
}

function bindKnowledge() {
  let latestGraph = null;
  let latestKeyword = "";
  const input = document.querySelector("#graph-question");
  const graphView = document.querySelector("#graph-view");
  const status = document.querySelector("#graph-status");
  const count = document.querySelector("#knowledge-count");
  const detail = document.querySelector("#graph-detail");
  const candidates = document.querySelector("#candidate-list");

  const bindGraphSelection = () => {
    graphView.querySelectorAll("[data-graph-kind]").forEach((item) => {
      item.addEventListener("click", () => {
        detail.innerHTML = graphDetail(item.dataset.graphKind, item.dataset.graphId, latestGraph);
      });
    });
  };

  const bindCandidates = () => {
    candidates.querySelectorAll("[data-center-id]").forEach((button) => {
      button.addEventListener("click", () => loadGraph(latestKeyword, button.dataset.centerId));
    });
  };

  const loadGraph = (keyword, centerId = "") => {
    graphView.innerHTML = `<div class="empty-state">正在查询节点关系...</div>`;
    status.textContent = `正在 sjzdata 中定位「${keyword || "任意节点"}」`;
    const centerPart = centerId ? `&centerId=${encodeURIComponent(centerId)}` : "";
    return api(`/api/knowledge/neighborhood?limit=40&keyword=${encodeURIComponent(keyword)}${centerPart}`)
      .then((result) => {
        latestGraph = result;
        count.textContent = `${(result.relationships || []).length} relations`;
        status.textContent = result.centerId == null ? "没有找到匹配节点" : `已展示中心节点 #${result.centerId} 的直接关系`;
        candidates.innerHTML = candidateList(result);
        graphView.innerHTML = graphSvg(result);
        detail.innerHTML = `<div class="empty-state compact">点击节点或关系查看属性。</div>`;
        bindCandidates();
        bindGraphSelection();
      })
      .catch((error) => {
        count.textContent = "0 relations";
        status.textContent = "查询失败";
        graphView.innerHTML = `<div class="empty-state">${escapeHtml(error.message || "Neo4j 查询失败")}</div>`;
      });
  };

  document.querySelector("#graph-search-form").addEventListener("submit", (event) => {
    event.preventDefault();
    latestKeyword = input.value.trim();
    loadGraph(latestKeyword);
  });
}

function bindAuth() {
  let mode = "login";
  const password = document.querySelector("#auth-password");
  const passwordToggle = document.querySelector("#password-toggle");
  const passwordHelp = document.querySelector("#password-help");
  const registrationTab = document.querySelector("#registration-tab");
  const registrationStatus = document.querySelector("#registration-status");
  api("/api/auth/registration")
    .then((result) => {
      const enabled = Boolean(result?.enabled);
      registrationTab.disabled = !enabled;
      registrationStatus.textContent = enabled ? "管理员已开放新用户注册。" : "管理员当前未开放新用户注册。";
      registrationStatus.classList.toggle("enabled", enabled);
    })
    .catch(() => {
      registrationTab.disabled = true;
      registrationStatus.textContent = "暂时无法确认注册状态，请稍后重试。";
    });
  passwordToggle.addEventListener("click", () => {
    const visible = password.type === "text";
    password.type = visible ? "password" : "text";
    passwordToggle.textContent = visible ? "显示" : "隐藏";
    passwordToggle.setAttribute("aria-label", visible ? "显示密码" : "隐藏密码");
    passwordToggle.setAttribute("aria-pressed", String(!visible));
  });
  document.querySelectorAll("[data-auth-mode]").forEach((button) => {
    button.addEventListener("click", () => {
      mode = button.dataset.authMode;
      document.querySelectorAll("[data-auth-mode]").forEach((item) => item.classList.toggle("active", item === button));
      document.querySelector("#auth-title").textContent = mode === "login" ? "登录" : "注册";
      document.querySelector("#auth-submit").textContent = mode === "login" ? "登录" : "创建账号";
      password.autocomplete = mode === "login" ? "current-password" : "new-password";
      password.minLength = mode === "login" ? 0 : 10;
      password.placeholder = mode === "login" ? "输入密码" : "至少 10 位";
      passwordHelp.textContent = mode === "login" ? "请输入你的账号密码。" : "密码需为 10–128 位；建议使用 16 位以上的独立密码。";
      document.querySelector("#auth-status").textContent = "";
    });
  });
  document.querySelector("#auth-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const username = document.querySelector("#auth-username").value.trim();
    const password = document.querySelector("#auth-password").value;
    const status = document.querySelector("#auth-status");
    status.textContent = mode === "login" ? "正在登录…" : "正在创建账号…";
    const submit = document.querySelector("#auth-submit");
    submit.disabled = true;
    api(`/api/auth/${mode}`, { method: "POST", body: JSON.stringify({ username, password }) })
      .then((user) => {
        setUser(user);
        status.textContent = "登录成功";
        location.hash = "#/chat";
      })
      .catch((error) => {
        status.textContent = error.message || "操作失败，请稍后重试。";
      })
      .finally(() => { submit.disabled = false; });
  });
}

function playButtonRipple(button, clientX, clientY) {
  if (!button || button.disabled || button.getAttribute("aria-disabled") === "true") return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const bounds = button.getBoundingClientRect();
  const x = Number.isFinite(clientX) ? clientX - bounds.left : bounds.width / 2;
  const y = Number.isFinite(clientY) ? clientY - bounds.top : bounds.height / 2;
  button.style.setProperty("--button-ripple-x", `${x}px`);
  button.style.setProperty("--button-ripple-y", `${y}px`);
  button.classList.remove("is-rippling");
  void button.offsetWidth;
  button.classList.add("is-rippling");
}

function syncMobileChatPanels() {
  if (!mobileChatMediaQuery) return;
  document.querySelectorAll("[data-mobile-chat-panel]").forEach((panel) => {
    const trigger = panel.querySelector(".mobile-chat-panel-toggle");
    if (!trigger) return;
    if (mobileChatMediaQuery.matches) {
      panel.classList.remove("is-expanded");
      trigger.setAttribute("aria-expanded", "false");
    } else {
      panel.classList.remove("is-expanded");
      trigger.setAttribute("aria-expanded", "true");
    }
  });
}

function bindMobileChatPanels() {
  if (!mobileChatMediaQuery) {
    mobileChatMediaQuery = window.matchMedia("(max-width: 720px) and (orientation: portrait)");
    mobileChatMediaQuery.addEventListener?.("change", syncMobileChatPanels);
  }
  syncMobileChatPanels();
  document.querySelectorAll("[data-mobile-chat-panel]").forEach((panel) => {
    const trigger = panel.querySelector(".mobile-chat-panel-toggle");
    trigger?.addEventListener("click", () => {
      if (!mobileChatMediaQuery.matches) return;
      const expanded = panel.classList.toggle("is-expanded");
      trigger.setAttribute("aria-expanded", String(expanded));
    });
  });
}

const BUTTON_MOTION_SELECTOR = "button, a.primary-button, a.ghost-button, a.chip-button, a.tool-button";

function bindButtonMotion() {
  document.addEventListener("pointerdown", (event) => {
    const button = event.target.closest(BUTTON_MOTION_SELECTOR);
    if (button) playButtonRipple(button, event.clientX, event.clientY);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const button = event.target.closest(BUTTON_MOTION_SELECTOR);
    if (button && !event.repeat) playButtonRipple(button);
  });
  document.addEventListener("animationend", (event) => {
    if (event.animationName === "button-ripple") event.target.classList.remove("is-rippling");
  });
}

function openSidebar() {
  sidebar.inert = false;
  sidebar.removeAttribute("aria-hidden");
  sidebar.classList.add("open");
  document.body.classList.add("sidebar-open");
  menuButton.setAttribute("aria-expanded", "true");
  sidebar.querySelector("a, button")?.focus();
}

function closeSidebar() {
  sidebar.classList.remove("open");
  document.body.classList.remove("sidebar-open");
  menuButton.setAttribute("aria-expanded", "false");
  syncSidebarAccessibility();
}

function syncSidebarAccessibility() {
  const hiddenDrawer = window.matchMedia("(max-width: 1280px)").matches && !sidebar.classList.contains("open");
  sidebar.inert = hiddenDrawer;
  if (hiddenDrawer) sidebar.setAttribute("aria-hidden", "true");
  else sidebar.removeAttribute("aria-hidden");
}

function loadSystemStatus() {
  const status = document.querySelector("#system-status");
  if (!status) return;
  fetch("/api/health", { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(6000) })
    .then((response) => response.json().then((body) => ({ ok: response.ok && body.ok, body })))
    .then(({ ok }) => {
      status.innerHTML = `<span class="pulse ${ok ? "" : "offline"}"></span><span>${ok ? "服务运行正常" : "部分服务不可用"}</span>`;
    })
    .catch(() => { status.innerHTML = `<span class="pulse offline"></span><span>服务状态未知</span>`; });
}

menuButton.addEventListener("click", () => sidebar.classList.contains("open") ? closeSidebar() : openSidebar());
sidebarBackdrop.addEventListener("click", () => { menuButton.focus(); closeSidebar(); });
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && sidebar.classList.contains("open")) { menuButton.focus(); closeSidebar(); }
});
window.addEventListener("resize", syncSidebarAccessibility);
document.addEventListener("error", (event) => {
  if (event.target instanceof HTMLImageElement && event.target.closest(".attachment-thumbnail")) {
    event.target.closest(".attachment-thumbnail").remove();
  }
}, true);
sidebarCollapseButton.addEventListener("click", () => {
  const collapsed = !appShell.classList.contains("sidebar-collapsed");
  localStorage.setItem("delta-force-sidebar-collapsed", String(collapsed));
  applySidebarState(collapsed);
});
window.addEventListener("hashchange", render);
bindButtonMotion();
applyTheme();
applyLocale();
syncSidebarAccessibility();
render();
loadSiteConfig();
loadSystemStatus();
