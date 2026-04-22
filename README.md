# Ragent 项目说明文档

---

## 1. 项目是什么

**Ragent** 是一套面向企业场景的 **Agentic RAG（检索增强生成）+ 意图路由 + MCP 工具** 平台：用户提问后，系统会做多轮记忆加载、问题重写与拆分、树形意图识别、歧义引导、**知识库多路并行检索**与后处理、必要时 **MCP 工具调用**，最后用多供应商 **LLM 流式生成**答案，并通过 SSE 推给前端；同时提供可编排的 **文档入库 Pipeline**、管理后台与全链路 Trace。另提供 **Deep Research（深度研究）** 工作流：由状态机驱动「宏观问题拆解 → 子问题并行召回 → 每子问题独立摘要压缩 → 流式撰写深度报告」，缓解单次长上下文注意力衰减问题。


---

## 2. 技术栈

| 层面 | 选型 |
|------|------|
| 语言/运行时 | Java 17 |
| 后端框架 | Spring Boot 3.5.7、MyBatis Plus |
| 前端 | React 18、Vite、TypeScript、Tailwind、Radix UI |
| 关系库 | **PostgreSQL**（`application.yaml`），JDBC + **pgvector** 可选作向量存储 |
| 向量库 | **Milvus**（可选，与 pgvector 二选一由配置切换） |
| 缓存/限流 | Redis + Redisson |
| 消息队列 | RocketMQ 5.x |
| 对象存储 | S3 兼容（配置为 RustFS） |
| 文档解析 | Apache Tika |
| 认证 | Sa-Token |
| 代码格式 | Spotless（编译期应用 License Header） |

根 `pom.xml` 中多模块：

- `bootstrap`：Web、RAG、入库、用户等业务
- `framework`：通用基础设施（异常、幂等、SSE、上下文、Trace 等）
- `infra-ai`：模型调用、路由、Embedding/Rerank 等
- `mcp-server`：独立进程，提供 MCP JSON-RPC HTTP 端点示例

---

## 3. Maven 模块职责

### 3.1 `bootstrap`（主应用）

- 启动类：`com.nageoffer.ai.ragent.RagentApplication`，开启定时任务，`@MapperScan` 扫描 `rag`、`ingestion`、`knowledge`、`user` 等包下 Mapper。
- 聚合依赖：`framework` + `infra-ai`，并引入 Milvus SDK、Tika、PostgreSQL、pgvector、S3 SDK 等。

### 3.2 `framework`

- 横切能力：统一响应/错误码、幂等、分布式 ID、用户上下文与 Trace 跨线程透传（配合 TTL）、SSE 封装、队列式限流相关支撑等。

### 3.3 `infra-ai`

- 抽象 **`LLMService`**：同步/流式 Chat，对上统一 `ChatRequest` / `ChatMessage`。
- **`ModelRoutingExecutor`**：按优先级遍历候选模型，结合 **`ModelHealthStore`** 做失败计数与熔断式跳过，失败则自动 fallback。
- Embedding、Rerank、HTTP 客户端等与厂商解耦，便于新增 `ChatClient` 实现。

### 3.4 `mcp-server`

- Spring Boot 小应用，`POST /mcp` 接收 **JSON-RPC**，由 `MCPDispatcher` 分发到具体 `MCPToolExecutor`（如天气、票务、销售等示例工具）。

---

## 4. 仓库目录（理解代码时优先看这些）

- `bootstrap/src/main/java/...`：核心业务
  - `rag/`：对话、检索、意图、改写、Prompt、MCP 客户端侧编排；`rag/deepresearch/` 为 Deep Research 状态枚举
  - `ingestion/`：入库流水线引擎与各类 Node
  - `user/`：登录与用户
  - `knowledge/`：知识库调度等
