package cn.ray.agent.server.task;

import org.springframework.jdbc.core.JdbcTemplate;

/** 入库按不可变versionId记录每次尝试；完整分块/向量发布仍沿用原事务，不把局部结果当READY。 */
public final class TaskCheckpointStore {
    public static void begin(JdbcTemplate jdbc,String version) {
        int attempt=jdbc.queryForObject("SELECT COALESCE(MAX(attempt),0)+1 FROM ra_ingestion_attempt WHERE version_id=?",Integer.class,version);
        jdbc.update("INSERT INTO ra_ingestion_attempt VALUES (?,?,'PARSING','RUNNING',NULL,FALSE,?,NULL)",version,attempt,System.currentTimeMillis());
        jdbc.update("INSERT INTO ra_document_retry(version_id) VALUES (?) ON CONFLICT DO NOTHING",version);
    }

    public static void stage(JdbcTemplate jdbc,String version,String stage) {
        jdbc.update("UPDATE ra_ingestion_attempt SET stage=? WHERE version_id=? AND attempt=(SELECT MAX(attempt) FROM ra_ingestion_attempt WHERE version_id=?) AND status='RUNNING'",stage,version,version);
    }

    public static void end(JdbcTemplate jdbc,String version,String stage,String state,String code,boolean retryable) {
        jdbc.update("UPDATE ra_ingestion_attempt SET stage=?,status=?,error_code=?,retryable=?,ended_at=? WHERE version_id=? AND attempt=(SELECT MAX(attempt) FROM ra_ingestion_attempt WHERE version_id=?) AND status='RUNNING'",
                stage,state,code,retryable,System.currentTimeMillis(),version,version);
        jdbc.update("UPDATE ra_document_retry SET next_attempt_at=NULL,reason=? WHERE version_id=?",code,version);
    }

    public static boolean transientEmbedding(String code) {
        return code.equals("REQUEST_FAILED") || code.equals("HTTP_429") || code.matches("HTTP_5\\d\\d");
    }
}
