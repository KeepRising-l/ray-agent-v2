package cn.ray.agent.server.rag.embedding;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 模型、接口、维度与人工版本共同标识向量空间；指纹不包含密钥。 */
public record EmbeddingProfile(URI endpoint, String model, int dimension, String revision) {
    public EmbeddingProfile {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || model == null || model.isBlank() || model.length() > 200
                || dimension < 1 || dimension > 4096 || revision == null || revision.isBlank() || revision.length() > 100) {
            throw new IllegalArgumentException("invalid embedding profile");
        }
    }

    public String id() {
        try {
            byte[] input = (endpoint + "\n" + model + "\n" + dimension + "\n" + revision).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 写入前再次验证，防止模拟接口、错误模型或数据库重试混入别的维度。 */
    public void validate(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new EmbeddingClient.Failure("DIMENSION_MISMATCH", "Embedding 返回维度与配置不一致");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingClient.Failure("INVALID_VECTOR", "Embedding 包含非有限数值");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new EmbeddingClient.Failure("INVALID_VECTOR", "Embedding 返回了零向量");
        }
    }
}
