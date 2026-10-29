package cn.ray.agent.server.conversation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 登录尚未实现时的固定本机 owner；不接受前端指定 owner，不能代表已经有认证。 */
@Component
public record LocalOwner(@Value("${ray.local.owner-id:local-owner}") String id) { }
