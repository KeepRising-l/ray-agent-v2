package cn.ray.agent.server.rag.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {
    private EmbeddingProfile profile(int port) {
        return new EmbeddingProfile(URI.create("http://127.0.0.1:" + port + "/embeddings"), "test-model", 3, "v1");
    }

    @Test
    void ordersReversedBatchByIndexAndSendsOnlyConfiguredCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            assertEquals("Bearer test-only-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("test-model") && body.contains("input"));
            byte[] response = "{\"model\":\"test-model\",\"data\":[{\"index\":1,\"embedding\":[0,1,0]},{\"index\":0,\"embedding\":[1,0,0]}]}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = new OpenAiEmbeddingClient(profile(server.getAddress().getPort()), "test-only-key", Duration.ofSeconds(2))) {
            var vectors = client.embed(List.of("first", "second"));
            assertArrayEquals(new float[]{1, 0, 0}, vectors.get(0));
            assertArrayEquals(new float[]{0, 1, 0}, vectors.get(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWrongModelCountDimensionIndicesAndNonNumericOrZeroVectors() throws Exception {
        for (String response : List.of(
                "{\"model\":\"other\",\"data\":[]}",
                "{\"model\":\"test-model\",\"data\":[{\"index\":0,\"embedding\":[1,2]}]}",
                "{\"model\":\"test-model\",\"data\":[{\"index\":9,\"embedding\":[1,2,3]}]}",
                "{\"model\":\"test-model\",\"data\":[{\"index\":0,\"embedding\":[0,0,0]}]}",
                "{\"model\":\"test-model\",\"data\":[{\"index\":0,\"embedding\":[1,\"NaN\",3]}]}")) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/embeddings", exchange -> {
                byte[] bytes = response.getBytes();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try (var client = new OpenAiEmbeddingClient(profile(server.getAddress().getPort()), "test", Duration.ofSeconds(2))) {
                assertThrows(EmbeddingClient.Failure.class, () -> client.embed(List.of("test")));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void httpFailuresDoNotExposeProviderBodyAndProfileSeparatesSpaces() throws Exception {
        var profile = profile(1);
        assertNotEquals(profile.id(), new EmbeddingProfile(profile.endpoint(), "another", 3, "v1").id());
        assertNotEquals(profile.id(), new EmbeddingProfile(profile.endpoint(), "test-model", 4, "v1").id());
        assertNotEquals(profile.id(), new EmbeddingProfile(profile.endpoint(), "test-model", 3, "v2").id());
        try (var missing = new OpenAiEmbeddingClient(profile, "", Duration.ofSeconds(1))) {
            assertEquals("NOT_CONFIGURED", assertThrows(EmbeddingClient.Failure.class, () -> missing.embed(List.of("test"))).code());
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "private-provider-token".getBytes();
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (var client = new OpenAiEmbeddingClient(profile(server.getAddress().getPort()), "test", Duration.ofSeconds(2))) {
            var failure = assertThrows(EmbeddingClient.Failure.class, () -> client.embed(List.of("test")));
            assertEquals("HTTP_401", failure.code());
            assertFalse(failure.getMessage().contains("private-provider-token"));
        } finally {
            server.stop(0);
        }
    }
}
