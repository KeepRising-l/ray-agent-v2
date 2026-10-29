# RayAgent V2

当前提供本机多轮聊天、网页工具循环和文档向量入库：支持取消、超时、上下文预算、Tavily 搜索、公开网页抓取，以及 TXT/Markdown/文本 PDF 上传、定位分块和 pgvector 写入。登录、知识库问答与 AUTO 路由尚未实现。

首版个人自用，提供三个模式：

- CHAT：智能对话，可按需联网，不读取知识库。
- KNOWLEDGE：按授权资料检索并引用，无依据明确说明。
- AUTO：按意图选择或组合知识库与联网，分别标明来源。

开发采用“先讲设计与取舍 → 小步实现 → 用户读代码 → 运行验证”。系统参考Harness机制，RAG业务自己实现；旧RayAgent保留参考。

本项目仍在早期开发阶段，规划和学习进度暂在本地维护。

后端采用 Java 17、Spring Boot 4.1.2；在根目录运行 `mvn clean package`，再用 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar` 启动。前端在 `ray-agent-web` 目录运行 `npm ci` 后用 `npm run dev` 启动，用 `npm run build` 构建。模型契约和适配器已有测试，并已通过一次本机兼容网关的真实流式探测；已提供本机最小聊天接口和页面；尚未部署。

## 模型配置

后端通过环境变量 `RAY_MODEL_BASE_URL`、`RAY_MODEL_MODEL` 和 `RAY_MODEL_API_KEY` 可选装配 OpenAI Chat Completions 兼容适配器。Base URL 指向 `/v1` 层级，应用会追加 `/chat/completions`。缺少模型配置时不注册适配器；数据库连接仍必须正确配置；请勿将真实密钥写入仓库。

可在根目录的本机配置中设置 `ray.model.extra-channels`（逗号分隔），并为每个名称设置 `ray.model.channel.<名称>.model`。额外渠道默认沿用主渠道的地址和密钥，也可以分别配置 `base-url` 和 `api-key`。聊天页按渠道选择模型；渠道名称与配置模型名不证明实际上游供应商身份。修改配置后需要重启后端。

调用的开始、结束和失败状态写入本机 `logs/ray-agent.log`；日志目录和本机模型配置均被 Git 忽略。当前记录用于本地诊断，不是数据库审计记录。

开发阶段的聊天接口未鉴权，后端默认仅监听 127.0.0.1，暂不用于公开部署。手动真实接口探测需额外设置 `RAY_LIVE_TEST=true`，然后在工程根目录运行 `mvn -pl ray-agent-runtime -Dtest=LiveModelSmokeTest test`；默认构建会跳过此探测。探测会向所配置的模型发送一条测试消息，可能产生用量。

## 运行最小聊天

在工程根目录的 `application-local.properties` 中配置模型（该文件被 Git 忽略，密钥不写入仓库），或在同一个 PowerShell 终端设置 `RAY_MODEL_BASE_URL`、`RAY_MODEL_MODEL` 和 `RAY_MODEL_API_KEY`。以工程根目录为工作目录运行 `mvn package` 后执行 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar`。在另一个终端进入 `ray-agent-web`，运行 `npm ci` 和 `npm run dev`，通过终端给出的本地地址打开页面。页面支持新建和切换会话；每次向模型提交系统约束、预算内的近期成功问答和本次问题。刷新可查数据库历史；不自动接回已断开的 token 流。

## PostgreSQL 与运行记录

本机连接放在根目录被 Git 忽略的 `application-local.properties`：`spring.datasource.url`、`spring.datasource.username`、`spring.datasource.password`。当前使用独立数据库 `ray_agent_v2`；Spring 启动时执行 `schema.sql` 初始化 `ra_session`、`ra_run`、`ra_message`、`ra_event` 表。此初始化脚本不替代后续正式版本迁移。

登录前的 owner 由服务端固定为 `local-owner`，不接收前端自定义身份；当前仅支持本机开发，尚无多用户认证。一个数据库 schema 只允许一个运行实例，避免启动恢复影响另一实例。

