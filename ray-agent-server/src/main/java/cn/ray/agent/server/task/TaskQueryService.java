package cn.ray.agent.server.task;

import cn.ray.agent.server.conversation.SessionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** owner范围内的统一状态投影；只返回任务元数据，不返回检查点全文、密钥或原始聊天/文档正文。 */
@Service
public class TaskQueryService {
    private final JdbcTemplate jdbc;
    private final SessionRegistry registry;
    public TaskQueryService(JdbcTemplate jdbc,SessionRegistry registry){this.jdbc=jdbc;this.registry=registry;}
    public List<Task> list(String owner) {
        var result=new ArrayList<Task>();
        result.addAll(jdbc.query("""
                SELECT r.*,COALESCE(s.phase,0) AS phase,s.enqueued_at,s.started_at FROM ra_run r
                LEFT JOIN ra_run_schedule s ON s.run_id=r.id WHERE r.owner_id=? ORDER BY r.created_at DESC,r.id LIMIT 100
                """,(r,n)->new Task(r.getString("id"),"RUN",r.getString("status"),r.getInt("phase"),r.getString("status"),null,0,null,
                r.getString("session_id"),null,null,r.getLong("created_at"),registry.attached(owner,r.getString("id")),
                r.getString("status").equals("WAITING_USER")?"ANSWER_PENDING":"NONE",(Long)r.getObject("enqueued_at"),(Long)r.getObject("started_at")),owner));
        result.addAll(jdbc.query("""
                SELECT v.id,v.status,v.created_at,v.version,d.id AS doc,d.kb_id,d.latest_version,
                COALESCE(a.attempt,0) AS attempt,COALESCE(a.stage,v.status) AS stage,a.error_code,
                COALESCE(t.retry_count,0) AS retries,t.next_attempt_at
                FROM ra_document_version v JOIN ra_document d ON d.id=v.document_id
                LEFT JOIN ra_ingestion_attempt a ON a.version_id=v.id AND a.attempt=(SELECT MAX(attempt) FROM ra_ingestion_attempt WHERE version_id=v.id)
                LEFT JOIN ra_document_retry t ON t.version_id=v.id
                WHERE d.owner_id=? AND d.deleted_at IS NULL ORDER BY v.created_at DESC,v.id LIMIT 100
                """,(r,n)->new Task(r.getString("id"),"DOCUMENT",r.getString("status"),r.getInt("attempt"),r.getString("stage"),r.getString("error_code"),
                r.getInt("retries"),(Long)r.getObject("next_attempt_at"),null,r.getString("kb_id"),r.getString("doc"),r.getLong("created_at"),false,
                r.getInt("version")==r.getInt("latest_version")&&Set.of("FAILED","INTERRUPTED").contains(r.getString("status"))?"RETRY_DOCUMENT":"NONE",null,null),owner));
        return result.stream().sorted(Comparator.comparingLong(Task::createdAt).reversed().thenComparing(Task::id)).limit(100).toList();
    }
    public List<Map<String,Object>> attempts(String owner,String version) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ra_document_version v JOIN ra_document d ON d.id=v.document_id WHERE v.id=? AND d.owner_id=? AND d.deleted_at IS NULL",Integer.class,version,owner)!=1) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"任务不存在");
        }
        return jdbc.queryForList("SELECT attempt,stage,status,error_code,retryable,started_at,ended_at FROM ra_ingestion_attempt WHERE version_id=? ORDER BY attempt",version);
    }
    public record Task(String id,String kind,String status,int attempt,String stage,String code,int retries,Long nextAttemptAt,
                       String sessionId,String kbId,String documentId,long createdAt,boolean attached,String recovery,Long enqueuedAt,Long startedAt) { }
}
