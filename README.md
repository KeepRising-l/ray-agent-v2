# RayAgent V2

当前提供本机多轮聊天、网页工具、知识库问答与AUTO路由：支持文档入库/引用/生命周期、摘要投影、提问接续、有界队列与任务查询、会话工作区、个人登录和管理后台。R11补充可关闭的检索实验、故障容量与交付验证；本机容器干净启动已验证，公开部署未验证。

首版个人自用，提供三个模式：

- CHAT：智能对话，可按需联网，不读取知识库。
- KNOWLEDGE：按授权资料检索并引用，无依据明确说明。
- AUTO：按意图选择或组合知识库与联网，分别标明来源。

开发采用“先讲设计与取舍 → 小步实现 → 用户读代码 → 运行验证”。系统参考Harness机制，RAG业务自己实现；旧RayAgent保留参考。

本项目仍在早期开发阶段，规划和学习进度暂在本地维护。

后端采用 Java 17、Spring Boot 4.1.2；在根目录运行 `mvn clean package`，再用 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar` 启动。前端在 `ray-agent-web` 目录运行 `npm ci` 后用 `npm run dev` 启动，用 `npm run build` 构建。模型契约和适配器已有测试，并已通过一次本机兼容网关的真实流式探测；已提供本机最小聊天接口和页面；尚未部署。

## 模型配置

后端通过环境变量 `RAY_MODEL_BASE_URL`、`RAY_MODEL_MODEL` 和 `RAY_MODEL_API_KEY` 可选装配 OpenAI Chat Completions 兼容适配器。Base URL 指向 `/v1` 层级，应用会追加 `/chat/completions`。缺少模型配置时不注册适配器；数据库连接仍必须正确配置；请勿将真实密钥写入仓库。

可在根目录的本机配置中设置 `ray.model.extra-channels`（逗号分隔），并为每个名称设置 `ray.model.channel.<名称>.model`。额外渠道默认沿用主渠道的地址和密钥，也可以分别配置 `base-url` 和 `api-key`。这些配置用于首次初始化；后台保存配置后，以数据库配置为准。后台可修改渠道、默认模型与开关，对新运行生效；已有运行保留快照。聊天页按渠道选择模型；渠道名称与配置模型名不证明实际上游供应商身份。

调用的开始、结束和失败状态写入本机 `logs/ray-agent.log`；日志目录和本机模型配置均被 Git 忽略。当前记录用于本地诊断，不是数据库审计记录。

所有业务API均要求个人账号登录，写操作还需CSRF令牌。后端默认仅监听127.0.0.1，暂不用于公开部署。手动真实接口探测需额外设置 `RAY_LIVE_TEST=true`，然后在工程根目录运行 `mvn -pl ray-agent-runtime -Dtest=LiveModelSmokeTest test`；默认构建会跳过此探测。探测会向所配置的模型发送一条测试消息，可能产生用量。

## 个人登录与管理后台（R10）

首次以工程根目录启动后端，会在受Git忽略的 `.ray-data/identity/initial-login.txt` 生成账号 `owner` 和随机初始密码，请在本机打开该文件查看。账号的BCrypt散列保存在同目录 `owner.properties`，重启不会重新生成密码；不要将两个文件提交到Git。保存好登录凭据后可删除初始明文文件，账号仍可使用。若需重置，先停止后端，备份并移走这两个身份文件，再启动生成新账号；业务数据仍属于原来的个人owner。

登录后点击“管理后台”，可以管理模型渠道、向量配置、知识库、任务补偿、工具与路由策略，并查看运行快照、用量和操作记录。密钥输入留空表示保留，明确勾选才清空，接口只返回是否配置；后台录入的密钥仅保存在受忽略的 `.ray-data/admin/secrets.properties`，数据库保存引用。该文件是本机明文配置，当前没有密钥加密服务。备份需同时保留数据库、身份文件、密钥文件及原文目录。

修改Embedding前必须结束活动问答、等待补充与文档处理；新向量空间不会复用旧索引，需要逐文档明确重建。R10之前没有配置快照的待补充运行，不能宣称可按原配置接续，应取消后重新提问。详见[后台设计](docs/r10-admin-design.md)与[后台验收](tests/acceptance/admin-console.md)。

## 运行最小聊天

在工程根目录的 `application-local.properties` 中配置模型（该文件被 Git 忽略，密钥不写入仓库），或在同一个 PowerShell 终端设置 `RAY_MODEL_BASE_URL`、`RAY_MODEL_MODEL` 和 `RAY_MODEL_API_KEY`。以工程根目录为工作目录运行 `mvn package` 后执行 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar`。在另一个终端进入 `ray-agent-web`，运行 `npm ci` 和 `npm run dev`，通过终端给出的本地地址打开页面。页面支持新建和切换会话；每次向模型提交系统约束、预算内的近期成功问答和本次问题。刷新可查数据库历史；不自动接回已断开的 token 流。

