package cn.ray.agent.server.rag.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.time.Duration;

@Configuration
public class EmbeddingConfiguration {
    @Bean(destroyMethod = "close")
    public OpenAiEmbeddingClient embeddingClient(
            @Value("${ray.embedding.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${ray.embedding.api-key:}") String key,
            @Value("${ray.embedding.model:BAAI/bge-m3}") String model,
            @Value("${ray.embedding.dimension:1024}") int dimension,
            @Value("${ray.embedding.revision:v1}") String revision) {
        var profile = new EmbeddingProfile(URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings"), model, dimension, revision);
        return new OpenAiEmbeddingClient(profile, key, Duration.ofSeconds(30));
    }
}
