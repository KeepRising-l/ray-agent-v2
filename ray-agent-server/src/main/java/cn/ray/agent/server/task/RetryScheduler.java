package cn.ray.agent.server.task;

import cn.ray.agent.server.knowledge.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 仅对可识别的临时Embedding失败退避重试；中断、认证/格式错误和聊天均不整轮自动重放。 */
@Component
public class RetryScheduler {
    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final int maxRetries;
    private final long backoffMs;

    public RetryScheduler(JdbcTemplate jdbc,DocumentRepository documents,
                           @Value("${ray.document.max-auto-retries:2}") int maxRetries,
                           @Value("${ray.document.retry-backoff-ms:1000}") long backoffMs) {
        if(maxRetries<0 || maxRetries>5 || backoffMs<100 || backoffMs>60000){throw new IllegalArgumentException("invalid document retry policy");}
        this.jdbc=jdbc;this.documents=documents;this.maxRetries=maxRetries;this.backoffMs=backoffMs;
    }

    public void tick() {
        var rows=jdbc.queryForList("""
                SELECT v.id,v.document_id,d.owner_id,d.kb_id,t.retry_count,t.next_attempt_at
                FROM ra_document_version v JOIN ra_document d ON d.id=v.document_id
                JOIN ra_document_retry t ON t.version_id=v.id
                JOIN ra_ingestion_attempt a ON a.version_id=v.id AND a.attempt=(SELECT MAX(attempt) FROM ra_ingestion_attempt WHERE version_id=v.id)
                WHERE v.status='FAILED' AND d.deleted_at IS NULL AND d.latest_version=v.version
                AND a.retryable=TRUE AND t.retry_count<? ORDER BY v.created_at,v.id LIMIT 8
                """,maxRetries);
        long now=System.currentTimeMillis();
        for(var row:rows) {
            String id=row.get("id").toString();
            if(row.get("next_attempt_at")==null) {
                long delay=Math.min(60000,backoffMs*(1L<<((Number)row.get("retry_count")).intValue()));
                jdbc.update("UPDATE ra_document_retry SET next_attempt_at=? WHERE version_id=? AND next_attempt_at IS NULL",now+delay,id);
            } else if(((Number)row.get("next_attempt_at")).longValue()<=now) {
                try {documents.retryAutomatic(row.get("owner_id").toString(),row.get("kb_id").toString(),row.get("document_id").toString(),id,maxRetries);}
                catch(ResponseStatusException ignored) { /* 被删除、手动重试或新版本取代，下一轮不会重放它。 */ }
            }
        }
    }
}
