const view = document.querySelector("#app-view");
const title = document.querySelector("#page-title");
const sidebar = document.querySelector(".sidebar");
const menuButton = document.querySelector(".menu-button");
const topActions = document.querySelector("#top-actions");
const API_BASE = "";

const state = {
  user: JSON.parse(localStorage.getItem("delta-force-user") || "null"),
  activeSessionId: localStorage.getItem("delta-force-session") || "",
  sessions: [],
  marketItems: [],
  reactEvents: [],
  selectedReactEventId: "",
  selectedReactMessageId: "",
  messageReactEvents: {},
  modifierFirearms: [],
  modifierCurrent: null,
  modifierSelected: {},
  modifierExpandedSlot: "",
  modifierSearch: "",
  gunModReplies: {},
  gunModImageCatalog: [],
  gunModImageCatalogReady: false,
  gunModImageCatalogPromise: null,
};

const pageTitles = {
  "/": "三角洲行动智能体",
  "/chat": "智能对话",
  "/market": "市场数据查看",
  "/gun-modifier": "改枪台",
  "/knowledge": "知识图谱查询",
  "/readiness": "卡战备",
  "/news": "今日资讯",
  "/admin": "管理后台",
  "/login": "账号登录",
};

const publicRoutes = new Set(["/", "/market", "/gun-modifier", "/readiness", "/news", "/login"]);

const mockMarketItems = [
  { name: "0.0", type: "枪械", price: 0, change: 0.0 },

];

function api(path, options = {}) {
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  }).then(async (response) => {
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) {
      throw new Error(data?.message || data?.error || response.statusText);
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
  return `<span class="attachment-thumbnail"><img src="${src}" alt="${name}" loading="lazy" decoding="async" draggable="false" onerror="this.parentElement.remove()" /></span>`;
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
    item.classList.toggle("active", item.dataset.route === route);
  });
  title.textContent = pageTitles[route] || pageTitles["/"];
}

function setUser(user) {
  state.user = user;
  if (user) {
    localStorage.setItem("delta-force-user", JSON.stringify(user));
  } else {
    localStorage.removeItem("delta-force-user");
    localStorage.removeItem("delta-force-session");
    state.activeSessionId = "";
    state.sessions = [];
  }
  renderTopActions();
}