## PostgreSQL 与运行记录

本机连接放在根目录被 Git 忽略的 `application-local.properties`：`spring.datasource.url`、`spring.datasource.username`、`spring.datasource.password`。当前使用独立数据库 `ray_agent_v2`；Spring 启动时执行 `schema.sql` 初始化 `ra_session`、`ra_run`、`ra_message`、`ra_event` 表。此初始化脚本不替代后续正式版本迁移。

已登录的唯一个人账号由服务端绑定为 `local-owner`，不接收前端自定义身份；保留既有个人数据，不等于已支持多用户。异步任务继续使用服务端绑定的owner。一个数据库 schema 只允许一个运行实例，避免启动恢复影响另一实例。

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

处理状态为 UPLOADED → PARSING → CHUNKING → EMBEDDING → READY，失败为 FAILED，服务中断为 INTERRUPTED。上传成功仅代表文件和版本已保存，READY 才代表向量原子发布完成；R05知识库问答只检索当前有效版本。外部 Embedding 调用在事务之外，向量和有效版本指针在同一事务发布。失败的新版本保留旧有效版本；相同内容复用当前版本，最新失败/中断版本可手动重试。

首版只用一个持久任务消费者，不引入 Redis/MQ。服务重启保留 UPLOADED 的待处理任务，把处理中任务标为 INTERRUPTED；不会静默重做外部调用。解析/分块策略随版本保存，策略变更时应上传新版本。当前解析总量上限 20 万字符、1000 个片段，PDF 最多 100 页；分块不跨段落或页，长段落按 800 个 Unicode 码点、120 个码点重叠切分，片段保存页/段和规范化文本中的 UTF-16 偏移。该参数只是基线，尚未做召回效果评估。

上传最多 5 MB，文件摘要、类型及 owner 范围均校验。文本要求 UTF-8，文本 PDF 使用 PDFBox 3.0.8 保留页码；空文本、扫描件、损坏及加密 PDF 明确失败，不执行 OCR。片段预览以文本展示，不执行 Markdown 中的 HTML 或脚本。原文片段会发送到已配置的外部 Embedding 服务。