- `bootstrap/src/main/resources/application.yaml`：**单机默认配置真理源**
- `bootstrap/src/main/resources/prompt/`：各类 `.st` 提示模板（含 `deep-research-decompose.st` / `deep-research-sub-summary.st` / `deep-research-report.st`）
- `resources/database/`：PostgreSQL 建表与初始化、升级脚本
- `resources/docker/`：Milvus、RocketMQ、轻量 Compose 说明
- `resources/docs/knowledge/`：**示例知识库 Markdown**（人事/IT/财务等）
- `frontend/`：React 管理端与用户端
- `mcp-server/`：独立 MCP 演示服务

---

## 5. 环境搭建与启动顺序

### 5.1 必备软件

- **JDK 17**、**Maven 3.8+**
- **Node.js 18+**（前端）
- **PostgreSQL**（建议安装 **pgvector** 扩展，若使用 `rag.vector.type: pg`）
- **Redis**
- **RocketMQ**（NameServer + Broker，与配置中 `name-server` 一致）
- 可选：**Milvus** 全栈（etcd、minio/rustfs 等，见 `resources/docker`）
- **RustFS/S3** 兼容存储（`application.yaml` 默认 `http://localhost:9000`）

### 5.2 数据库

1. 创建库 `ragent`（与 JDBC URL 一致）。
2. 执行 `resources/database/schema_pg.sql` 与 `init_data_pg.sql`（及按需的 `upgrade_*.sql`）。

### 5.3 配置文件要点（`application.yaml`）

- 服务端口与上下文：**`9090`**，**`context-path: /api/ragent`**，因此完整 API 前缀为：  
  `http://localhost:9090/api/ragent/...`
- **`rag.vector.type`**：`pg` 或 `milvus`，切换向量存储实现（`PgVectorStoreService` / `MilvusVectorStoreService`）。
- **`ai.providers`**：百炼、SiliconFlow、Ollama 的 URL 与 API Key（Key 可用环境变量 `BAILIAN_API_KEY`、`SILICONFLOW_API_KEY`）。
- **`ai.chat.candidates` / `ai.embedding.candidates` / `ai.rerank.candidates`**：多模型路由列表与优先级。
- **`rag.mcp.servers`**：主应用调用的 MCP 服务地址（示例默认 `http://localhost:9099`，与 `mcp-server` 端口需对应）。
- **`rag.deep-research.*`**：Deep Research 开关、子问题数量上限、每子问题检索 TopK、检索片段与摘要长度上限等（见下文「Deep Research 工作流」）。

### 5.4 启动后端

在含根 `pom.xml` 的目录（本仓库为 `ragent-main/`）执行：

```bash
mvn -pl bootstrap -am spring-boot:run
```

（或使用 IDE 运行 `RagentApplication`。）

### 5.5 启动 MCP 演示服务（可选）

```bash
mvn -pl mcp-server -am spring-boot:run
```

并确认 `mcp-server` 的 `application.yml` 中端口与主应用 `rag.mcp.servers` 一致。

### 5.6 启动前端

```bash
cd frontend
npm install
npm run dev
```

（Vite 默认开发端口见 `frontend/vite.config`，请求后端时需带上 **`/api/ragent`** 前缀或通过代理配置。）

### 5.7 Docker 辅助

- `resources/docker/lightweight/README.md`：说明**内存受限**场景下 Milvus Compose 与 **2.5.8 降级**用法。
- RocketMQ、Milvus 等在 `resources/docker/` 下有对应 `*.compose.yaml`，可按需 `docker compose up -d`。

---

## 6. 核心对话链路（如何实现）

入口是 **SSE 流式接口**，并实现「同会话防重复提交」幂等（`RAGChatController` → `RAGChatServiceImpl`）。

`RAGChatServiceImpl` 中核心流程顺序为：

