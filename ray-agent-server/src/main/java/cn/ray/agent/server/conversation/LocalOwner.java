package cn.ray.agent.server.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 单账号绑定的服务端owner，保护已有数据归属并可用于异步线程；HTTP入口统一要求个人账号登录。 */
@Component
public record LocalOwner(@Value("${ray.local.owner-id:local-owner}") String id) { }
