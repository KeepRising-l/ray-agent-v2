package cn.ray.agent.server.identity;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 采用框架的会话认证、session fixation防护与CSRF；只有一个OWNER账号，不开放注册/共享。 */
@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService ownerUsers(OwnerCredentials credentials) {
        return new InMemoryUserDetailsManager(User.withUsername(credentials.username())
                .password(credentials.passwordHash()).roles("OWNER").build());
    }

    @Bean
    BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/session", "/api/auth/login").permitAll()
                .requestMatchers("/api/**").hasRole("OWNER").anyRequest().permitAll())
            .requestCache(cache -> cache.disable())
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, error) -> json(res, 401, "请先登录"))
                .accessDeniedHandler((req, res, error) -> json(res, 403, "操作未获授权或页面已过期，请刷新后重试")))
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> json(res, 200, "登录成功"))
                .failureHandler((req, res, error) -> json(res, 401, "账号或密码不正确")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> json(res, 200, "已退出")))
            .addFilterBefore(new LoginLimit(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    /** 单账号的固定大小限流，不按任意用户名分配内存；最多每分钟20次登录认证。 */
    private static final class LoginLimit extends OncePerRequestFilter {
        private long window;
        private int attempts;
        private synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - window >= 60000) { window = now; attempts = 0; }
            return ++attempts <= 20;
        }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getServletPath().equals("/api/auth/login") && request.getMethod().equals("POST") && !allow()) {
                response.setHeader("Retry-After", "60"); json(response, 429, "登录尝试过多，请稍后重试"); return;
            }
            chain.doFilter(request, response);
        }
    }
}