1. **记忆**：`ConversationMemoryService.loadAndAppend` 加载历史并追加当前用户句。
2. **改写与拆问**：`QueryRewriteService.rewriteWithSplit`，产出 `RewriteResult`（改写句 + 子问题列表）。
3. **意图**：`IntentResolver.resolve` 对每个子问题并行意图分类，得到 `SubQuestionIntent` 列表。
4. **歧义引导**：`IntentGuidanceService.detectAmbiguity`；若需要澄清，直接 `callback.onContent` 后结束。
5. **仅系统意图**：若全是 `SYSTEM` 类意图，走 `streamSystemResponse`，可用意图节点上配置的自定义 prompt。
6. **检索**：`RetrievalEngine.retrieve` 汇总 KB + MCP，为空则返回固定「未检索到…」。
7. **生成**：`RAGPromptService.buildStructuredMessages` 组装消息，`LLMService.streamChat` 流式输出；`StreamTaskManager` 绑定可取消句柄。

限流在 `streamChat` 上通过 `@ChatRateLimit` AOP 实现（全局并发、排队等由 `application.yaml` 的 `rag.rate-limit` 控制）。

---

## 7. Deep Research 工作流（深度研究）

面向**宏观研究主题**（非单轮短问答）：用显式状态 **`DeepResearchState`** 驱动整条链路，避免把海量检索原文一次性塞进最终报告，由子问题级摘要再综合成文。

### 7.1 状态与数据流

| 状态 | 含义 |
|------|------|
| `DECOMPOSE` | LLM 将 `topic` 拆解为若干 `sub_questions`（JSON），失败则退化为单主题 |
| `PARALLEL_RETRIEVE` | `CompletableFuture` + 线程池 **`deepResearchThreadPoolExecutor`**，每子问题调用 **`MultiChannelRetrievalEngine.retrieveKnowledgeChannels`**（空意图 → 全局向量通道） |
| `SUBQUESTION_SUMMARIZE` | 每个子问题**独立一次** `LLMService.chat`（`deep-research-sub-summary.st`），仅传入该子问题对应检索片段（可截断） |
| `SYNTHESIZE_REPORT` | 汇总子问题笔记，`LLMService.streamChat` + **`StreamChatEventHandler`** 流式输出最终报告（与普通对话相同的 `meta` / `message` / `finish` / `done`） |
| `FAILED` | 取消、未启用或异常 |

实现类：**`DeepResearchServiceImpl`**（`DeepResearchService`）；提示词路径见 **`RAGConstant`** 中 `DEEP_RESEARCH_*_PROMPT_PATH`。

### 7.2 HTTP 接口与 SSE 约定

- **入口**：`GET /rag/v3/deep-research`，参数：`topic`（必填）、`conversationId`（可选）、`deepThinking`（可选，默认 `false`）。
- **完整 URL 示例**：`http://localhost:9090/api/ragent/rag/v3/deep-research?topic=...&deepThinking=false`
- **限流/幂等/Trace**：与普通聊天一致，使用 **`@ChatRateLimit`**；停止任务仍用 **`POST /rag/v3/stop?taskId=...`**（`taskId` 与首包 `meta` 中一致）。
- **阶段进度**：`message` 事件中 **`type` 为 `deep_research`**，`delta` 为 JSON 字符串，例如：`{"state":"PARALLEL_RETRIEVE","detail":"..."}`。最终报告正文仍为 **`type` = `response`** 的增量。

### 7.3 配置项（`application.yaml`）

```yaml
rag:
  deep-research:
    enabled: true                    # 关闭后接口将返回业务错误
    max-sub-questions: 8             # 拆解阶段子问题数量上限
    retrieval-top-k: 12            # 每子问题多通道检索 TopK
    max-source-chars-per-sub: 12000  # 单个子问题拼接检索片段用于摘要的最大字符数
    summary-max-chars: 2500        # 单个子问题摘要长度提示上限（模板内约束）
```

### 7.4 相关源码位置

