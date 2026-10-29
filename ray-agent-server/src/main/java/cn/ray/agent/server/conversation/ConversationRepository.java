package cn.ray.agent.server.conversation;

import cn.ray.agent.runtime.model.Message;
import cn.ray.agent.runtime.model.TokenUsage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** 会话、消息、运行和事件的事务存储；所有查询都校验服务端 owner。 */
@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ConversationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public Session create(String owner, String title) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ra_session VALUES (?, ?, ?, 'CHAT', ?)", id, owner, title, now);
        return session(owner, id);
    }

    public List<Session> sessions(String owner) {
        return jdbc.query("SELECT * FROM ra_session WHERE owner_id=? ORDER BY created_at DESC, id", (r, n) ->
                new Session(r.getString("id"), r.getString("title"), r.getString("mode"), r.getLong("created_at")), owner);
    }

    public Session session(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM ra_session WHERE id=? AND owner_id=?", (r, n) ->
                new Session(r.getString("id"), r.getString("title"), r.getString("mode"), r.getLong("created_at")), id, owner);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.get(0);
    }

    public List<ChatMessage> messages(String owner, String sessionId) {
        session(owner, sessionId);
        return jdbc.query("SELECT m.* FROM ra_message m JOIN ra_run r ON r.id=m.run_id WHERE m.session_id=? ORDER BY r.created_at, r.id, CASE m.role WHEN 'USER' THEN 0 ELSE 1 END", (r, n) ->
                new ChatMessage(r.getString("id"), r.getString("run_id"), r.getString("role"), r.getString("content"), r.getBoolean("complete")), sessionId);
    }

    /** 只把成功的完整轮次投影给模型，失败或取消的部分回答仍保存在历史中。 */
    public List<Message> history(String owner, String sessionId) {
        session(owner, sessionId);
        return jdbc.query("SELECT m.* FROM ra_message m JOIN ra_run r ON r.id=m.run_id WHERE m.session_id=? AND r.status='SUCCEEDED' ORDER BY r.created_at, r.id, CASE m.role WHEN 'USER' THEN 0 ELSE 1 END", (r, n) ->
                new Message(Message.Role.valueOf(r.getString("role")), r.getString("content")), sessionId);
    }

    /** 锁住会话行后检查重复请求和活动运行，避免同一会话产生交错写入。 */
    public Run begin(String owner, String sessionId, String requestId, String channel, String model, String input) {
        return transactions.execute(status -> {
            var owned = jdbc.queryForList("SELECT id FROM ra_session WHERE id=? AND owner_id=? FOR UPDATE", sessionId, owner);
            if (owned.isEmpty()) {
                throw missing();
            }
            if (!jdbc.queryForList("SELECT id FROM ra_run WHERE session_id=? AND client_request_id=?", sessionId, requestId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "重复请求，请查询已有运行");
            }
            if (!jdbc.queryForList("SELECT id FROM ra_run WHERE session_id=? AND status IN ('CREATED','RUNNING')", sessionId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话已有运行，请先等待或取消");
            }
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO ra_run(id,session_id,owner_id,client_request_id,channel_id,configured_model,status,created_at) VALUES (?,?,?,?,?,?,'RUNNING',?)", id, sessionId, owner, requestId, channel, model, now);
            jdbc.update("INSERT INTO ra_message VALUES (?,?,?,'USER',?,TRUE,?)", UUID.randomUUID().toString(), sessionId, id, input, now);
            event(id, "STARTED", "channel=" + channel);
            return run(owner, id);
        });
    }

    public void context(String owner, String id, int estimated, int omitted) {
        transactions.executeWithoutResult(tx -> {
            run(owner, id);
            jdbc.queryForList("SELECT id FROM ra_run WHERE id=? FOR UPDATE", id);
            jdbc.update("UPDATE ra_run SET estimated_input=? WHERE id=?", estimated, id);
            event(id, "CONTEXT_SELECTED", "estimatedInput=" + estimated + ", omittedTurns=" + omitted);
        });
    }

    /** 单一事务写入唯一终态、最终/部分回答与用量；重复完成不会追加第二条回答。 */
    public boolean finish(String owner, String id, String state, String output, TokenUsage usage, String error) {
        return finish(owner, id, state, output, usage, error, null);
    }

    public boolean finish(String owner, String id, String state, String output, TokenUsage usage, String error, String reason) {
        if (!java.util.Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "INTERRUPTED").contains(state)) {
            throw new IllegalArgumentException("invalid terminal state");
        }
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            Run current = run(owner, id);
            jdbc.queryForList("SELECT id FROM ra_run WHERE id=? FOR UPDATE", id);
            int changed = jdbc.update("UPDATE ra_run SET status=?,ended_at=?,input_tokens=?,output_tokens=?,error_message=? WHERE id=? AND status IN ('CREATED','RUNNING')", state, System.currentTimeMillis(), usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(), error, id);
            if (changed == 0) {
                return false;
            }
            if (output != null && !output.isBlank()) {
                jdbc.update("INSERT INTO ra_message VALUES (?,?,?,'ASSISTANT',?,?,?)", UUID.randomUUID().toString(), current.sessionId(), id, output, "SUCCEEDED".equals(state), System.currentTimeMillis());
            }
            event(id, state, error != null ? error : (reason == null ? "" : "reason=" + reason));
            return true;
        }));
    }

    public Run run(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM ra_run WHERE id=? AND owner_id=?", (r, n) -> new Run(
                r.getString("id"), r.getString("session_id"), r.getString("client_request_id"), r.getString("channel_id"),
                r.getString("configured_model"), r.getString("status"), r.getLong("created_at"), (Long) r.getObject("ended_at"),
                (Integer) r.getObject("estimated_input"), (Integer) r.getObject("input_tokens"), (Integer) r.getObject("output_tokens"), r.getString("error_message")), id, owner);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.get(0);
    }

    public List<Run> runs(String owner, String sessionId) {
        session(owner, sessionId);
        var ids = jdbc.queryForList("SELECT id FROM ra_run WHERE session_id=? ORDER BY created_at DESC, id", String.class, sessionId);
        return ids.stream().map(id -> run(owner, id)).toList();
    }

    public List<Event> events(String owner, String id) {
        run(owner, id);
        return jdbc.query("SELECT * FROM ra_event WHERE run_id=? ORDER BY seq", (r, n) -> new Event(r.getInt("seq"), r.getString("type"), r.getString("detail"), r.getLong("created_at")), id);
    }

    /** 与终态写入争用同一行锁，保证工具事件顺序且不在终态之后追加“成功”。 */
    public Event appendEvent(String owner, String id, String type, String detail) {
        if (detail.length() > 16000) {
            throw new IllegalArgumentException("event exceeds limit");
        }
        return transactions.execute(tx -> {
            run(owner, id);
            jdbc.queryForList("SELECT id FROM ra_run WHERE id=? FOR UPDATE", id);
            if (!run(owner, id).status().equals("RUNNING")) {
                return null;
            }
            return event(id, type, detail);
        });
    }

    /** 进程重启后不自动重放模型请求；之前的活动运行标记为中断。 */
    public void recover() {
        var rows = jdbc.query("SELECT id,owner_id FROM ra_run WHERE status IN ('CREATED','RUNNING')", (r, n) -> new String[]{r.getString("id"), r.getString("owner_id")});
        for (var row : rows) {
            finish(row[1], row[0], "INTERRUPTED", "", null, "服务重启，运行已中断");
        }
    }

    private Event event(String id, String type, String detail) {
        Integer sequence = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM ra_event WHERE run_id=?", Integer.class, id);
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ra_event VALUES (?,?,?,?,?)", id, sequence, type, detail, now);
        return new Event(sequence, type, detail, now);
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "会话或运行不存在");
    }

    public record Session(String id, String title, String mode, long createdAt) { }
    public record ChatMessage(String id, String runId, String role, String content, boolean complete) { }
    public record Run(String id, String sessionId, String clientRequestId, String channelId, String configuredModel,
                      String status, long createdAt, Long endedAt, Integer estimatedInput, Integer inputTokens,
                      Integer outputTokens, String errorMessage) { }
    public record Event(int seq, String type, String detail, long createdAt) { }
}
