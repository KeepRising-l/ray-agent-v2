package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.agent.ReactLoopAgent;
import cn.ray.agent.runtime.tool.ToolExecutor;
import cn.ray.agent.runtime.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** server 提供具体网页能力，runtime 无需认识 Tavily、Spring 或数据库。 */
@Configuration
public class WebToolConfiguration {
    @Bean
    public ToolRegistry toolRegistry(@Value("${ray.tools.web.tavily-api-key:}") String key,
                                     @Value("${ray.tools.web.dns-mode:system}") String dnsMode) {
        var registry = new ToolRegistry();
        var policy = AllowedUrlPolicy.configured(dnsMode);
        var transport = new PublicWebTransport(policy);
        registry.register(new WebFetchTool(transport, policy, new WebContentExtractor()));
        // 未配置时也保留搜索定义，执行返回明确的 NOT_CONFIGURED，避免模型假装已经搜索。
        registry.register(new WebSearchTool(key, transport, policy));
        return registry;
    }

    @Bean(destroyMethod = "close")
    public ToolExecutor toolExecutor(ToolRegistry registry) {
        return new ToolExecutor(registry);
    }

    @Bean(destroyMethod = "close")
    public ReactLoopAgent reactLoopAgent(ToolRegistry registry, ToolExecutor executor) {
        return new ReactLoopAgent(registry, executor);
    }
}
