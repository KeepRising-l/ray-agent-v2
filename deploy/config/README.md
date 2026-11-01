此目录只提交说明文件。可在本机创建`application-local.properties`配置模型、Embedding与Tavily；该文件受Git和Docker构建上下文忽略，只作为只读运行挂载。

不要直接复制带`localhost`的数据库配置；数据库连接由Compose环境提供。容器里的`127.0.0.1`是容器本身，本机中转地址必须改成容器可访问的地址。没有配置外部接口时仍可登录、管理资料元数据与查看后台，但不能据此声称模型或Embedding可用。
