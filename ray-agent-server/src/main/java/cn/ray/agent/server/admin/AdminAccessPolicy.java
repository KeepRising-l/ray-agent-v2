package cn.ray.agent.server.admin;

import cn.ray.agent.server.identity.CurrentActor;
import org.springframework.stereotype.Component;

/** 后台复用同一个个人身份，不把菜单显示当鉴权；API仍由SecurityFilterChain保护。 */
@Component
public class AdminAccessPolicy {
    private final CurrentActor actor;
    public AdminAccessPolicy(CurrentActor actor) { this.actor=actor; }
    public String owner() { actor.username();return actor.ownerId(); }
    public String username() { return actor.username(); }
}