运行有 `RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`、`INTERRUPTED` 状态。已收到的部分回答在终态时保存，进程异常退出前未落库的输出可能丢失；重启后仅标记旧运行中断，不自动重放。输入估算与供应商实际用量分别展示，缺失用量保留为空。

常规检查：`mvn test`、前端 `npm test` 和 `npm run build`。真实 PostgreSQL/模拟模型的本机集成检查可运行 `python tools/verify_r02.py`，它通过 WSL 容器 `pgvector-db` 创建并清理独立测试 schema，不调用真实模型。真实数据库 JUnit 检查使用 `RAY_PG_TEST=true` 和 `RAY_TEST_DB_URL`、`RAY_TEST_DB_USER`、`RAY_TEST_DB_PASSWORD`，默认构建跳过它。

## 网页工具（R03）

在根目录被 Git 忽略的 `application-local.properties` 中配置 `ray.tools.web.tavily-api-key`，或使用环境变量 `RAY_TOOLS_WEB_TAVILY_API_KEY`。按 [Tavily 官方接口](https://docs.tavily.com/api-reference/endpoint/search)执行 basic 搜索，最多取三条摘要，不使用其生成式 answer。缺少密钥时，搜索返回 NOT_CONFIGURED，普通聊天和公开网页抓取仍可用。修改配置后重启后端。

本机代理若启用了 Fake-IP，会把域名解析为保留地址（例如 198.18.*），公网策略会拒绝。可在本机配置设置 `ray.tools.web.dns-mode=public-doh`，通过固定 Cloudflare 1.1.1.1 的 [DoH JSON 接口](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/make-api-requests/dns-json/)获取公网 IPv4；默认 system 使用系统 DNS。两种方式都校验所有返回地址并固定实际连接 IP，保留域名进行 TLS 验证；重定向每跳重新检查。DoH 模式会把所访问域名交给该公共解析服务。

请求链路：`ChatApplicationService → ReactLoopAgent → 模型提出工具调用 → ToolExecutor 校验/执行 → TOOL 消息回填 → 下一次模型生成 → 保存回答`。普通问题仍只生成一次。运行默认最多 5 次模型生成、6 次工具尝试、60 秒总时限，单次工具 15 秒；对应 `ray.run.max-model-steps`、`ray.run.max-tool-calls`、`ray.run.timeout-ms`、`ray.run.tool-timeout-ms`。当前采用有界线程池，工具按模型给出的顺序执行。

抓取仅支持公开 HTTP(S) 标准端口的静态 HTML/TXT，最多三跳重定向、1 MB 响应、5000 字符正文。脚本不执行；内网、本机、保留地址、凭据 URL、PDF、登录态网页和非预期压缩响应被拒绝。搜索摘要与抓取正文作为不可信资料交给模型，不改变 Java 允许名单。CHAT 当前只登记网页工具，不读取知识库。

模型引用使用本次运行的 `[W1]` 等编号，页面工具卡展示实际来源链接，并区分“搜索摘要”和“已抓取正文”。校验编号是否存在及有网页资料时是否提供编号，不保证模型每项陈述都已得到语义核验。工具失败同样交回模型，可解释限制或在预算内调整；预算耗尽、来源编号错误或模型中断明确失败。已输出文本作为未完成回答保留。刷新可恢复工具卡；切换运行轨迹可查看之前的来源。历史上下文保留成功问答的文本投影，当前不重放旧工具调用或旧来源编号。

执行轨迹保存工具名、调用编号、状态、耗时和来源，避免记录密钥、工具参数和网页全文。未知用量保持未知，多步用量只有供应商每步提供时才可求完整总和。

验证入口见 [工具循环验收](tests/acceptance/tool-loop.md)：`python tools/verify_r03.py`（模拟模型＋真实网页与数据库）；添加 `--live` 使用本机真实模型及 Tavily，可能消耗模型和搜索用量；添加 `--browser` 验证工具卡和刷新，需本机 Chrome 和 Playwright。脚本只启动/停止自己的进程并清理随机测试 schema；打包与启动脚本请顺序执行，Windows 下运行中的 jar 会被占用。

## 知识库文档入库（R04）

重启后端后，前端切换到“知识库管理”，创建知识库，再上传 TXT、Markdown 或文本 PDF。原文件保存在被 Git 忽略的 `.ray-data/documents`，可通过 `ray.knowledge.storage-root` 修改；数据库保存 owner、知识库、业务文档、不可变文件摘要和版本、片段位置、向量空间与处理状态。原文件用服务端 UUID 存储，文件名不参与路径计算。

Embedding 单独配置在根目录被忽略的 `application-local.properties`：

```properties
ray.embedding.base-url=https://api.siliconflow.cn/v1
ray.embedding.model=BAAI/bge-m3
ray.embedding.dimension=1024
ray.embedding.revision=v1
ray.embedding.api-key=填写你自己的密钥
```

以上协议参考[硅基流动 Embeddings](https://docs.siliconflow.cn/cn/api-reference/embeddings/create-embeddings)，批次按返回 index 还原顺序并校验模型、数量、维度和有限数值。配置中的接口、模型、维度和 revision 共同标识向量空间；密钥不参与指纹，也不传到前端。改模型应使用新的文档版本/后续重建索引流程，不混用旧向量。

处理状态为 UPLOADED → PARSING → CHUNKING → EMBEDDING → READY，失败为 FAILED，服务中断为 INTERRUPTED。上传成功仅代表文件和版本已保存，READY 才代表向量原子发布完成；本轮还不能进行知识库问答。外部 Embedding 调用在事务之外，向量和有效版本指针在同一事务发布。失败的新版本保留旧有效版本；相同内容复用当前版本，最新失败/中断版本可手动重试。

首版只用一个持久任务消费者，不引入 Redis/MQ。服务重启保留 UPLOADED 的待处理任务，把处理中任务标为 INTERRUPTED；不会静默重做外部调用。解析/分块策略随版本保存，策略变更时应上传新版本。当前解析总量上限 20 万字符、1000 个片段，PDF 最多 100 页；分块不跨段落或页，长段落按 800 个 Unicode 码点、120 个码点重叠切分，片段保存页/段和规范化文本中的 UTF-16 偏移。该参数只是基线，尚未做召回效果评估。

上传最多 5 MB，文件摘要、类型及 owner 范围均校验。文本要求 UTF-8，文本 PDF 使用 PDFBox 3.0.8 保留页码；空文本、扫描件、损坏及加密 PDF 明确失败，不执行 OCR。片段预览以文本展示，不执行 Markdown 中的 HTML 或脚本。原文片段会发送到已配置的外部 Embedding 服务。

数据库初始化显式加载 `schema.sql`、`db/migration/V004__knowledge_document.sql` 和 `V005__document_chunk_vector.sql`，在本项目数据库的 public schema 安装 vector 扩展。SQL 文件可重复执行，当前仍使用 Spring SQL 初始化，并未启用 Flyway 的版本迁移机制。向量列用 [pgvector 的 vector 类型](https://github.com/pgvector/pgvector#can-i-store-vectors-with-different-dimensions-in-the-same-column)，通过 profile 和维度限制写入，R05 再实现带 owner/知识库/有效版本过滤的检索。

接口入口为 `/api/knowledge-bases`，包含建库、文档上传、版本列表/状态/片段、向量数量与失败重试。服务端仍固定绑定本机 owner；保留所有权约束不等于已经有登录或多用户认证。文档删除、向量清理和全量重建按后续生命周期课卡继续实现。

验证说明见 [文档入库验收](tests/acceptance/document-ingestion.md)。先完成 `mvn package` 再运行 `python tools/verify_r04.py`，默认是模拟 Embedding＋真实 PostgreSQL/pgvector；添加 `--live` 使用本机真实 Embedding，添加 `--browser --live` 验证真实上传、PDF 页码、刷新及失败展示。测试资料均为自写，测试只清理自己的随机 schema 和 `.ray-data/r04-check-*` 目录。
