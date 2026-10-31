# R08 并发、队列与任务恢复验收

2026-10-31。实现验收基于main的7db1337，由助手协作完成R07/R08；提交与推送状态以Git历史和双远程为准。用户尚未阅读R08新增源码，理解待确认。

## 执行链路

`ChatController → ChatApplicationService → 请求幂等检查 → BoundedRunExecutor.reserve → 会话行锁/创建QUEUED → QueuedRunDispatcher → RunQueue → TaskStateRepository.start → 上下文与工作流 → 模型/工具 → 唯一终态`。

同会话只允许一个活动run，包含QUEUED、RUNNING、WAITING_USER；新请求返回409，由用户等待、回答或取消后再发。这里的串行保证是互斥，不是自动把同会话多个问题排队并拼接上下文。创建与检查使用短事务行锁，并由部分唯一索引兜底，不在模型生成期间持有数据库事务。

不同会话共享单进程FIFO执行槽，先预留容量再保存输入。默认2个执行槽、8个排队位置；`ray.run.max-per-owner=0`表示单owner可占总容量，正数可进一步限制。排队满或owner额度满返回429，不创建该请求的run。`/api/tasks/capacity`提供本进程执行数、排队数与拒绝计数，重启后计数重置。

异步订阅方法返回不代表模型执行完毕，permit持续到Flow终态、取消、超时或WAITING_USER。取消在上游订阅建立前也有效；被取消的底层线程池占位同时移除，避免快速取消撑满另一层队列。

SSE先发meta和queue/QUEUED，取得槽后发queue/RUNNING。排队默认30秒超时（`ray.run.queue-timeout-ms`），执行活跃时限仍由`ray.run.timeout-ms`控制，排队时间不扣活跃预算。排队超时为TIMED_OUT，事件说明排队超时；未调用模型。排队取消同样为CANCELLED，不能把入队当成成功。

## 请求幂等

`(owner, sessionId, clientRequestId)`定位原run。重复请求409携带runId、status、sameInput和Location；消息或渠道不同则sameInput=false，不能用旧编号提交新问题。不重新订阅旧的一次性流。

`GET /api/sessions/{session}/requests/{clientRequestId}`可在客户端不确定请求是否已接收时查询原运行；未接收404。并发重复由会话事务内再次检查，只有一个用户消息和run。普通重复与不同请求的同会话互斥是不同的拒绝原因。

## 文档尝试与重试

复用`DocumentIngestionService`作为单消费者，不另建空的DocumentIngestionWorker；`BoundedRunExecutor.Job`承担运行worker职责，不另建空RunWorker。每次成功领取文档版本后新增一次持久尝试，记录PARSING/CHUNKING/EMBEDDING/PUBLISHED阶段、状态、错误码及开始/结束时间。版本绑定目标向量空间，既有稳定chunkId、替换分块、完整向量发布事务继续保证重试不增生片段、不发布半套索引。

仅Embedding的网络失败、429及5xx允许有限退避，默认最多2次自动重试，即最多3次尝试；`ray.document.max-auto-retries`和`ray.document.retry-backoff-ms`控制上限与初始延迟，指数退避最多60秒。401、未配置、坏JSON、错误模型/维度、解析/策略错误和进程中断不自动重试。手动重试仅允许最新失败或中断版本，重置本轮自动重试计数，保留总尝试历史。

原READY版本仅在新版本完整发布后切换；删除和新版本抢占后旧任务不能复活。旧的历史文档若早于V008，则attempt为0，不伪造过去的尝试记录。自动调度与文档处理共用既有轮询消费者，数据库失败后下一轮重试轮询。

## 查询与恢复边界

- `GET /api/tasks`：当前owner的聊天与文档状态投影，按时间合并最多100条；文档显示attempt、stage、code、自动重试计数/计划。
- `GET /api/tasks/documents/{versionId}/attempts`：该文档版本的尝试记录；不返回检查点原文、密钥或文档正文。
- `GET /api/sessions/status`：持久会话与最新run状态；`attached`只表示本进程当前有活动连接，不能当数据库真相。
- `GET /api/runs/{runId}/recovery`：WAITING_USER且检查点有效才返回ANSWER_PENDING；仍通过R07的answers接口继续。其他状态返回NONE，不提供虚假的整轮恢复按钮。

活动连接登记按对象身份清理，旧SSE的结束回调不能删掉同run的新接续登记。回答先预留容量再消费questionId，过载不应丢掉待答问题；恢复保留原runId，调度phase加1，不把它当模型任务的自动重跑。

继续沿用每schema的PostgreSQL单实例advisory锁。重启后旧QUEUED/RUNNING标INTERRUPTED，不重放内存队列；WAITING_USER保留有效检查点。文档处理中断标INTERRUPTED，用户显式重试。未领取的UPLOADED文档仍能被轮询领取。当前不是持久消息队列、多实例调度或exactly-once执行，外部工具副作用不能靠本地状态消除。

## 验证与复现

先运行`mvn -B package`，再启动脚本；Windows中运行的jar不能同时被打包覆盖。

1. `python tools/verify_r08.py --browser`：隔离真实PostgreSQL/pgvector，模拟模型和Embedding。验证1执行/2排队、FIFO、429不落库、幂等409与查询、同会话互斥、排队/运行取消、排队超时、重启不重放、受控503后自动重试、文档中断后手动重试、同run的WAITING_USER接续；浏览器检查状态/取消/尝试记录/查看会话。
2. `python tools/verify_r08.py --live --skip-embedding`：本机真实聊天模型，经受控延迟代理复现排队，验证相同聊天与检查点路径。代理延迟和崩溃由脚本控制，不冒称真实供应商事故；此命令明确跳过文档Embedding。
3. 密钥恢复后可用`python tools/verify_r08.py --live`重新验证真实Embedding。2026-10-31首次复验收到401，用户确认已删除本机key；本轮真实Embedding未通过，未恢复或拷回旧密钥。
4. 真实PG JUnit需显式开启`RAY_PG_TEST=true`及`RAY_TEST_DB_URL/USER/PASSWORD`。PostgresTaskTest覆盖8项：并发幂等、排队/重启、同run恢复、尝试计数与退避上限、401不重试、owner边界、中断重试与片段/向量幂等。其他四组PG回归共21项，合计29项通过。
5. 后端常规86项通过，32项外部测试默认跳过；前端19项与构建通过。新增针对性单测覆盖异步槽生命周期、排队取消、底层占位清理、同run连接登记替换，以及坏JSON不自动重试。
6. R02会话/运行回归、R05模拟20问与索引生命周期/取消、R06三模式、R07摘要与接续回归结果见统一进度表。模拟模型不等于真实模型，旧阶段真实Embedding证据也不替代本轮复验。

报告在忽略的`logs/r08-mock.json`、`logs/r08-live.json`，截图`logs/r08-browser.png`。脚本只清理自己的schema、进程和`.ray-data/r08-check-*`。WSL最初停止导致连接拒绝，启动并保持WSL实例后PG测试通过。SQL新增V008，仍由Spring显式初始化，未引入Flyway、Redis或MQ。

登录、部署、任意位置恢复与用户代码学习留待后续；R08实现和验证不代表用户已掌握源码。
