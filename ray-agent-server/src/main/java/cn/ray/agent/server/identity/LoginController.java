package cn.ray.agent.server.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class LoginController {
    private final cn.ray.agent.server.admin.ModelSettingsService models;
    public LoginController(cn.ray.agent.server.admin.ModelSettingsService models){this.models=models;}
    /** GET仅返回会话状态与本页CSRF令牌，不返回口令、散列或密钥。 */
    @GetMapping("/api/auth/session")
    public Map<String, Object> session(HttpServletRequest request, Authentication authentication) {
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return Map.of("authenticated", authenticated, "username", authenticated ? authentication.getName() : "",
                "csrfToken", csrf.getToken(), "csrfHeader", csrf.getHeaderName(),"runtimeReady",models.isReady());
    }
}
