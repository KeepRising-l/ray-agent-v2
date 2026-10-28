# RayAgent V2

当前有后端最小 Maven 骨架与启动入口，尚无业务源码或前端。

首版个人自用，提供三个模式：

- CHAT：智能对话，可按需联网，不读取知识库。
- KNOWLEDGE：按授权资料检索并引用，无依据明确说明。
- AUTO：按意图选择或组合知识库与联网，分别标明来源。

开发采用“先讲设计与取舍 → 小步实现 → 用户读代码 → 运行验证”。系统参考Harness机制，RAG业务自己实现；旧RayAgent保留参考。

本项目仍在早期开发阶段，规划和学习进度暂在本地维护。

后端采用 Java 17、Spring Boot 4.1.2；在根目录运行 `mvn clean package`，再用 `java -jar ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar` 启动。当前仅验证打包与启动，没有业务接口或测试；尚未调用模型或部署。下一步先阅读骨架，再继续R00-02。
