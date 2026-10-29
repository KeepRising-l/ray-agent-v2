package cn.ray.agent.server.rag.embedding;

import java.util.List;

public interface EmbeddingClient {
    EmbeddingProfile profile();

    List<float[]> embed(List<String> texts);

    final class Failure extends RuntimeException {
        private final String code;

        public Failure(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