function renderTopActions() {
  const adminNav = document.querySelector('[data-route="/admin"]');
  if (adminNav) adminNav.hidden = state.user?.username !== "admin";
  if (!state.user) {
    topActions.innerHTML = `<a class="primary-button" href="#/login">登录</a>`;
    return;
  }
  topActions.innerHTML = `
    <a class="ghost-button" href="#/chat">新对话</a>
    <div class="user-pill">
      <span class="avatar small">${escapeHtml(state.user.displayName?.slice(0, 1) || "用")}</span>
      <span>${escapeHtml(state.user.displayName || state.user.username)}</span>
      <button class="chip-button" id="logout-button" type="button">退出</button>
    </div>
  `;
  document.querySelector("#logout-button")?.addEventListener("click", () => {
    setUser(null);
    location.hash = "#/login";
  });
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
    <div class="hero home-hero">
      <div class="hero-copy">
        <div class="eyebrow">DELTA FORCE PLAYER INTELLIGENCE</div>
        <h2>三角洲行动智能体</h2>
        <p>像ChatGPT一样的三角洲AIbot 支持根据你的需求改枪，市场行情提问，三角洲知识问答。</p>
        <div class="hero-actions">
          <a class="primary-button" href="#/chat">进入对话</a>
          <a class="primary-button" href="#/market">查看市场</a>
          <a class="primary-button" href="#/knowledge">查询图谱</a>
        </div>
      </div>
    </div>
    <div class="grid module-grid">
      <a class="card" href="#/chat"><span class="badge">CHAT</span><h3>智能对话</h3><p>登录后会话会保存到 Neo4j，可像 ChatGPT 一样切换历史记录。</p></a>
      <a class="card" href="#/market"><span class="badge blue">MARKET</span><h3>市场数据查看</h3><p>通过实时数据 API 获取物品行情，展示当前价格、战备价与均价变化。</p></a>
      <a class="card" href="#/knowledge"><span class="badge amber">GRAPH</span><h3>v知识图谱查询</h3><p>在图谱数据库中搜索枪械、配件、属性和知识节点。</p></a>
    </div>
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
      <aside class="panel chat-list">
        <div class="session-header">
          <h3>历史记录</h3>
          <button class="chip-button" id="new-session-button" type="button">新建</button>
        </div>
        <div id="session-list">
          ${state.user ? `<div class="empty-state compact">正在读取会话...</div>` : `<div class="empty-state compact">登录后保存和读取历史对话</div>`}
        </div>
      </aside>
      <section class="panel chat-main">
        <div class="messages" id="messages">${defaultChatMessages()}</div>
        <form class="composer" id="chat-form">
          <button class="tool-button" type="button" title="添加工具">＋</button>
          <input id="chat-input" placeholder="询问价格、装备、图谱知识或改枪方案..." autocomplete="off" />
          <button class="primary-button" type="submit">发送</button>
        </form>
      </section>
      <aside class="panel react-panel">
        <div class="react-head">
          <div>
            <span class="badge blue">ReAct</span>
            <h3>执行过程</h3>
          </div>
          <button class="chip-button" id="clear-react-button" type="button">清空</button>
        </div>
        <div class="react-timeline" id="react-timeline">
          <div class="empty-state compact">发送问题后展示任务拆解、工具调用和观察结果。</div>
        </div>
        <div class="react-detail" id="react-detail">
          <div class="empty-state compact">点击事件查看 payload JSON。</div>
        </div>
      </aside>
    </div>
  `;
}

function renderSessionList() {
  const container = document.querySelector("#session-list");
  if (!container) return;
  if (!state.user) {
    container.innerHTML = `<div class="empty-state compact">登录后保存和读取历史对话</div>`;
    return;
  }
  if (!state.sessions.length) {
    container.innerHTML = `<div class="empty-state compact">暂无历史记录</div>`;
    return;
  }
  container.innerHTML = state.sessions.map((session) => `
    <button class="session-item ${session.sessionId === state.activeSessionId ? "active" : ""}" type="button" data-session-id="${session.sessionId}">
      <strong>${escapeHtml(session.title || "新的对话")}</strong>
      <small>${escapeHtml((session.updatedAt || "").replace("T", " ").slice(0, 16))}</small>
    </button>
  `).join("");
  container.querySelectorAll("[data-session-id]").forEach((button) => {
    button.addEventListener("click", () => loadSessionMessages(button.dataset.sessionId));
  });
}

function loadSessions() {
  if (!state.user) return Promise.resolve();
  return api(`/api/chat/sessions?userId=${encodeURIComponent(state.user.userId)}`)
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
  localStorage.setItem("delta-force-session", sessionId);
  renderSessionList();
  api(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages?userId=${encodeURIComponent(state.user.userId)}`)
    .then((messages) => {
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
      box.innerHTML = messages.length ? messages.map(messageMarkup).join("") : defaultChatMessages();
      bindMessageReactClicks();
      renderReactTimeline();
      box.scrollTop = box.scrollHeight;
    })
    .catch(() => {
      document.querySelector("#messages").innerHTML = `<div class="empty-state">读取历史消息失败，请确认 Neo4j 已启动。</div>`;
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

function isGunModHandoff(events) {
  return (events || []).some((event) => {
    if ((event.status || "").toLowerCase() !== "running") return false;
    const text = `${event.title || ""} ${event.summary || ""} ${event.node || ""} ${JSON.stringify(event.payload || {})}`.toLowerCase();
    return text.includes("intentagent") && text.includes("gunmodagent");
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
  const publish = state.user?.username === "admin" ? `<button class="message-gunmod-button secondary" type="button" data-publish-gunmod-message="${escapeHtml(messageId)}">发布到推荐</button>` : "";
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
  const adminUserId = encodeURIComponent(state.user.userId);
  api("/api/daily-content").then((content) => api(`/api/admin/daily-content?adminUserId=${adminUserId}`, {
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
    return value;
  }
  if (!value) {
    return [];
  }
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

function phaseName(phase) {
  const names = {
    workspace_ready: "工作区准备",
    task_plan_ready: "任务拆解",
    agent_plan: "Agent 计划",
    handoff: "Agent 交接",
    tool_call: "工具调用",
    tool_result: "工具结果",
    observation: "观察",
    agent_result: "Agent 完成",
    agent_update: "状态更新",
    final_answer: "最终回答",
  };
  return names[phase] || phase || "事件";
}

function updateReactTimeline(event) {
  if (!event || event.type !== "react_event") return;
  const existingIndex = state.reactEvents.findIndex((item) => item.event_id === event.event_id);
  if (existingIndex >= 0) {
    state.reactEvents[existingIndex] = event;
  } else {
    state.reactEvents.push(event);
  }
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
}

function renderReactTimeline() {
  const timeline = document.querySelector("#react-timeline");
  const detail = document.querySelector("#react-detail");
  if (!timeline || !detail) return;
  if (!state.reactEvents.length) {
    timeline.innerHTML = `<div class="empty-state compact">点击带 ReAct 标识的回复，查看这条回复的执行过程。</div>`;
    detail.innerHTML = `<div class="empty-state compact">点击事件查看 payload JSON。</div>`;
    return;
  }
  timeline.innerHTML = state.reactEvents.map((event) => `
    <button class="react-event ${event.status || "running"} ${event.event_id === state.selectedReactEventId ? "active" : ""}" type="button" data-react-id="${escapeHtml(event.event_id)}">
      <span class="react-dot"></span>
      <span>
        <strong>${escapeHtml(event.title || phaseName(event.phase))}</strong>
        <small>${escapeHtml(phaseName(event.phase))}${event.node ? ` · ${escapeHtml(event.node)}` : ""}</small>
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
      <span class="react-status ${selected.status || "running"}">${escapeHtml(selected.status || "running")}</span>
    </div>
    <p>${escapeHtml(selected.summary || "")}</p>
    <pre>${escapeHtml(JSON.stringify(selected.payload || {}, null, 2))}</pre>
  `;
}

async function streamChat(text, assistantId) {
  const response = await fetch(`${API_BASE}/api/chat/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      message: text,
      userId: state.user?.userId || "",
      sessionId: state.activeSessionId || "",
      reactEvents: true,
    }),
  });
  if (!response.ok || !response.body) {
    throw new Error(response.statusText || "stream failed");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let finalText = "";
  const bubble = document.querySelector(`#${assistantId}`);
  const messages = document.querySelector("#messages");

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
      if (!raw || raw === "[DONE]") continue;

      const chunk = JSON.parse(raw);
      if (chunk.sessionId) {
        state.activeSessionId = chunk.sessionId;
        localStorage.setItem("delta-force-session", chunk.sessionId);
        continue;
      }
      if (chunk.assistantMessageId) {
        const events = normalizeReactEvents(state.messageReactEvents[assistantId]);
        state.messageReactEvents[chunk.assistantMessageId] = events;
        delete state.messageReactEvents[assistantId];
        state.selectedReactMessageId = chunk.assistantMessageId;
        state.gunModReplies[chunk.assistantMessageId] = state.gunModReplies[assistantId] || { content: finalText, events };
        delete state.gunModReplies[assistantId];
        const message = document.querySelector(`[data-message-id="${assistantId}"]`);
        if (message) {
          message.dataset.messageId = chunk.assistantMessageId;
        }
        bindMessageReactClicks();
        continue;
      }
      if (chunk.error) {
        throw new Error(chunk.error);
      }

      const delta = chunk.choices?.[0]?.delta || {};
      if (delta.react_event) {
        updateReactTimeline(delta.react_event);
      }
      if (delta.content && bubble) {
        finalText += delta.content;
        state.gunModReplies[state.selectedReactMessageId || assistantId] = { content: finalText, events: state.reactEvents };
        bubble.innerHTML = `${gunModReplyContentMarkup(finalText, state.reactEvents)}<span class="message-react-tag">ReAct</span>${thinkingMarkup(state.reactEvents)}${gunModCtaMarkup(state.selectedReactMessageId || assistantId, state.reactEvents)}`;
        bindGunModButtons(bubble);
        messages.scrollTop = messages.scrollHeight;
      }
    }
  }

  if (bubble && !finalText) {
    bubble.innerHTML = `后端没有返回内容<span class="message-react-tag">ReAct</span>`;
  }
  return loadSessions();
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
  if (state.user?.username !== "admin") return `<section class="panel admin-denied"><span class="badge amber">RESTRICTED</span><h2>管理员权限必需</h2><p>请使用用户名为 <code>admin</code> 的账户登录后访问管理后台。</p></section>`;
  return `<div class="admin-layout">
    <section class="panel admin-head"><div><span class="eyebrow">CONTROL CENTER</span><h2>管理员界面</h2><p>管理用户、今日资讯内容与实时市场数据。</p></div><span class="badge">ADMIN</span></section>
    <section class="panel admin-users"><div class="section-title"><h3>用户信息</h3><button id="admin-users-refresh" class="ghost-button" type="button">刷新列表</button></div><div class="query-table-wrap"><table class="data-table"><thead><tr><th>用户</th><th>显示名</th><th>会话数</th><th>创建时间</th><th>最近登录</th></tr></thead><tbody id="admin-users-body"><tr><td colspan="5">正在读取用户信息…</td></tr></tbody></table></div></section>
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
  const adminUserId = encodeURIComponent(state.user.userId);
  const loadUsers = () => api(`/api/admin/users?adminUserId=${adminUserId}`).then((users) => { document.querySelector("#admin-users-body").innerHTML = users.length ? users.map((user) => `<tr><td><strong>${escapeHtml(user.username)}</strong></td><td>${escapeHtml(user.displayName)}</td><td>${user.sessionCount}</td><td>${escapeHtml(formatUpdatedAt(user.createdAt))}</td><td>${escapeHtml(formatUpdatedAt(user.lastLoginAt))}</td></tr>`).join("") : `<tr><td colspan="5">暂无用户</td></tr>`; }).catch((error) => { document.querySelector("#admin-users-body").innerHTML = `<tr><td colspan="5">${escapeHtml(error.message)}</td></tr>`; });
  const loadContent = () => api(`/api/admin/daily-content?adminUserId=${adminUserId}`).then((content) => { document.querySelector("#admin-password-az3").value = content.passwordAz3 || ""; document.querySelector("#admin-password-zero-dam").value = content.passwordZeroDam || ""; document.querySelector("#admin-password-space-city").value = content.passwordSpaceCity || ""; document.querySelector("#admin-password-tide-prison").value = content.passwordTidePrison || ""; document.querySelector("#admin-password-brakkesh").value = content.passwordBrakkesh || ""; document.querySelector("#admin-password-longbow").value = content.passwordLongbow || ""; document.querySelector("#admin-ai-recommendation").value = content.aiRecommendation || ""; document.querySelector("#admin-ai-prompt").value = content.aiPrompt || ""; document.querySelector("#admin-news-content").value = content.newsContent || ""; });
  document.querySelector("#admin-users-refresh").addEventListener("click", loadUsers);
  document.querySelector("#admin-content-form").addEventListener("submit", (event) => { event.preventDefault(); const status = document.querySelector("#admin-content-status"); status.textContent = "正在保存…"; api(`/api/admin/daily-content?adminUserId=${adminUserId}`, { method: "POST", body: JSON.stringify({ passwordAz3: document.querySelector("#admin-password-az3").value.trim(), passwordZeroDam: document.querySelector("#admin-password-zero-dam").value.trim(), passwordSpaceCity: document.querySelector("#admin-password-space-city").value.trim(), passwordTidePrison: document.querySelector("#admin-password-tide-prison").value.trim(), passwordBrakkesh: document.querySelector("#admin-password-brakkesh").value.trim(), passwordLongbow: document.querySelector("#admin-password-longbow").value.trim(), aiRecommendation: document.querySelector("#admin-ai-recommendation").value.trim(), aiPrompt: document.querySelector("#admin-ai-prompt").value.trim(), newsContent: document.querySelector("#admin-news-content").value.trim() }) }).then(() => { status.textContent = "已保存"; }).catch((error) => { status.textContent = error.message || "保存失败"; }); });
  document.querySelector("#admin-refresh-form").addEventListener("submit", (event) => { event.preventDefault(); const submit = document.querySelector("#admin-refresh-submit"), result = document.querySelector("#admin-refresh-result"), pages = Number(document.querySelector("#refresh-pages").value); const request = {}; if (document.querySelector("#refresh-kind").value) request.kind = document.querySelector("#refresh-kind").value; if (pages > 0) request.max_pages = pages; if (document.querySelector("#refresh-save-partial").checked) request.save_partial = true; submit.disabled = true; result.textContent = "正在刷新，请勿关闭页面…"; api(`/api/admin/market/refresh?adminUserId=${adminUserId}`, { method: "POST", body: JSON.stringify(request) }).then((data) => { result.textContent = JSON.stringify(data, null, 2); }).catch((error) => { result.textContent = error.message || "刷新失败"; }).finally(() => { submit.disabled = false; }); });
  loadUsers(); loadContent();
}

function blankPage() {
  return `<div class="blank-view" aria-hidden="true"></div>`;
}

function loginPage() {
  const notice = sessionStorage.getItem("auth-notice") || "";
  sessionStorage.removeItem("auth-notice");
  return `
    <div class="login-view">
      <div class="login-art">
        <div class="eyebrow">PLAYER WORKSPACE</div>
        <h2>登录后保存你的对话、行情关注和图谱查询上下文。</h2>
        <p>账号数据、会话列表和消息历史会写入独立 Neo4j 用户库；图谱查询继续使用 sjzdata。</p>
        <div class="stat-strip">
          <div class="stat-tile"><strong>用户</strong><span>AgentUser 节点</span></div>
          <div class="stat-tile"><strong>会话</strong><span>AgentChatSession 节点</span></div>
          <div class="stat-tile"><strong>消息</strong><span>AgentChatMessage 节点</span></div>
        </div>
      </div>
      <section class="login-box">
        <h2 id="auth-title">登录</h2>
        ${notice ? `<div class="auth-notice">${escapeHtml(notice)}</div>` : ""}
        <div class="auth-tabs">
          <button class="chip-button active" type="button" data-auth-mode="login">登录</button>
          <button class="chip-button" type="button" data-auth-mode="register">注册</button>
        </div>
        <form id="auth-form">
          <div class="field"><label>账号</label><input id="auth-username" placeholder="输入用户名" autocomplete="username" /></div>
          <div class="field"><label>密码</label><input id="auth-password" type="password" placeholder="输入密码" autocomplete="current-password" /></div>
          <button class="primary-button" type="submit" id="auth-submit">登录</button>
          <div class="form-status" id="auth-status"></div>
        </form>
      </section>
    </div>
  `;
}

function render() {
  const route = location.hash.replace("#", "") || "/";
  const normalized = pageTitles[route] ? route : "/";
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
    "/gun-modifier": modifierPage,
    "/knowledge": knowledgePage,
    "/readiness": blankPage,
    "/news": newsPage,
    "/admin": adminPage,
    "/login": loginPage,
  };
  view.innerHTML = pages[normalized]();
  view.classList.toggle("home-view", normalized === "/");
  bindPageEvents(normalized);
  sidebar.classList.remove("open");
}

function bindPageEvents(route) {
  if (route === "/chat") bindChat();
  if (route === "/market") bindMarket();
  if (route === "/gun-modifier") bindModifier();
  if (route === "/knowledge") bindKnowledge();
  if (route === "/news") bindNews();
  if (route === "/admin" && state.user?.username === "admin") bindAdmin();
  if (route === "/login") bindAuth();
}

function bindChat() {
  document.querySelector("#new-session-button").addEventListener("click", () => {
    state.activeSessionId = "";
    state.reactEvents = [];
    state.selectedReactEventId = "";
    state.selectedReactMessageId = "";
    state.messageReactEvents = {};
    localStorage.removeItem("delta-force-session");
    document.querySelector("#messages").innerHTML = defaultChatMessages();
    renderReactTimeline();
    renderSessionList();
  });
  document.querySelector("#clear-react-button").addEventListener("click", () => {
    state.reactEvents = [];
    state.selectedReactEventId = "";
    renderReactTimeline();
  });
  loadSessions().then(() => {
    if (state.activeSessionId) loadSessionMessages(state.activeSessionId);
  });
  document.querySelector("#chat-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const input = document.querySelector("#chat-input");
    const text = input.value.trim();
    if (!text) return;
    const messages = document.querySelector("#messages");
    const assistantId = `assistant-${Date.now()}`;
    state.reactEvents = [];
    state.selectedReactEventId = "";
    state.selectedReactMessageId = assistantId;
    state.messageReactEvents[assistantId] = state.reactEvents;
    state.gunModReplies[assistantId] = { content: "", events: state.reactEvents, prompt: text };
    renderReactTimeline();
    messages.insertAdjacentHTML("beforeend", messageMarkup({ role: "user", content: text }));
    messages.insertAdjacentHTML("beforeend", assistantStreamMarkup(assistantId));
    bindMessageReactClicks();
    input.value = "";
    messages.scrollTop = messages.scrollHeight;
    try {
      await streamChat(text, assistantId);
    } catch (error) {
      const bubble = document.querySelector(`#${assistantId}`);
      if (bubble) {
        bubble.textContent = error.message || "后端暂未连接或 FastAPI/Neo4j 不可用，当前无法获得真实回复。";
      }
      messages.scrollTop = messages.scrollHeight;
    }
  });
}

function bindMarket() {
  const load = () => {
    const keyword = document.querySelector("#market-search").value.trim();
    const type = document.querySelector("#market-type").value;
    api(`/api/market/items?limit=200&keyword=${encodeURIComponent(keyword)}&type=${encodeURIComponent(type)}`)
      .then((items) => {
        state.marketItems = items || [];
        document.querySelector("#market-body").innerHTML = marketRows(state.marketItems);
      })
      .catch(() => {
        state.marketItems = mockMarketItems;
        document.querySelector("#market-body").innerHTML = marketRows(mockMarketItems);
      });
  };
  document.querySelector("#market-search").addEventListener("input", load);
  document.querySelector("#market-type").addEventListener("change", load);
  document.querySelector("#market-refresh").addEventListener("click", load);
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
  document.querySelectorAll("[data-auth-mode]").forEach((button) => {
    button.addEventListener("click", () => {
      mode = button.dataset.authMode;
      document.querySelectorAll("[data-auth-mode]").forEach((item) => item.classList.toggle("active", item === button));
      document.querySelector("#auth-title").textContent = mode === "login" ? "登录" : "注册";
      document.querySelector("#auth-submit").textContent = mode === "login" ? "登录" : "创建账号";
      document.querySelector("#auth-status").textContent = "";
    });
  });
  document.querySelector("#auth-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const username = document.querySelector("#auth-username").value.trim();
    const password = document.querySelector("#auth-password").value;
    const status = document.querySelector("#auth-status");
    status.textContent = "正在连接 Neo4j 用户库...";
    api(`/api/auth/${mode}`, { method: "POST", body: JSON.stringify({ username, password }) })
      .then((user) => {
        setUser(user);
        status.textContent = "登录成功";
        location.hash = "#/chat";
      })
      .catch((error) => {
        status.textContent = error.message || "登录失败，请确认 Neo4j 已启动。";
      });
  });
}

menuButton.addEventListener("click", () => sidebar.classList.toggle("open"));
window.addEventListener("hashchange", render);
render();
