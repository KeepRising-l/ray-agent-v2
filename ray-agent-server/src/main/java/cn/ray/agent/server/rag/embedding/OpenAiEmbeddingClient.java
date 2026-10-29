package cn.ray.agent.server.rag.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 独立于聊天模型的兼容 Embeddings 客户端；按 index 还原批次顺序，失败不返回占位向量。 */
public final class OpenAiEmbeddingClient implements EmbeddingClient, AutoCloseable {
    private final EmbeddingProfile profile;
    private final String key;
    private final Duration timeout;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();

    public OpenAiEmbeddingClient(EmbeddingProfile profile, String key, Duration timeout) {
        this.profile = profile;
        this.key = key;
        this.timeout = timeout;
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    public boolean configured() {
        return key != null && !key.isBlank();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!configured()) {
            throw new Failure("NOT_CONFIGURED", "Embedding 密钥尚未配置；配置后重启并重试文档");
        }
        if (texts.isEmpty() || texts.size() > 8 || texts.stream().anyMatch(text -> text == null || text.isBlank() || text.length() > 8000)) {
            throw new IllegalArgumentException("invalid embedding input batch");
        }
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            var body = mapper.createObjectNode().put("model", profile.model()).put("encoding_format", "float");
            var input = body.putArray("input");
            texts.forEach(input::add);
            var request = HttpRequest.newBuilder(profile.endpoint()).timeout(timeout)
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            future = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            requests.add(future);
            var response = future.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw new Failure("HTTP_" + response.statusCode(), "Embedding 服务返回 HTTP " + response.statusCode());
            }
            if (response.body().length > 4_000_000) {
                throw new Failure("INVALID_RESPONSE", "Embedding 响应过大");
            }
            var data = mapper.readTree(response.body());
            if (!data.path("model").asText().equals(profile.model()) || !data.path("data").isArray()
                    || data.path("data").size() != texts.size()) {
                throw new Failure("INVALID_RESPONSE", "Embedding 返回模型或数量与请求不符");
            }
            float[][] vectors = new float[texts.size()][];
            for (var item : data.path("data")) {
                int index = item.path("index").asInt(-1);
                if (!item.path("index").isInt() || index < 0 || index >= texts.size() || vectors[index] != null
                        || !item.path("embedding").isArray()) {
                    throw new Failure("INVALID_RESPONSE", "Embedding 批次编号或向量格式无效");
                }
                float[] vector = new float[item.path("embedding").size()];
                for (int i = 0; i < vector.length; i++) {
                    if (!item.path("embedding").get(i).isNumber()) {
                        throw new Failure("INVALID_VECTOR", "Embedding 向量必须由数值组成");
                    }
                    vector[i] = item.path("embedding").get(i).floatValue();
                }
                profile.validate(vector);
                vectors[index] = vector;
            }
            return List.of(vectors);
        } catch (Failure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("embedding interrupted");
        } catch (Exception e) {
            throw new Failure("REQUEST_FAILED", "Embedding 请求失败或超时，请检查接口后重试");
        } finally {
            if (future != null) {
                requests.remove(future);
                future.cancel(true);
            }
        }
    }

    @Override
    public void close() {
        requests.forEach(future -> future.cancel(true));
    }
}
