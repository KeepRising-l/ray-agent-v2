package cn.ray.agent.server.rag.store;

import cn.ray.agent.server.rag.chunk.DocumentChunk;
import cn.ray.agent.server.rag.embedding.EmbeddingProfile;
import cn.ray.agent.server.rag.retrieve.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 使用 pgvector 精确存储；每条记录保留 profile，维度与模型空间不得混用。 */
@Repository
public class PgVectorStore implements VectorStore {
    private final JdbcTemplate jdbc;

    public PgVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(EmbeddingProfile profile, List<DocumentChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("embedding count mismatch");
        }
        vectors.forEach(profile::validate);
        jdbc.update("INSERT INTO ra_embedding_profile VALUES (?,?,?,?) ON CONFLICT(id) DO NOTHING",
                profile.id(), profile.model(), profile.dimension(), profile.revision());
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectors.get(i);
            String value = IntStream.range(0, vector.length).mapToObj(index -> Float.toString(vector[index]))
                    .collect(Collectors.joining(",", "[", "]"));
            jdbc.update("INSERT INTO ra_chunk_vector VALUES (?,?,?,CAST(? AS public.vector)) ON CONFLICT(chunk_id) DO UPDATE SET profile_id=EXCLUDED.profile_id,dimension=EXCLUDED.dimension,embedding=EXCLUDED.embedding",
                    chunks.get(i).id(), profile.id(), profile.dimension(), value);
        }
    }

    @Override
    public int count(String versionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ra_chunk_vector v JOIN ra_document_chunk c ON c.id=v.chunk_id WHERE c.version_id=?", Integer.class, versionId);
    }

    private String scopeSql(RetrievalFilter filter) {
        return AuthorizedChunkSql.from(filter);
    }

    private java.util.ArrayList<Object> scopeArgs(RetrievalFilter filter, EmbeddingProfile profile) {
        return AuthorizedChunkSql.args(filter, profile);
    }

    public boolean hasEligible(RetrievalFilter filter, EmbeddingProfile profile) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 " + scopeSql(filter) + ")", Boolean.class, scopeArgs(filter, profile).toArray()));
    }

    /** MATERIALIZED 先收拢授权、当前版本与向量空间，再计算距离，避免不同维度参与运算。 */
    public List<RetrievedChunk> search(RetrievalQuery query, EmbeddingProfile profile, float[] vector) {
        profile.validate(vector);
        String value = IntStream.range(0, vector.length).mapToObj(index -> Float.toString(vector[index])).collect(Collectors.joining(",", "[", "]"));
        String sql = "WITH eligible AS MATERIALIZED (SELECT c.*,d.kb_id,d.id AS document_id,d.name,v.version,x.embedding "
                + scopeSql(query.filter()) + ") SELECT *,1-(embedding OPERATOR(public.<=>) CAST(? AS public.vector)) AS score "
                + "FROM eligible ORDER BY score DESC,id LIMIT ?";
        var args = scopeArgs(query.filter(), profile);
        args.add(value);
        args.add(query.limit());
        var hits = jdbc.query(sql, (r, n) -> new RetrievedChunk(r.getString("id"), r.getString("kb_id"), r.getString("document_id"),
                r.getString("name"), r.getString("version_id"), r.getInt("version"), r.getString("content"), (Integer) r.getObject("page"),
                r.getInt("paragraph"), r.getString("heading"), r.getInt("start_offset"), r.getInt("end_offset"), r.getDouble("score")), args.toArray());
        return hits.stream().filter(hit -> query.minimumScore() == null || hit.score() >= query.minimumScore()).toList();
    }
}
