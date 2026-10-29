# R03 工具循环验收与阅读入口

范围为 R03-01～R03-04，助手按用户要求一次协作实现。用户明确尚未开始本节学习，新增代码未读，理解待讨论。当前先推进功能，带读后补；实现和验证不计为用户已经掌握。提交状态以 Git 为准，尚未部署。

## 主链路

`ChatApplicationService.stream` 组装成功历史与新问题，把允许名单传给 `ReactLoopAgent.bind`。模型输出文字就转发 SSE；输出工具碎片则由 `StepResult` 收齐。`ToolExecutor` 先检查允许名单、注册状态、严格参数，再在超时内执行。结果以 TOOL 消息回填同一次运行，模型再生成。普通问题没有工具调用时只走一步。

先看 `ReactLoopAgent.run` 的循环，再看 `ToolExecutor.execute` 的边界，最后对照 `WebSearchTool` / `WebFetchTool`。`ToolEventBridge` 先保存事件再通过 SSE 发给页面，`ToolExecutionCard.vue` 从事件还原状态与来源。每次模型生成结束并不代表整个 run 已结束。

## 可复现检查

先运行 `mvn package`，待结束再启动测试脚本；运行中的 jar 在 Windows 上会被占用。前端在 `ray-agent-web` 运行 `npm test`、`npm run build`。

- `mvn test`：契约、片段组装、工具白名单、参数、异常、超时、取消、循环/用量/引用和模拟网页边界。真实测试默认跳过。
- `python tools/verify_r02.py`：回归原多轮、幂等、互斥、取消、超时和中断恢复。
- `python tools/verify_r03.py`：真实 PostgreSQL 随机 schema、模拟模型 HTTP/SSE、真实 example.com。验证普通聊天、工具回填、禁止地址、参数、预算、取消、顺序事件及持久状态。
- `python tools/verify_r03.py --live`：使用本机兼容模型，分别真实抓取和 Tavily 搜索，检查真实成功事件、来源、引用与落库。需要本机模型及 Tavily 配置，会产生外部调用。
- `python tools/verify_r03.py --browser` 或 `--browser --live`：本机 Chrome/Playwright 验证卡片、正文来源链接、引用和刷新恢复；模拟模式还验证失败卡没有来源。截图存 Git 忽略的 `logs/r03-browser.png`。
- 手动 `LiveWebToolTest`：环境变量 `RAY_WEB_TEST=true`、`RAY_WEB_API_KEY`，Fake-IP 环境另设 `RAY_WEB_DNS=public-doh`；真实搜索和正文抓取探测，默认不运行。
- 手动 `PostgresConversationTest`：沿用 R02 的 `RAY_PG_TEST` 与 `RAY_TEST_DB_*`，新增结构化长事件、所有权和终态后禁止追加检查。

## 已验证与边界（2026-10-29）

已通过模拟模型＋真实 PostgreSQL/网页的五条运行路径和取消；本机真实兼容模型分别执行 web_fetch、web_search，观察结果回填后带引用回答并保存轨迹；真实 Tavily 和公网抓取单独探测通过；R02 原有集成回归通过。

后端常规37项测试通过，7项外部测试默认跳过；真实PostgreSQL5项和真实网页1项另行启用通过，前端8项及构建通过。浏览器模拟/真实模型均验证卡片、来源、回答引用与刷新恢复；模拟模型另验失败卡无来源。本机 Fake-IP 初测被安全策略拒绝，改用固定公网 DoH 后通过，未放开内网访问。example.com 的页面标题和正文分别验证，不依赖过时页面文本。

一次浏览器真实模型请求没有等到工具调用，后续独立复测通过，不据此宣称上游始终稳定；前端历史刷新时序已修复。完整详细记录在既有统一规划目录，未另建学习账本。

本轮不包含登录、知识库、Embedding、AUTO 路由、动态网页执行或工具并行编排。来源编号校验不等于逐句事实核验；未对真实供应商注入超时/故障，异常与预算路径主要由模拟验证。工具元数据和来源保存，参数/网页全文未持久化，进程崩溃不重放工具。
