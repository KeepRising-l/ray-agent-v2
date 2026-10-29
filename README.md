# RayAgent V2

当前提供本机单次文本问答的最小链路；登录、会话历史和知识库功能尚未实现。

首版个人自用，提供三个模式：

- CHAT：智能对话，可按需联网，不读取知识库。
- KNOWLEDGE：按授权资料检索并引用，无依据明确说明。
- AUTO：按意图选择或组合知识库与联网，分别标明来源。

开发采用“先讲设计与取舍 → 小步实现 → 用户读代码 → 运行验证”。系统参考Harness机制，RAG业务自己实现；旧RayAgent保留参考。

本项目仍在早期开发阶段，规划和学习进度暂在本地维护。

后端采用 Java 17、Spring Boot 4.1.2；在根目录运行 `mvn clean package`，再用 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar` 启动。前端在 `ray-agent-web` 目录运行 `npm ci` 后用 `npm run dev` 启动，用 `npm run build` 构建。模型契约和适配器已有测试，并已通过一次本机兼容网关的真实流式探测；已提供本机最小聊天接口和页面；尚未部署。

## 模型配置

后端通过环境变量 `RAY_MODEL_BASE_URL`、`RAY_MODEL_MODEL` 和 `RAY_MODEL_API_KEY` 可选装配 OpenAI Chat Completions 兼容适配器。Base URL 指向 `/v1` 层级，应用会追加 `/chat/completions`。缺少配置时不注册适配器，应用仍可启动；请勿将真实密钥写入仓库。

开发阶段的聊天接口未鉴权，后端默认仅监听 127.0.0.1，暂不用于公开部署。手动真实接口探测需额外设置 `RAY_LIVE_TEST=true`，然后在工程根目录运行 `mvn -pl ray-agent-runtime -Dtest=LiveModelSmokeTest test`；默认构建会跳过此探测。探测会向所配置的模型发送一条测试消息，可能产生用量。

## 运行最小聊天

在工程根目录的 `application-local.properties` 中配置模型（该文件被 Git 忽略，密钥不写入仓库），或在同一个 PowerShell 终端设置 `RAY_MODEL_BASE_URL`、`RAY_MODEL_MODEL` 和 `RAY_MODEL_API_KEY`。以工程根目录为工作目录运行 `mvn package` 后执行 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar`。在另一个终端进入 `ray-agent-web`，运行 `npm ci` 和 `npm run dev`，通过终端给出的本地地址打开页面。每次提交只发送当前消息，刷新后没有历史。