- 控制器：`RAGChatController#deepResearch`
- 服务：`DeepResearchService` / `DeepResearchServiceImpl`
- 状态枚举：`rag/deepresearch/DeepResearchState.java`
- 配置：`DeepResearchProperties`（`@ConfigurationProperties(prefix = "rag.deep-research")`）
- 线程池：`ThreadPoolExecutorConfig#deepResearchThreadPoolExecutor`（TTL 透传）
- SSE 辅助：`StreamChatEventHandler#sendDeepResearchPhase`

---

## 8. 检索子系统（多路并行 + 后处理）

### 8.1 多通道检索引擎

`MultiChannelRetrievalEngine`：

- 注入所有 **`SearchChannel`** 实现，按 `isEnabled` 过滤、`getPriority` 排序。
- 使用专用线程池 **`ragRetrievalThreadPoolExecutor`** 并行 `CompletableFuture.supplyAsync`。
- 各通道返回 **`SearchChannelResult`**（chunks、置信度、耗时等）。
- 再按顺序执行 **`SearchResultPostProcessor`** 链（去重、rerank 等），顺序由 `getOrder` 决定。

当前仓库中的通道包括 **`VectorGlobalSearchChannel`**（全局向量）、**`IntentDirectedSearchChannel`**（意图定向/带 collection 与过滤）等，阈值在 `application.yaml` 的 `rag.search.channels` 下。

### 8.2 检索与 MCP 的合并

`RetrievalEngine` 对每个 **子问题** 异步构建上下文：

- **KB**：调用 `multiChannelRetrievalEngine.retrieveKnowledgeChannels`，再用 `ContextFormatter.formatKbContext` 格式化为 Markdown 风格段落。
- **MCP**：从意图节点取出 `mcpToolId`，`MCPParameterExtractor` 用 LLM 从用户话里抽参，`MCPToolRegistry` 找执行器并行 `execute`。

最终将多子问题的 **KB/MCP 文本** 拼进 `RetrievalContext`，供 Prompt 阶段使用。

---

## 9. 意图体系（概念级）

- 意图数据存库（`IntentNodeDO` 等），节点包含类型（KB / MCP / SYSTEM）、关联集合名、**Milvus filter 表达式**、MCP 工具 ID、参数提取模板、且可配置 **节点级 topK**。
- **`IntentResolver`**：`IntentClassifier`（默认 Bean）对问句打 `NodeScore`，过滤低分、限制总意图数，并把 MCP 与 KB 分到 **`IntentGroup`** 供 Prompt 区分「知识」与「工具结果」段落。

歧义引导由 `IntentGuidanceService` + `guidance-prompt.st` 等模板配合实现（低置信度时返回引导话术而非检索）。

---

## 10. 模型路由与容错（infra-ai）

`ModelRoutingExecutor.executeWithFallback` 逻辑要点：

- 按候选列表顺序尝试；`ModelHealthStore.allowCall` 不通过则跳过（熔断/半开）。
- 单次调用成功则 `markSuccess`，异常则 `markFailure` 并记录日志，继续下一个候选。
- 全部失败抛出 `RemoteException`。

流式场景下的 **首包探测与缓冲**（避免切换模型时前端收到半截内容）在 `infra-ai` 的 Stream 回调装饰中实现（如 `ProbeBufferingCallback` 等类，可按类名在仓库中跳转阅读）。

---

## 11. 文档入库 Pipeline（如何实现）

`IngestionEngine`：

- Spring 注入所有 **`IngestionNode`** 实现子类，按 **`getNodeType`** 登记到 `nodeMap`。
- 从数据库读取 **`PipelineDefinition`**（节点列表与连线），校验无环、边存在性，找**入度为 0** 的起点，再 **`executeChain`** 顺序执行。
- 每步写 **`NodeLog`**，支持条件表达式（`ConditionEvaluator`）与上节点输出抽取（`NodeOutputExtractor`）。

节点类型在包 `ingestion/node/` 中：**Fetcher、Parser、Enhancer、Enricher、Chunker、Indexer** 等，分别对应「拉取 → 解析 → 增强 → 分块 enrich → 切块 → 写入向量索引」等企业 ETL 步骤。

