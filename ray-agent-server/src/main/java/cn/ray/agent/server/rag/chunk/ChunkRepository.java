package cn.ray.agent.server.rag.chunk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** 与文档版本事务共用连接；重复处理替换相同版本的片段，不累加重复记录。 */
@Repository
public class ChunkRepository {
    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(String versionId, List<DocumentChunk> chunks) {
        jdbc.update("DELETE FROM ra_document_chunk WHERE version_id=?", versionId);
        for (var chunk : chunks) {
            if (!chunk.versionId().equals(versionId)) {
                throw new IllegalArgumentException("chunk version mismatch");
            }
            jdbc.update("INSERT INTO ra_document_chunk VALUES (?,?,?,?,?,?,?,?,?)", chunk.id(), versionId,
                    chunk.ordinal(), chunk.content(), chunk.page(), chunk.paragraph(), chunk.heading(), chunk.startOffset(), chunk.endOffset());
        }
    }

    public List<DocumentChunk> list(String versionId) {
        return jdbc.query("SELECT * FROM ra_document_chunk WHERE version_id=? ORDER BY ordinal", (r, n) ->
                new DocumentChunk(r.getString("id"), versionId, r.getInt("ordinal"), r.getString("content"),
                        (Integer) r.getObject("page"), r.getInt("paragraph"), r.getString("heading"), r.getInt("start_offset"), r.getInt("end_offset")), versionId);
    }
}
