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
            r.getString("failure_stage"), r.getString("error_message"), r.getLong("created_at"), (Long) r.getObject("ended_at"), r.getString("target_profile"));

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
        return jdbc.query("SELECT d.*,v.id AS version_id,v.status,v.error_message,v.chunk_count FROM ra_document d LEFT JOIN ra_document_version v ON v.document_id=d.id AND v.version=d.latest_version WHERE d.kb_id=? AND d.owner_id=? AND d.deleted_at IS NULL ORDER BY d.created_at DESC,d.id LIMIT 100", (r, n) ->
                new Document(r.getString("id"), r.getString("name"), r.getInt("latest_version"), r.getString("active_version_id"),
                        r.getString("version_id"), r.getString("status"), r.getString("error_message"), r.getInt("chunk_count")), kbId, owner);
    }

    private void requireDocument(String owner, String kb, String doc) {
        base(owner, kb);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM ra_document WHERE id=? AND kb_id=? AND owner_id=? AND deleted_at IS NULL", Integer.class, doc, kb, owner) != 1) {
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
                var ids = jdbc.queryForList("SELECT id FROM ra_document WHERE kb_id=? AND name=? AND owner_id=? AND deleted_at IS NULL", String.class, kb, name, owner);
                if (ids.isEmpty()) {
                    documentId = UUID.randomUUID().toString();
                    jdbc.update("INSERT INTO ra_document(id,kb_id,owner_id,name,created_at) VALUES (?,?,?,?,?)", documentId, kb, owner, name, System.currentTimeMillis());
                } else {
                    documentId = ids.get(0);
                }
            }
            requireDocument(owner, kb, documentId);
            jdbc.queryForList("SELECT id FROM ra_document WHERE id=? FOR UPDATE", documentId);
            // 删除可能在等待行锁期间完成，取得锁后必须再次确认文档仍有效。
            requireDocument(owner, kb, documentId);
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
    public Work claim() {return claim(null);}

    public Work claim(String targetProfile) {
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT v.* FROM ra_document_version v JOIN ra_document d ON d.id=v.document_id WHERE v.status='UPLOADED' AND d.deleted_at IS NULL AND d.latest_version=v.version ORDER BY v.created_at,v.id LIMIT 1 FOR UPDATE OF v SKIP LOCKED", versionMapper);
            if (rows.isEmpty()) {
                return null;
            }
            Version version = rows.get(0);
            stage(version.id(), "UPLOADED", "PARSING");
            jdbc.update("UPDATE ra_document_version SET target_profile=COALESCE(target_profile,?) WHERE id=?",targetProfile,version.id());
            cn.ray.agent.server.task.TaskCheckpointStore.begin(jdbc,version.id());
            version=jdbc.queryForObject("SELECT * FROM ra_document_version WHERE id=?",versionMapper,version.id());
            var owner = jdbc.queryForMap("SELECT owner_id,kb_id,name FROM ra_document WHERE id=?", version.documentId());
            String key = jdbc.queryForObject("SELECT storage_key FROM ra_document_version WHERE id=?", String.class, version.id());
            return new Work(version, (String) owner.get("owner_id"), (String) owner.get("kb_id"), (String) owner.get("name"), key);
        });
    }

    public void stage(String id, String expected, String next) {
        if (jdbc.update("UPDATE ra_document_version SET status=? WHERE id=? AND status=?", next, id, expected) != 1) {
            throw new java.util.concurrent.CancellationException("document state changed");
        }
        cn.ray.agent.server.task.TaskCheckpointStore.stage(jdbc,id,next);
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
            var doc = jdbc.queryForMap("SELECT latest_version,deleted_at FROM ra_document WHERE id=? FOR UPDATE", work.version().documentId());
            if (doc.get("deleted_at") != null || ((Number) doc.get("latest_version")).intValue() != work.version().version()) {
                throw new IllegalStateException("stale document version");
            }
            stage(work.version().id(), "EMBEDDING", "READY");
            vectors.write(profile, result, embeddings);
            jdbc.update("UPDATE ra_document_version SET embedding_profile=?,ended_at=?,failure_stage=NULL,error_message=NULL WHERE id=?", profile.id(), System.currentTimeMillis(), work.version().id());
            jdbc.update("UPDATE ra_document SET active_version_id=? WHERE id=?", work.version().id(), work.version().documentId());
            cn.ray.agent.server.task.TaskCheckpointStore.end(jdbc,work.version().id(),"PUBLISHED","SUCCEEDED",null,false);
        });
    }

    public void fail(String id, String stage, String message, boolean interrupted) {
        fail(id,stage,message,interrupted,"MANUAL_REVIEW",false);
    }

    public void fail(String id,String stage,String message,boolean interrupted,String code,boolean retryable) {
        tx.executeWithoutResult(status -> {
            int changed=jdbc.update("UPDATE ra_document_version SET status=?,failure_stage=?,error_message=?,ended_at=? WHERE id=? AND status IN ('PARSING','CHUNKING','EMBEDDING')",
                    interrupted?"INTERRUPTED":"FAILED",stage,message,System.currentTimeMillis(),id);
            if(changed>0) {cn.ray.agent.server.task.TaskCheckpointStore.end(jdbc,id,stage,interrupted?"INTERRUPTED":"FAILED",code,retryable);}
        });
    }

    public Version retry(String owner, String kb, String doc, String id) {
        return retry(owner,kb,doc,id,false,0);
    }

    public Version retryAutomatic(String owner,String kb,String doc,String id,int maxRetries) {
        return retry(owner,kb,doc,id,true,maxRetries);
    }

    private Version retry(String owner,String kb,String doc,String id,boolean automatic,int maxRetries) {
        return tx.execute(status -> {
            Version value = version(owner, kb, doc, id);
            jdbc.queryForList("SELECT id FROM ra_document WHERE id=? FOR UPDATE", doc);
            int latest = jdbc.queryForObject("SELECT latest_version FROM ra_document WHERE id=?", Integer.class, doc);
            requireDocument(owner,kb,doc);
            if(automatic && jdbc.queryForObject("SELECT COUNT(*) FROM ra_document_retry WHERE version_id=? AND retry_count<? AND next_attempt_at<=?",Integer.class,id,maxRetries,System.currentTimeMillis())!=1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"重试计划已失效");
            }
            if (latest != value.version() || jdbc.update("UPDATE ra_document_version SET status='UPLOADED',error_message=NULL,failure_stage=NULL,ended_at=NULL WHERE id=? AND status IN ('FAILED','INTERRUPTED')", id) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试最新的失败或中断版本");
            }
            jdbc.update("UPDATE ra_document_retry SET retry_count="+(automatic?"retry_count+1":"0")+",next_attempt_at=NULL,reason=? WHERE version_id=?",automatic?"AUTO_RETRY":"MANUAL_RETRY",id);
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
        jdbc.update("UPDATE ra_ingestion_attempt SET status='INTERRUPTED',retryable=FALSE,error_code='PROCESS_RESTART',ended_at=? WHERE status='RUNNING'",System.currentTimeMillis());
        jdbc.update("UPDATE ra_document_version SET failure_stage=status,status='INTERRUPTED',error_message='服务重启，文档处理已中断，请手动重试',ended_at=? WHERE status IN ('PARSING','CHUNKING','EMBEDDING')", System.currentTimeMillis());
    }

    /** 删除事务先取消可见性，再删片段/向量，最后登记原文件清理；旧处理任务不能重新发布。 */
    public void delete(String owner, String kb, String doc) {
        tx.executeWithoutResult(status -> {
            base(owner, kb);
            var rows = jdbc.queryForList("SELECT id FROM ra_document WHERE id=? AND kb_id=? AND owner_id=? FOR UPDATE", doc, kb, owner);
            if (rows.isEmpty()) {
                throw missing();
            }
            jdbc.update("UPDATE ra_document SET deleted_at=COALESCE(deleted_at,?),active_version_id=NULL WHERE id=?", System.currentTimeMillis(), doc);
            jdbc.update("UPDATE ra_ingestion_attempt SET status='CANCELLED',ended_at=? WHERE version_id IN (SELECT id FROM ra_document_version WHERE document_id=?) AND status='RUNNING'",System.currentTimeMillis(),doc);
            jdbc.update("UPDATE ra_document_retry SET next_attempt_at=NULL,reason='DELETED' WHERE version_id IN (SELECT id FROM ra_document_version WHERE document_id=?)",doc);
            jdbc.update("UPDATE ra_document_version SET status='DELETED',ended_at=COALESCE(ended_at,?) WHERE document_id=?", System.currentTimeMillis(), doc);
            jdbc.update("DELETE FROM ra_document_chunk WHERE version_id IN (SELECT id FROM ra_document_version WHERE document_id=?)", doc);
            jdbc.update("INSERT INTO ra_file_cleanup(storage_key) SELECT DISTINCT storage_key FROM ra_document_version WHERE document_id=? ON CONFLICT DO NOTHING", doc);
        });
    }

    public List<String> cleanupKeys() {
        return jdbc.queryForList("SELECT storage_key FROM ra_file_cleanup WHERE next_attempt_at<=? ORDER BY storage_key LIMIT 5", String.class, System.currentTimeMillis());
    }

    public void cleanupFinished(String key, boolean success) {
        if (success) {
            jdbc.update("DELETE FROM ra_file_cleanup WHERE storage_key=?", key);
        } else {
            jdbc.update("UPDATE ra_file_cleanup SET attempts=attempts+1,next_attempt_at=? WHERE storage_key=?", System.currentTimeMillis() + 5000, key);
        }
    }

    /** 重建总是生成新版本；沿用原文件，在新索引完整发布之前保留旧有效版本。 */
    public Version reindex(String owner, String kb, String doc, String parser, String policy, String targetProfile) {
        return tx.execute(status -> {
            requireDocument(owner, kb, doc);
            jdbc.queryForList("SELECT id FROM ra_document WHERE id=? FOR UPDATE", doc);
            requireDocument(owner, kb, doc);
            Version latest = versions(owner, kb, doc).get(0);
            if (Set.of("UPLOADED", "PARSING", "CHUNKING", "EMBEDDING").contains(latest.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "文档仍在处理，不能同时重建索引");
            }
            String key = jdbc.queryForObject("SELECT storage_key FROM ra_document_version WHERE id=?", String.class, latest.id());
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ra_document_version(id,document_id,version,sha256,file_size,file_type,storage_key,status,parser_version,chunk_policy,target_profile,created_at) VALUES (?,?,?,?,?,?,?,'UPLOADED',?,?,?,?)",
                    id, doc, latest.version() + 1, latest.sha256(), latest.fileSize(), latest.fileType(), key, parser, policy, targetProfile, System.currentTimeMillis());
            jdbc.update("UPDATE ra_document SET latest_version=? WHERE id=?", latest.version() + 1, doc);
            return version(owner, kb, doc, id);
        });
    }

    /** 历史引用只返回当前仍有效的原文；失效引用保留定位元数据，不返还被删除的内容。 */
    public String currentText(String owner, String kb, String doc, String version, String chunk) {
        var rows = jdbc.queryForList("SELECT c.content FROM ra_document_chunk c JOIN ra_document_version v ON v.id=c.version_id JOIN ra_document d ON d.id=v.document_id JOIN ra_knowledge_base k ON k.id=d.kb_id WHERE c.id=? AND v.id=? AND d.id=? AND k.id=? AND d.owner_id=? AND k.owner_id=? AND d.deleted_at IS NULL AND d.active_version_id=v.id AND v.status='READY'", String.class, chunk, version, doc, kb, owner, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库、文档或版本不存在");
    }

    public record KnowledgeBase(String id, String name, long createdAt) { }
    public record Document(String id, String name, int latestVersion, String activeVersionId, String versionId,
                           String status, String errorMessage, int chunkCount) { }
    public record Version(String id, String documentId, int version, String sha256, int fileSize, String fileType,
                          String status, String parserVersion, String chunkPolicy, String embeddingProfile,
                          int chunkCount, String failureStage, String errorMessage, long createdAt, Long endedAt, String targetProfile) { }
    public record Accepted(Version version, boolean newFile) { }
    public record Work(Version version, String ownerId, String kbId, String name, String storageKey) { }
}
