package cn.ray.agent.server.identity;

import cn.ray.agent.server.conversation.LocalOwner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 请求操作者来自认证上下文；后台不接受调用方覆盖owner。异步任务仍使用已绑定的服务端个人owner。 */
@Component
public class CurrentActor {
    private final LocalOwner owner;
    public CurrentActor(LocalOwner owner) { this.owner = owner; }
    public String ownerId() { return owner.id(); }
    public String username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_OWNER"))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return authentication.getName();
    }
}