文档抓取策略在 `ingestion/strategy/fetcher/`：**本地、HTTP、S3、飞书** 等。

---

## 12. MCP 双进程模型（示例）

- **主应用（bootstrap）**：业务侧 **`MCPToolExecutor`** 实现调用远端 HTTP MCP（工具注册表 + HTTP 客户端），与意图绑定。
- **mcp-server**：独立提供 **`POST /mcp`** 的 JSON-RPC 服务端，便于本地演示或拆进程部署。

---

## 13. 前端（`frontend`）

- 技术栈见 `package.json`：Vite、React Router、axios、react-markdown、TanStack Table、Zustand、Radix 组件等。
- 与后端联调时注意 **context-path**：API 形如 `http://localhost:9090/api/ragent/...`。
- 用户侧：流式聊天、Markdown、代码高亮、深度思考开关等；若对接 Deep Research，需解析 **`message.type === "deep_research"`** 以展示阶段状态（可选）。
- 管理侧：知识库、意图树、入库任务、Trace、模型与系统设置等。

---

## 14. 具体可运行的「例子」

### 14.1 示例知识文档

仓库自带 Markdown 知识库样例，例如：

- `resources/docs/knowledge/group/hr/公司规章制度.md`
- `resources/docs/knowledge/group/it/IT支持.md`

入库后，用户问「考勤」「打印机」类问题，可走 **向量 + 意图定向** 通道，观察日志里多通道统计与后处理器条数变化。

### 14.2 API 调用示例（SSE）

```text
GET http://localhost:9090/api/ragent/rag/v3/chat?question=公司考勤制度是怎么样的&deepThinking=false
```

（需按 Sa-Token 要求带登录态时，应先走登录接口拿到 `Authorization`。）

**Deep Research 示例**：

```text
GET http://localhost:9090/api/ragent/rag/v3/deep-research?topic=RAG%20在企业知识库中的工程实践要点&deepThinking=false
```

### 14.3 模型与 Key

在 `application.yaml` 中为百炼/硅基流动配置 API Key，或设置环境变量 **`BAILIAN_API_KEY`**、**`SILICONFLOW_API_KEY`**。若仅本地试运行，可拉高 **Ollama** 候选优先级并本地拉模型。

---

## 15. 观测与排错

- **测试说明（含 Deep Research 测法、现有单测清单与缺口）**：见 **[docs/testing-report.md](docs/testing-report.md)**。
- **Deep Research**：一次请求会触发「1 次拆解 + N 次子问题同步摘要 + 1 次流式报告」，LLM 与检索调用次数显著多于普通聊天；子问题较多时耗时会变长，请关注模型配额与超时配置。
- **日志**：检索通道、后处理器、意图、MCP 工具调用均有结构化 INFO/WARN。
- **`@RagTraceNode`**：Trace 落库/查询在管理后台「链路追踪」页使用（具体表结构见 `schema_pg.sql`）。
- **Milvus vs pg**：若检索一直为空，先确认 `rag.vector.type` 与真实数据写入的存储一致，且 collection/表名、维度与 embedding 配置一致。

---

## 16. 扩展点

- 新检索通道：实现 **`SearchChannel`** 并注册为 Spring Bean。
- 新后处理器：实现 **`SearchResultPostProcessor`**。
- 新 MCP 工具：业务里实现 **`MCPToolExecutor`**；demo 可在 `mcp-server` 增加 executor。
- 新入库节点：实现 **`IngestionNode`**。
- 新模型厂商：在 **`infra-ai`** 增加客户端实现并接入候选列表。
- Deep Research：可调整 `prompt/deep-research-*.st` 与 **`DeepResearchProperties`**；若需多轮「检索—摘要」迭代，可在 `DeepResearchState` 与 `DeepResearchServiceImpl` 上扩展循环与轮次上限。



