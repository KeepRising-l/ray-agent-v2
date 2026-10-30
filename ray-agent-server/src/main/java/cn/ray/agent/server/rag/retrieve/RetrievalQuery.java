package cn.ray.agent.server.rag.retrieve;

public record RetrievalQuery(String question, RetrievalFilter filter, int limit, Double minimumScore) {
    public RetrievalQuery {
        if (question == null || question.isBlank() || question.length() > 4000 || limit < 1 || limit > 8
                || (minimumScore != null && (!Double.isFinite(minimumScore) || minimumScore < -1 || minimumScore > 1))) {
            throw new IllegalArgumentException("invalid retrieval query");
        }
    }
}
