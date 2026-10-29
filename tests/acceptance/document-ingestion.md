# R04 文档入库验收

R04-01～R04-05 按用户要求协作实现。用户本轮提供了 Embedding 供应商和本机配置，尚未开始阅读本轮代码，不标为已经理解。配置、原文件和测试运行产物均被 Git 忽略；本轮不自动提交、推送或部署。

## 主链路与阅读位置

`DocumentUploadController → DocumentService → DocumentFileStore + DocumentRepository.accept → UPLOADED`。

后台 `DocumentIngestionService` 领取任务后：`ParserSelector → TextDocumentParser/PdfDocumentParser → DocumentChunker → OpenAiEmbeddingClient → PgVectorStore → DocumentRepository.publish → READY`。

原文和文档版本是不同对象。段落/页保留定位，chunkId 由版本和分块位置生成。只有向量全部写完，事务才切换有效版本。新版本失败时不覆盖旧有效版本。解析和外部调用不长期占用数据库事务。

## 检查入口

- `mvn test`：解析、PDF 页码及失败、Unicode/分块、文件边界、Embedding 协议和原有运行契约。外部测试默认跳过。
- 前端 `npm test` / `npm run build`：状态判定、业务错误与已有 SSE/工具事件。
- `PostgresKnowledgeTest`：设置 `RAY_PG_TEST=true` 与 `RAY_TEST_DB_URL/USER/PASSWORD`，使用随机 schema；验证 owner、重复/并发上传、摘要/大小/类型、向量真实存取、维度拒绝、失败版本保护、幂等重试与中断恢复。Embedding 为模拟。
- `LiveEmbeddingTest`：设置 `RAY_EMBEDDING_TEST=true` 与 `RAY_TEST_EMBEDDING_URL/MODEL/DIMENSION/KEY`，真实模型返回正确数量/顺序/维度；默认不运行。
- `python tools/verify_r04.py`：真实 PostgreSQL/pgvector＋模拟 Embedding，验证完整 HTTP 上传、PDF 位置、错误文件、版本发布与进程强杀重启/显式重试。
- `python tools/verify_r04.py --live`：相同入库主链路改用真实本机 Embedding 配置，写入并读回真实向量；不运行模拟慢接口的强杀用例。
- `python tools/verify_r04.py --browser --live`：真实模型/数据库下浏览器建库、上传 Markdown、刷新、上传两页 PDF、核对第2页来源和空 PDF 失败。需本机 Chrome/Playwright，截图在 `logs/r04-browser.png`。

打包和运行 jar 的脚本必须顺序执行。脚本仅清理自己创建的随机 schema、进程与受忽略的测试目录；不清理用户文件，不停止 IDEA。

## 已通过与事实边界（2026-10-29）

常规后端45项测试、前端10项与构建通过；真实 PostgreSQL/pgvector 5项、真实 Embedding 1项另行启用通过。模拟和真实 Embedding 的 API 入库验收、真实模型浏览器验收均通过；原 R02 聊天/取消/超时/重启回归通过。

真实配置为硅基流动 BAAI/bge-m3，1024维；pgvector 0.8.6。仅向外部模型发送自写测试文字。模拟3维向量与真实1024维测试使用独立 schema，不能混记为同一项真实模型证据。浏览器初次断言误匹配了下拉框就绪文字，已改为等候详情中的片段/向量实际数量。

本节仅完成入库与定位预览。知识库检索、证据组装、引用回答属于 R05；AUTO 属于 R06。登录、OCR、文档删除与索引清理、全量重建、检索效果评估尚未实现。编号 SQL 由 Spring 初始化显式执行，尚未接入 Flyway。片段是规范化提取文本，不保证复杂 PDF 的阅读顺序和版面结构被完整理解。
