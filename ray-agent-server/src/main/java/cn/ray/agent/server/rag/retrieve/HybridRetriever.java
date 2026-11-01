package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.server.rag.store.PgVectorStore;
import cn.ray.agent.server.rag.embedding.EmbeddingProfile;
import org.springframework.stereotype.Component;
import java.util.List;

/** 同一次查询向量供两路使用；两路先各自排序，再融合，不并发多发一次Embedding。 */
@Component
public class HybridRetriever {
    private final PgVectorStore vectors;
    private final KeywordRetriever keywords;
    public HybridRetriever(PgVectorStore vectors, KeywordRetriever keywords) {
        this.vectors = vectors;
        this.keywords = keywords;
    }
    public Comparison compare(RetrievalQuery query, EmbeddingProfile profile, float[] vector) {
        var candidates = new RetrievalQuery(query.question(), query.filter(), 8, query.minimumScore());
        long start = System.nanoTime();
        var semantic = vectors.search(candidates, profile, vector);
        long vectorEnd = System.nanoTime();
        var literal = keywords.search(candidates, profile, vector);
        long keywordEnd = System.nanoTime();
        var fused = new RankFusion().fuse(semantic, literal, query.limit());
        return new Comparison(semantic.stream().limit(query.limit()).toList(), literal.stream().limit(query.limit()).toList(), fused,
                (vectorEnd-start)/1_000_000.0, (keywordEnd-vectorEnd)/1_000_000.0, (System.nanoTime()-start)/1_000_000.0);
    }
    public record Comparison(List<RetrievedChunk> vector, List<RetrievedChunk> keyword, List<RetrievedChunk> hybrid,
                             double vectorMs, double keywordMs, double hybridMs) { }
}
