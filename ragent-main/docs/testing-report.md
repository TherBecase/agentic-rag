# Ragent 测试说明与 Deep Research 测试指南

本文说明：**Deep Research 如何测**、**现有自动化测试覆盖哪些能力**、**哪些模块尚无测试**、以及 **推荐命令与检查项**。

---

## 一、测试资产总览

| 类型 | 位置 | 说明 |
|------|------|------|
| JUnit 5 单测 | `bootstrap/src/test/java` | 唯一集中存放测试代码的模块；`framework`、`infra-ai`、`mcp-server` 当前**无** `src/test` |
| Shell 脚本 | `scripts/sse_queue_test.sh` | SSE 排队/并发压测（需已启动服务与 Token） |
| 文档示例 | `docs/examples/`、`docs/quick-start.md` | 入库、API 等示例，非自动化测试 |

### 1.1 运行全部测试命令

在仓库根目录（含根 `pom.xml` 的 `ragent-main/`）执行：

```bash
mvn test
```

仅运行 `bootstrap` 模块测试：

```bash
mvn -pl bootstrap test
```

跳过测试编译主代码：

```bash
mvn -pl bootstrap -am package -DskipTests
```

---

## 二、Deep Research 如何测试

### 2.1 前置条件

- 与主应用一致：**PostgreSQL**、**Redis**、**RocketMQ**、**向量存储**（pgvector 或 Milvus）、**已配置可用的 LLM**（`application.yaml` 中 `ai.chat` / `ai.providers`）。
- `rag.deep-research.enabled: true`（默认已开）。
- 知识库中已有向量数据时，检索与摘要更有意义；无数据时仍可跑通链路，但摘要多为「未检索到」类提示。

### 2.2 手工接口测试（推荐先做）

1. 启动后端：`mvn -pl bootstrap -am spring-boot:run`
2. 完成登录，取得 **`Authorization`**（Sa-Token，与现有聊天接口相同）。
3. 使用支持 **SSE** 的客户端访问：

```http
GET http://localhost:9090/api/ragent/rag/v3/deep-research?topic=你的研究主题&deepThinking=false
Header: Authorization: <token>
```

4. **观察事件顺序**（与前端约定一致）：
   - `meta`：含 `conversationId`、`taskId`（用于 `POST /rag/v3/stop` 取消）
   - `message`，`type` 为 **`deep_research`**：阶段 JSON，如 `{"state":"PARALLEL_RETRIEVE","detail":"..."}`
   - `message`，`type` 为 **`response`**：最终深度报告流式增量
   - `finish` / `done`

5. **可选**：用 `taskId` 调用 `POST /api/ragent/rag/v3/stop?taskId=...` 验证取消逻辑。

### 2.3 使用 curl（Windows 需注意引号与编码）

```bash
curl -N -H "Authorization: <你的token>" "http://localhost:9090/api/ragent/rag/v3/deep-research?topic=RAG%E5%B7%A5%E7%A8%8B%E5%8C%96%E8%A6%81%E7%82%B9&deepThinking=false"
```

`-N` 禁用缓冲，便于看到 SSE 流。

### 2.4 自动化测试（当前仓库状态）

- **尚未添加** `DeepResearchServiceImpl` 的专用单元/集成测试类。
- **可扩展方向**（供后续实现）：
  - **单元测试**：对 `parseSubQuestionsJson` 等纯解析逻辑提取为包可见方法或独立工具类后，用固定字符串断言 JSON 解析结果；对 `LLMService`、`MultiChannelRetrievalEngine` 使用 **Mockito** 模拟，验证状态顺序与线程池调用次数。
  - **集成测试**：`@SpringBootTest` + 测试配置指向测试库/禁用真实 LLM（需 Testcontainers 或 WireMock），成本较高，与现有 `QueryRewriteTests` 等风格一致（依赖真实环境时需在 CI 中标记为可选）。

---

## 三、现有 `bootstrap` 测试类与功能对应关系

以下均为 **`@SpringBootTest`** 拉起完整 Spring 上下文；多数会访问真实 Bean，**运行前需本地/CI 具备数据库、Redis、部分外部 API 等**（以各测试类实际为准）。

| 测试类 | 路径（包下） | 覆盖能力（大致） |
|--------|----------------|------------------|
| `RagentCoreApplicationTests` | 根包 | 上下文能否启动（`contextLoads`） |
| `QueryRewriteTests` | `rag.rewrite` | 查询改写、与 `LLMService` 相关 |
| `MultiQuestionRewriteServiceTests` | `rag.rewrite` | 多问句拆分与改写 |
| `SiliconFlowEmbeddingServiceTests` | `rag.embedding` | SiliconFlow Embedding |
| `PgVectorStoreServiceTest` | `rag.core.vector` | PostgreSQL + pgvector 向量存储 |
| `MilvusCollectionTests` | `vector` | Milvus 集合相关 |
| `SimpleIntentClassifierTests` | `rag.Intent` | 意图分类（简化场景） |
| `VectorTreeIntentClassifierTests` | `rag.Intent` | 向量树意图分类 |
| `IntentTreeServiceTests` | `rag.Intent` | 意图树服务 |
| `ScheduleRefreshProcessorTest` | `knowledge.schedule` | 知识库调度刷新 |
| `InvoiceIndexDocumentTests` | `index` | 索引/文档（发票场景） |
| `ConversationMessageServiceTests` | `service` | 会话消息服务 |

**说明**：`bootstrap/src/test/.../VectorIntentClassifier.java` 为测试辅助类（非 `*Tests` 命名），供意图相关测试使用。

---

## 四、其他功能的测试方式归纳

| 功能域 | 自动化测试覆盖 | 其他验证方式 |
|--------|----------------|--------------|
| 普通 RAG 对话 SSE | 无专门 `*Chat*Test` | 手工调 `/rag/v3/chat`；`scripts/sse_queue_test.sh` 做并发/排队 |
| Deep Research | 无 | 见本文第二节 |
| 限流/排队 | 无独立单测（逻辑在 AOP/Limiter） | 上述脚本 + 压测 |
| MCP 主应用侧 | 无 | 联调 `mcp-server` + 意图配置 |
| `mcp-server` | 未发现 `src/test` | 手工 `POST /mcp` JSON-RPC |
| `framework` 模块 | 无 | 随 `bootstrap` 集成测试间接覆盖 |
| `infra-ai` 模块 | 无 | 随 `bootstrap` + Embedding/LLM 类测试覆盖 |
| 入库 Pipeline | 无专门 `Ingestion*Test`（当前列表） | `docs/examples`、管理后台手工 |
| 前端 | 无（仓库内未见 Jest/Playwright 配置） | `npm run dev` 手工与 E2E（若自行引入） |

---

## 五、结论与建议

1. **Deep Research**：当前以 **手工 SSE 联调**为主；配置 `rag.deep-research.*`、观察 `deep_research` 与 `response` 两阶段事件即可完整验证。
2. **项目测试现状**：测试代码**仅存在于 `bootstrap` 模块**，共十余个类，以 **Spring Boot 集成测试**为主，**不是**全链路单元测试矩阵。
3. **缺口**：`RAGChatController`、`DeepResearchService`、大量 `ingestion` / `knowledge` 业务、**`framework` / `infra-ai` / `mcp-server`** 均无独立测试目录；若需 CI 稳定，建议逐步补充 **Mock 单元测试** 与 **Testcontainers 集成测试**，并对依赖外网的测试加 `@Disabled` 或 Maven Profile。

---

## 六、文档修订记录

- 随 Deep Research 功能增加整理本文，与 `README.md` 中「§7 Deep Research」「§14 示例」互为补充。