数据库初始化显式加载 `schema.sql`、`db/migration/V004__knowledge_document.sql`、`V005__document_chunk_vector.sql` 和 `V006__knowledge_chat_lifecycle.sql`，在本项目数据库的 public schema 安装 vector 扩展。SQL 文件可重复执行，当前仍使用 Spring SQL 初始化，并未启用 Flyway 的版本迁移机制。向量列用 [pgvector 的 vector 类型](https://github.com/pgvector/pgvector#can-i-store-vectors-with-different-dimensions-in-the-same-column)，通过 profile 和维度限制写入；检索在计算距离前限定 owner、知识库、有效版本与当前向量空间。

接口入口为 `/api/knowledge-bases`，包含建库、文档上传、版本列表/状态/片段、向量数量与失败重试。接口要求登录，服务端绑定个人owner；保留所有权约束不等于已实现多用户。R05增加文档删除和逐文档索引重建，批量全量重建后续再接。

验证说明见 [文档入库验收](tests/acceptance/document-ingestion.md)。先完成 `mvn package` 再运行 `python tools/verify_r04.py`，默认是模拟 Embedding＋真实 PostgreSQL/pgvector；添加 `--live` 使用本机真实 Embedding，添加 `--browser --live` 验证真实上传、PDF 页码、刷新及失败展示。测试资料均为自写，测试只清理自己的随机 schema 和 `.ray-data/r04-check-*` 目录。

## 知识库问答与索引生命周期（R05）

聊天页选择新会话模式 KNOWLEDGE，勾选1～5个知识库后点击“新建会话”。模式和资料范围在会话创建时固定；CHAT 不带知识库，也不会继承知识库会话的历史。AUTO 已在 R06 接入，范围和执行规则见下文。

固定流程为 `RetrievalService → EvidenceAssembler → 证据判断 → GroundedAnswerService → 引用校验`。SQL先按 owner、所选知识库、未删除文档、有效 READY 版本和当前 EmbeddingProfile 过滤，再按余弦相似度精确排序，最多取6个片段。当前没有通用硬编码分数阈值；可用 `ray.rag.minimum-score` 设置本数据集需要的阈值，不把相似度当成答案正确概率。

没有可用资料时直接返回固定的“依据不足”说明，不调用模型编造答案。存在候选时先调用模型判断资料是否足够，并校验返回的K编号和逐字摘录是否真实存在；足够后才生成带 `[K1]` 等引用的回答。KNOWLEDGE 不进入 ReactLoopAgent，不提供工具 Schema，模型若仍返回工具调用则失败。普通CHAT继续原有网页工具路径。

检索正文以当前USER消息内的不可信JSON数据传入，与本次问题作为同一轮保留；不会写进SYSTEM指令。证据判断和回答生成都执行上下文预算校验。判断过程不显示为回答，最终回答仍流式传输；错误引用、来源在回答期间更新/删除、模型截断或超时会让运行失败，已传出的内容保留为未完成。引用编号和摘录校验不等于每个陈述都经过语义证明。复杂多轮指代改写、重排和混合检索尚未实现。

检索元数据、分数、候选与实际引用分别保存为运行事件。正文只临时进入生成上下文；点击引用时通过 `/api/runs/{run}/citations/{K编号}` 重新校验 owner 和当前版本再取原文。旧引用在文档更新、重建或删除后显示失效，不返还旧片段全文。RAG输入估算累计证据判断和回答生成两次实际组装的消息，用量未知时保留为空。

知识库管理中的“重建索引”保留原文件，生成新文档版本并绑定当前目标向量空间；新索引完整发布后才切换有效版本。历史版本保留但不参与当前检索。删除通过数据库事务取消可见性并移除所有片段/向量，处理中任务不能复活文档；原文件清理使用持久补偿队列，失败后每5秒重试。它不与文件系统组成同一个数据库事务。

第一组固定评估见 [RAG评估说明](tests/evaluation/rag-evaluation.md) 和 `tests/fixtures/rag-cases.json`。`python tools/verify_r05.py` 为模拟模型＋真实数据库；`--live` 使用本机真实模型和 Embedding，运行20问及版本生命周期检查；`--browser --live` 验证模式、引用原文、刷新、删除后引用失效和拒答。评估只报告实际运行的样例，不把小样本结果当通用准确率。


## 三模式与AUTO（R06）

新建会话时选择CHAT、KNOWLEDGE或AUTO；AUTO可选0～5个知识库，KNOWLEDGE必须选1～5个，CHAT不接收知识库。范围由服务端校验归属并保存，切换模式需新建会话。

统一链路：`ChatApplicationService → ModeDispatcher → GeneralChatWorkflow / KnowledgeChatWorkflow / AutoChatWorkflow`。AUTO先以规则处理问候、明确资料/联网及禁止要求，其余以严格JSON做有限意图分类。分类失败先澄清，不自动扩大资料或工具范围；运行轨迹保存选择目标与简短原因码，刷新可恢复。

混合链路：`授权检索 → 一次搜索 → 预算允许时抓取首个结果 → 三栏目来源校验 → 输出`。知识库用K编号、网页用W编号，缺失与冲突分别说明；最终混合JSON先缓冲校验，正文随后以SSE发布，检索与工具轨迹仍实时显示。纯CHAT和纯KNOWLEDGE不会经过AUTO分类。分类与生成共用模型预算与时限，用量只在实际提供时累计。

验收入口：[三模式验收](tests/acceptance/three-modes.md)、[本机演示步骤](docs/demo-script.md)。脚本为 `python tools/verify_r06.py --browser`，真实接口加 `--live`；先完成Maven打包再启动脚本。没有部署、登录或多用户认证；不会把路由小样本成功率当通用效果。


## 摘要与等待用户（R07）

长对话在预算压力下整理旧轮次摘要，并保留最近4轮；原始历史不删除。摘要覆盖范围、内容指纹与当前投影持久保存，重启后校验恢复；它是历史数据，不是当前知识库/网页的事实依据。配置 `ray.context.compaction-trigger` 默认14000（本地保守估算），`ray.context.keep-recent-turns` 默认4。摘要生成另耗模型用量，只有包含包装开销的投影确实变短才应用；失败保留原投影并回退近期选择。

缺少必要参数时，CHAT/AUTO的模型可单独调用ask_user；AUTO歧义也可先提问。页面显示补充表单，运行状态WAITING_USER，释放执行资源；刷新或重启仍可回答，按原runId和questionId继续。已执行工具不会重放，原允许名单与剩余预算保留；取消、过期、重复回答分别处理。等待期限由 `ray.question.ttl-ms` 配置，默认24小时；等待不占活跃时限。

接口为 `GET /api/runs/{runId}/question` 和 `POST /api/runs/{runId}/answers`，后者正文含questionId/answer并返回SSE；服务端固定身份校验归属。当前上下文诊断 `GET /api/sessions/{id}/context` 返回原范围与模型投影，UI历史继续显示原始消息。SQL新增V007，仍使用Spring显式初始化。

验收见[摘要与接续验收](tests/acceptance/context-and-resume.md)：先打包，再运行 `python tools/verify_r07.py --browser`，真实模型加 `--live`。当前只支持单独ask_user和AUTO路由两类版本1检查点，不宣称任意位置崩溃恢复或生产部署。R07新增代码由助手协作实现，用户阅读与理解待补。

## 并发、任务队列与恢复（R08）

不同会话默认最多2个run执行、8个等待；同会话有活动run时新问题409，重复clientRequestId可查询原run，容量满429且不创建run。SSE与运行记录区分QUEUED/RUNNING/终态，排队可取消和超时；异步流结束或等待用户时才释放执行槽。配置为`ray.run.max-concurrent`、`ray.run.queue-capacity`、`ray.run.max-per-owner`、`ray.run.queue-timeout-ms`。

顶部“任务与运行”查询当前owner的聊天与文档任务，可取消活动run、查看原会话、查询文档尝试记录，并重试最新失败/中断文档。文档临时Embedding失败默认最多自动重试2次，认证/格式/策略错误不自动重试。重启将遗留QUEUED/RUNNING聊天及处理中入库标记中断，不自动重放聊天；有效WAITING_USER仍可按原检查点回答。

查询接口与能力边界、配置和复现命令见[队列与恢复验收](tests/acceptance/queue-and-recovery.md)。当前为单进程FIFO加持久状态，数据库单实例锁仍有效。R08初次因本机Embedding密钥删除收到401；2026-10-31用户提供新配置后，真实模型/Embedding/PG与浏览器复验通过。新增源码待用户阅读；个人登录已在R10补齐，本机容器启动在R11验证，公开部署仍待验收。

## 会话工作区与交互恢复（R09）

聊天左栏切换/新建会话，右侧显示服务端模式、资料范围、历史和输入；新建设置不会改写已有会话。切换会话时迟到响应与流式文本按原会话隔离，草稿在本页分别暂存。手机布局收起新建表单，让当前对话优先可见。

正文K/W引用可直接定位该回答所属运行的原文或网页来源，旧回答的来源不必通过运行编号猜选。文档页区分处理状态与上一有效版本，展示页段、尝试记录和失败入口。运行过程按事件序号去重，估算与供应商usage分开显示。

网络结果不明确时先“查询本次请求”；原请求编号可在刷新浏览器后查回，避免盲目整轮重跑。模型失败可“编辑后重新提问”，该操作只回填输入；发送才创建新运行。文档仍按最新失败版本重试。设计与验收见[工作区说明](docs/r09-workspace-design.md)和[交互恢复验收](tests/acceptance/ui-recovery.md)。提交状态以Git历史为准，新增源码尚未学习。

## 检索实验与启动交付（R11）

向量检索仍为默认基线。关键词召回、RRF名次融合、受限多轮指代补全和当前模型候选重排均可关闭；在本机配置中修改并重启：

```properties
ray.rag.experimental.hybrid=false
ray.rag.experimental.rewrite=false
ray.rag.experimental.rerank=false
```

两路召回共用owner、知识库、有效版本及当前向量空间过滤。RRF不相加不同含义的分数，引用仍展示余弦值。改写仅补充最近用户问题，重排只接受已有候选编号的完整排列；失败回退原范围，预算不足跳过重排。策略保存进run快照，等待接续时策略不兼容返回409而不消费原问题。

自写20题真实Embedding对照中，向量@6为19/20、融合20/20；这只是候选证据命中，小样本不作为默认启用依据或回答准确率。执行`python tools/verify_r11.py`做隔离模拟验证，`--live`使用本机真实模型/向量；比较API为已鉴权的`POST /api/admin/retrieval/compare`，每次可能产生一次Embedding用量。详见[检索对照](tests/evaluation/retrieval-comparison.md)与[质量报告](tests/evaluation/quality-report.md)。

部署配置见[干净启动](docs/operations/clean-start.md)。Docker从源码构建前后端并放在同一个应用镜像，数据库和个人数据使用独立卷。`GET /api/health`只报告本进程与数据库就绪，不代表外部模型/向量在线。演示和故障处理分别见[演示脚本](docs/demo-script.md)、[恢复手册](docs/operations/recovery-playbook.md)；[贡献事实清单](docs/resume-claims.md)保留AI协作与用户尚未阅读的边界。
