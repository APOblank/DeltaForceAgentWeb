# 三角洲行动智能体前端原型

这是「三角洲行动智能体」前后端原型。前端包含页面样式、mock 回退和基础交互；后端使用 Spring Boot，提供市场数据、AI 改枪推荐、Neo4j 知识库检索和 FastAPI 聊天代理。

## 页面

- `#/` 首页
- `#/chat` 智能对话
- `#/market` 市场数据查看
- `#/gunsmith` AI 改枪推荐
- `#/login` 登录

## 运行

前端正式文件位于 Spring Boot 项目静态资源目录：

```text
src/main/resources/static/
```

请通过 Spring Boot 访问页面，不再使用根目录旧版 `app.js`。

## Spring Boot 后端

构建：

```bash
mvn -s .mvn/settings.xml -DskipTests package
```

运行：

```bash
java -jar target/delta-force-agent-web-0.1.0.jar
```

访问：

```text
http://127.0.0.1:8080/
```

后端接口：

- `GET /api/health`
- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/market/items?keyword=&type=&limit=100`
- `POST /api/gunsmith/recommend`
- `GET /api/knowledge/search?keyword=&limit=20`
- `GET /api/knowledge/neighborhood?keyword=&limit=30`
- `POST /api/knowledge/query`
- `GET /api/chat/sessions?userId=`
- `POST /api/chat/sessions`
- `GET /api/chat/sessions/{sessionId}/messages?userId=`
- `POST /api/chat`

## 配置

默认配置在 `src/main/resources/application.yml`。

- Neo4j：默认连接 `bolt://localhost:7687`，数据库 `sjzdata`
- 图谱查询：固定使用 `sjzdata`
- 图谱可视化：输入关键词后，展示匹配节点与其它节点的直接关系
- Neo4j Query 工作台接口仍保留：仅允许只读 Cypher，禁止 `CREATE`、`MERGE`、`SET`、`DELETE`、`DROP`、`CALL`、`LOAD CSV` 等语句
- 用户与会话：默认写入独立 Neo4j 数据库 `deltaforceusers`，节点包括 `AgentUser`、`AgentChatSession`、`AgentChatMessage`
- 页面权限：首页和市场数据无需登录；智能对话、图谱查询、AI 改枪需要登录后使用
- FastAPI：默认代理 `http://127.0.0.1:8000/v1/chat/completions`
- 市场数据：通过 DeltaForceRealTimeData 的 `GET /items` API 获取，默认地址 `http://127.0.0.1:8010`
- 不会调用 DeltaForceRealTimeData 的 `POST /refresh`，刷新爬虫由外部服务自行管理
- JSON 数据文件仅保留为历史资源，不再作为市场数据来源

可通过环境变量覆盖：

```bash
SERVER_PORT=8080
NEO4J_URI=bolt://localhost:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=your-password
NEO4J_DATABASE=sjzdata
NEO4J_USER_DATABASE=deltaforceusers
DELTA_FORCE_HELPER_BASE_URL=http://127.0.0.1:8000
DELTA_FORCE_HELPER_API_KEY=
DELTA_FORCE_HELPER_MODEL=DF_agent
DELTA_FORCE_REALTIME_BASE_URL=http://127.0.0.1:8010
```

## 后续接入点

后续接入时，优先替换 `src/main/resources/static/app.js` 中的 mock 数据与页面事件：

- `marketItems`：市场物品、价格、涨跌、热度
- `loadout`：AI 改枪推荐结果
- `chat-form` submit 事件：智能体对话接口
