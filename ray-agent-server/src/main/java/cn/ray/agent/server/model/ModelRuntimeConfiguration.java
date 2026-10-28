package cn.ray.agent.server.model;

import cn.ray.agent.runtime.model.ModelRegistry;
import cn.ray.agent.runtime.model.OpenAiCompatibleAdapter;
import cn.ray.agent.runtime.model.RetryPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;

/** 可选的模型装配：只有提供完整配置才注册真实适配器。 */
@Configuration
public class ModelRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ray.model", name = {"base-url", "api-key", "model"})
    OpenAiCompatibleAdapter openAiCompatibleAdapter(Environment env) {
        String baseUrl = env.getRequiredProperty("ray.model.base-url").replaceAll("/+$", "");
        String apiKey = env.getRequiredProperty("ray.model.api-key");
        String model = env.getRequiredProperty("ray.model.model");
        return new OpenAiCompatibleAdapter(URI.create(baseUrl + "/chat/completions"), apiKey, model,
                new RetryPolicy(2, Duration.ofMillis(200)));
    }

    @Bean
    ModelRegistry modelRegistry(ObjectProvider<OpenAiCompatibleAdapter> adapterProvider) {
        ModelRegistry registry = new ModelRegistry();
        OpenAiCompatibleAdapter adapter = adapterProvider.getIfAvailable();
        if (adapter != null) registry.register("openai-compatible", adapter);
        return registry;
    }
}
