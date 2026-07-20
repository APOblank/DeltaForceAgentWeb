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
};

const pageTitles = {
  "/": "三角洲行动智能体",
  "/chat": "智能对话",
  "/market": "市场数据查看",
  "/knowledge": "知识图谱查询",
  "/readiness": "卡战备",
  "/news": "今日资讯",
  "/login": "账号登录",
};

const publicRoutes = new Set(["/", "/market", "/readiness", "/news", "/login"]);

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
      (messages || []).forEach((message) => {
        const events = normalizeReactEvents(message.reactEvents);
        if (message.messageId && events.length) {
          state.messageReactEvents[message.messageId] = events;
        }
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
  const hasReact = !isUser && normalizeReactEvents(message.reactEvents).length > 0;
  const messageId = message.messageId || "";
  return `
    <div class="message ${isUser ? "user" : ""} ${hasReact ? "has-react" : ""} ${messageId && messageId === state.selectedReactMessageId ? "selected-react-message" : ""}" ${messageId ? `data-message-id="${escapeHtml(messageId)}"` : ""}>
      <div class="avatar">${isUser ? "你" : "Δ"}</div>
      <div class="bubble">${escapeHtml(message.content)}${hasReact ? `<span class="message-react-tag">ReAct</span>` : ""}</div>
    </div>
  `;
}

function assistantStreamMarkup(id) {
  return `
    <div class="message has-react selected-react-message" data-message-id="${id}">
      <div class="avatar">Δ</div>
      <div class="bubble" id="${id}"><span class="message-react-tag">ReAct</span></div>
    </div>
  `;
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
        bubble.innerHTML = `${escapeHtml(finalText)}<span class="message-react-tag">ReAct</span>`;
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
        <td><strong>${escapeHtml(item.name)}</strong></td>
        <td>${escapeHtml(type)}</td>
        <td>${money(currentPrice)}</td>
        <td>${money(combatPrice)}</td>
        <td>${money(price3d)} <span class="${change3d >= 0 ? "trend-up" : "trend-down"}">${change3d >= 0 ? "+" : ""}${Number(change3d).toFixed(1)}%</span></td>
        <td>${money(price7d)} <span class="${change7d >= 0 ? "trend-up" : "trend-down"}">${change7d >= 0 ? "+" : ""}${Number(change7d).toFixed(1)}%</span></td>
      </tr>
    `;
  }).join("");
}

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
    "/knowledge": knowledgePage,
    "/readiness": blankPage,
    "/news": blankPage,
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
  if (route === "/knowledge") bindKnowledge();
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
