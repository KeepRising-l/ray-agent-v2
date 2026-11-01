# 从干净环境启动

适用R11，本机个人单实例。只要求Docker Engine与Compose，不依赖宿主机已装JDK、Maven、Node、数据库或原项目缓存。构建阶段分别使用Node 24、Maven 3.9.9/JDK17；运行阶段只有JRE17、打包后的前后端与PostgreSQL/pgvector。公网发布、域名和TLS另行验收。

## 首次启动

克隆本工程后，在Linux或WSL的工程根目录执行。先确认`docker version`与`docker compose version`可用；Windows本机现有`pgvector-db`不受这份Compose管理。

```bash
# 只在文件不存在时复制，避免覆盖已有私密配置。
test -e deploy/.env || cp deploy/.env.example deploy/.env
# 用编辑器填写deploy/.env中的随机RAY_DB_PASSWORD；不要提交真实值。
docker compose --env-file deploy/.env -f deploy/compose.yml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d
docker compose --env-file deploy/.env -f deploy/compose.yml ps
```

默认地址`http://127.0.0.1:8080`。前端已经打包进Spring Boot，不需要另启Vite或配置跨域。数据库不发布宿主端口，应用端口只绑定127.0.0.1。若8080被占用，在私密`.env`修改RAY_HTTP_PORT，不停止别的项目。

首次生成账号`owner`及随机密码；只在自己的终端查看：

```bash
docker compose --env-file deploy/.env -f deploy/compose.yml exec app cat /app/.ray-data/identity/initial-login.txt
```

登录后可进入管理后台。账号散列、本机密钥、原文分别在data卷，数据库与日志有独立卷；重启不会重置账号或清空资料。保存好密码后可删除初始明文文件，散列文件必须保留。不能仅备份Git来恢复个人数据。

## 外部模型与向量

在`deploy/config/application-local.properties`填入自己的接口，也可登录后台录入。真实密钥文件受Git与Docker构建上下文忽略，只作为运行时只读挂载；首次后台保存后数据库配置优先。本机IDEA使用的根目录配置不会自动进入镜像。

```properties
ray.model.base-url=https://你的兼容接口/v1
ray.model.model=你的聊天模型
ray.model.api-key=自行填写
ray.embedding.base-url=https://api.siliconflow.cn/v1
ray.embedding.model=BAAI/bge-m3
ray.embedding.dimension=1024
ray.embedding.api-key=自行填写
```

容器里的localhost不是Windows宿主机。本机中转站须提供容器可访问的地址，并按实际宿主/WSL网络配置验证；本文不自动把中转站暴露公网。首次没有配置密钥仍可登录与查看后台，不能完成模型问答或向量入库。

`GET /api/health`只说明本进程配置恢复与数据库可访问，不调用任何外部接口。因此健康不等于真实模型、Embedding或Tavily在线。登录后在后台分别检查，并使用自己的无敏感资料完成一次上传与引用问答。

## 日常停止、更新与备份

```bash
docker compose --env-file deploy/.env -f deploy/compose.yml stop
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d
```

更新前先停止app，备份数据库、data卷和私密配置，以保证原文与数据库指针对应。SQL初始化当前为可重复执行的Spring SQL脚本，未采用Flyway；不要把换镜像当成任意版本回滚方案。详细故障步骤见[恢复手册](recovery-playbook.md)。

`.env`里的POSTGRES初始密码只对空数据库生效，已有数据库不能靠修改文件重置密码；需要交互式修改数据库账号密码，再同步应用配置。不要为修复连接错误删除数据库卷。

普通`down`保留命名卷。`down -v`会删除该项目全部数据，只能用于明确命名、确认是自己新建的隔离测试项目；本项目没有自动清空正式数据的脚本。

## 验证边界

实际构建与新卷启动结果、镜像版本、首次账号、重启保留、数据库故障和浏览器证据记在[质量报告](../../tests/evaluation/quality-report.md)。文档里的命令不是已执行证明。Docker清洁启动测试不读取用户的本机密钥；真实外部服务在单独的隔离宿主进程中验证。

Compose机制参考[Docker官方启动依赖说明](https://docs.docker.com/compose/how-tos/startup-order/)，镜像上下文隔离参考[Docker官方.dockerignore说明](https://docs.docker.com/build/building/context/#dockerignore-files)。
