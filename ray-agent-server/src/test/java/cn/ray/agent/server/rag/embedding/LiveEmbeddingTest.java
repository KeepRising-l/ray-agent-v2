package cn.ray.agent.server.rag.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RAY_EMBEDDING_TEST", matches = "true")
class LiveEmbeddingTest {
    @Test
    void realEmbeddingMatchesConfiguredModelDimensionAndOrder() {
        var profile = new EmbeddingProfile(URI.create(System.getenv("RAY_TEST_EMBEDDING_URL") + "/embeddings"),
                System.getenv("RAY_TEST_EMBEDDING_MODEL"), Integer.parseInt(System.getenv("RAY_TEST_EMBEDDING_DIMENSION")), "v1");
        try (var client = new OpenAiEmbeddingClient(profile, System.getenv("RAY_TEST_EMBEDDING_KEY"), Duration.ofSeconds(30))) {
            var vectors = client.embed(List.of("RayAgent self-written sample: trees need water.", "RayAgent self-written sample: a database stores records."));
            assertEquals(2, vectors.size());
            vectors.forEach(profile::validate);
            assertFalse(java.util.Arrays.equals(vectors.get(0), vectors.get(1)));
        }
    }
}
