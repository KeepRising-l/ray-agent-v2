package cn.ray.agent.server.knowledge;

import cn.ray.agent.server.rag.chunk.*;
import cn.ray.agent.server.rag.embedding.EmbeddingProfile;
import cn.ray.agent.server.rag.store.PgVectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 元数据和发布事务：所有对外读取从服务端 owner 限定，后台任务靠持久状态接续。 */
@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ChunkRepository chunks;
    private final PgVectorStore vectors;
    private final RowMapper<Version> versionMapper = (r, n) -> new Version(r.getString("id"), r.getString("document_id"),
            r.getInt("version"), r.getString("sha256"), r.getInt("file_size"), r.getString("file_type"), r.getString("status"),
            r.getString("parser_version"), r.getString("chunk_policy"), r.getString("embedding_profile"), r.getInt("chunk_count"),
            r.getString("failure_stage"), r.getString("error_message"), r.getLong("created_at"), (Long) r.getObject("ended_at"));

    public DocumentRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ChunkRepository chunks, PgVectorStore vectors) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.chunks = chunks;
        this.vectors = vectors;
    }

    public KnowledgeBase createBase(String owner, String name) {
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO ra_knowledge_base VALUES (?,?,?,?)", id, owner, name, System.currentTimeMillis());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "知识库名称已存在");
        }
        return base(owner, id);
    }

    public List<KnowledgeBase> bases(String owner) {
        return jdbc.query("SELECT * FROM ra_knowledge_base WHERE owner_id=? ORDER BY created_at DESC,id LIMIT 100", (r, n) ->
                new KnowledgeBase(r.getString("id"), r.getString("name"), r.getLong("created_at")), owner);
    }

    public KnowledgeBase base(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM ra_knowledge_base WHERE id=? AND owner_id=?", (r, n) ->
                new KnowledgeBase(r.getString("id"), r.getString("name"), r.getLong("created_at")), id, owner);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.get(0);
    }

    public List<Document> documents(String owner, String kbId) {
        base(owner, kbId);
        return jdbc.query("SELECT d.*,v.id AS version_id,v.status,v.error_message,v.chunk_count FROM ra_document d LEFT JOIN ra_document_version v ON v.document_id=d.id AND v.version=d.latest_version WHERE d.kb_id=? AND d.owner_id=? ORDER BY d.created_at DESC,d.id LIMIT 100", (r, n) ->
                new Document(r.getString("id"), r.getString("name"), r.getInt("latest_version"), r.getString("active_version_id"),
                        r.getString("version_id"), r.getString("status"), r.getString("error_message"), r.getInt("chunk_count")), kbId, owner);
    }

    private void requireDocument(String owner, String kb, String doc) {
        base(owner, kb);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ra_document WHERE id=? AND kb_id=? AND owner_id=?", Integer.class, doc, kb, owner) != 1) {
            throw missing();
        }
    }

    public List<Version> versions(String owner, String kb, String doc) {
        requireDocument(owner, kb, doc);
        return jdbc.query("SELECT * FROM ra_document_version WHERE document_id=? ORDER BY version DESC", versionMapper, doc);
    }

    public Version version(String owner, String kb, String doc, String version) {
        requireDocument(owner, kb, doc);
        var rows = jdbc.query("SELECT * FROM ra_document_version WHERE id=? AND document_id=?", versionMapper, version, doc);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.get(0);
    }

    /** 锁住知识库后按同名文档创建版本；同一文档处理中拒绝并行更新，重复摘要返回已有版本。 */
    public Accepted accept(String owner, String kb, String doc, String name, String hash, int size, String type,
                           String storageKey, String parserVersion, String chunkPolicy) {
        return tx.execute(status -> {
            base(owner, kb);
            jdbc.queryForList("SELECT id FROM ra_knowledge_base WHERE id=? FOR UPDATE", kb);
            String documentId = doc;
            if (documentId == null) {
                var ids = jdbc.queryForList("SELECT id FROM ra_document WHERE kb_id=? AND name=? AND owner_id=?", String.class, kb, name, owner);
                if (ids.isEmpty()) {
                    documentId = UUID.randomUUID().toString();
                    jdbc.update("INSERT INTO ra_document(id,kb_id,owner_id,name,created_at) VALUES (?,?,?,?,?)", documentId, kb, owner, name, System.currentTimeMillis());
                } else {
                    documentId = ids.get(0);
                }
            }
            requireDocument(owner, kb, documentId);
            jdbc.queryForList("SELECT id FROM ra_document WHERE id=? FOR UPDATE", documentId);
            var previous = jdbc.query("SELECT * FROM ra_document_version WHERE document_id=? ORDER BY version DESC LIMIT 1", versionMapper, documentId);
            if (!previous.isEmpty()) {
                Version latest = previous.get(0);
                if (latest.sha256().equals(hash) && latest.parserVersion().equals(parserVersion) && latest.chunkPolicy().equals(chunkPolicy)) {
                    return new Accepted(latest, false);
                }
                if (Set.of("UPLOADED", "PARSING", "CHUNKING", "EMBEDDING").contains(latest.status())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该文档仍在处理，请等待结束后再上传新版本");
                }
            }
            int number = previous.isEmpty() ? 1 : previous.get(0).version() + 1;
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ra_document_version(id,document_id,version,sha256,file_size,file_type,storage_key,status,parser_version,chunk_policy,created_at) VALUES (?,?,?,?,?,?,?,'UPLOADED',?,?,?)",
                    id, documentId, number, hash, size, type, storageKey, parserVersion, chunkPolicy, System.currentTimeMillis());
            jdbc.update("UPDATE ra_document SET latest_version=? WHERE id=?", number, documentId);
            return new Accepted(version(owner, kb, documentId, id), true);
        });
    }

    /** 单个后台消费者领取一项任务；事务到这里结束，解析和外部 Embedding 不占数据库事务。 */
    public Work claim() {
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT v.* FROM ra_document_version v WHERE status='UPLOADED' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED", versionMapper);
            if (rows.isEmpty()) {
                return null;
            }
            Version version = rows.get(0);
            stage(version.id(), "UPLOADED", "PARSING");
            var owner = jdbc.queryForMap("SELECT owner_id,kb_id,name FROM ra_document WHERE id=?", version.documentId());
            String key = jdbc.queryForObject("SELECT storage_key FROM ra_document_version WHERE id=?", String.class, version.id());
            return new Work(version, (String) owner.get("owner_id"), (String) owner.get("kb_id"), (String) owner.get("name"), key);
        });
    }

    public void stage(String id, String expected, String next) {
        if (jdbc.update("UPDATE ra_document_version SET status=? WHERE id=? AND status=?", next, id, expected) != 1) {
            throw new java.util.concurrent.CancellationException("document state changed");
        }
    }

    public void saveChunks(Work work, List<DocumentChunk> result) {
        tx.executeWithoutResult(status -> {
            stage(work.version().id(), "CHUNKING", "EMBEDDING");
            chunks.replace(work.version().id(), result);
            jdbc.update("UPDATE ra_document_version SET chunk_count=? WHERE id=?", result.size(), work.version().id());
        });
    }

    /** 一次事务写完向量再发布有效版本；失败的新版本不覆盖旧的就绪版本。 */
    public void publish(Work work, EmbeddingProfile profile, List<DocumentChunk> result, List<float[]> embeddings) {
        tx.executeWithoutResult(status -> {
            var doc = jdbc.queryForMap("SELECT latest_version FROM ra_document WHERE id=? FOR UPDATE", work.version().documentId());
            if (((Number) doc.get("latest_version")).intValue() != work.version().version()) {
                throw new IllegalStateException("stale document version");
            }
            stage(work.version().id(), "EMBEDDING", "READY");
            vectors.write(profile, result, embeddings);
            jdbc.update("UPDATE ra_document_version SET embedding_profile=?,ended_at=?,failure_stage=NULL,error_message=NULL WHERE id=?", profile.id(), System.currentTimeMillis(), work.version().id());
            jdbc.update("UPDATE ra_document SET active_version_id=? WHERE id=?", work.version().id(), work.version().documentId());
        });
    }

    public void fail(String id, String stage, String message, boolean interrupted) {
        jdbc.update("UPDATE ra_document_version SET status=?,failure_stage=?,error_message=?,ended_at=? WHERE id=? AND status IN ('PARSING','CHUNKING','EMBEDDING')",
                interrupted ? "INTERRUPTED" : "FAILED", stage, message, System.currentTimeMillis(), id);
    }

    public Version retry(String owner, String kb, String doc, String id) {
        return tx.execute(status -> {
            Version value = version(owner, kb, doc, id);
            jdbc.queryForList("SELECT id FROM ra_document WHERE id=? FOR UPDATE", doc);
            int latest = jdbc.queryForObject("SELECT latest_version FROM ra_document WHERE id=?", Integer.class, doc);
            if (latest != value.version() || jdbc.update("UPDATE ra_document_version SET status='UPLOADED',error_message=NULL,failure_stage=NULL,ended_at=NULL WHERE id=? AND status IN ('FAILED','INTERRUPTED')", id) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试最新的失败或中断版本");
            }
            return version(owner, kb, doc, id);
        });
    }

    public List<DocumentChunk> chunks(String owner, String kb, String doc, String id) {
        version(owner, kb, doc, id);
        return chunks.list(id);
    }

    public int vectorCount(String owner, String kb, String doc, String id) {
        version(owner, kb, doc, id);
        return vectors.count(id);
    }

    public void recover() {
        jdbc.update("UPDATE ra_document_version SET failure_stage=status,status='INTERRUPTED',error_message='服务重启，文档处理已中断，请手动重试',ended_at=? WHERE status IN ('PARSING','CHUNKING','EMBEDDING')", System.currentTimeMillis());
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库、文档或版本不存在");
    }

    public record KnowledgeBase(String id, String name, long createdAt) { }
    public record Document(String id, String name, int latestVersion, String activeVersionId, String versionId,
                           String status, String errorMessage, int chunkCount) { }
    public record Version(String id, String documentId, int version, String sha256, int fileSize, String fileType,
                          String status, String parserVersion, String chunkPolicy, String embeddingProfile,
                          int chunkCount, String failureStage, String errorMessage, long createdAt, Long endedAt) { }
    public record Accepted(Version version, boolean newFile) { }
    public record Work(Version version, String ownerId, String kbId, String name, String storageKey) { }
}
