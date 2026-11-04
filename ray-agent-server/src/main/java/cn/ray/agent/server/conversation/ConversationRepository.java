package cn.ray.agent.server.conversation;

import cn.ray.agent.runtime.model.Message;
import cn.ray.agent.runtime.model.TokenUsage;
import org.springframework.beans.factory.annotation.Value;
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

    /** 每会话活动运行上限（含排队）；等待用户补充的运行也计入。 */
    @Value("${ray.run.max-queued-per-session:2}")
    private int maxActivePerSession = 2;

    public ConversationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public Session create(String owner, String title) {
        return create(owner, title, "CHAT", List.of());
    }

    /** 会话创建时固定模式和资料范围，切换模式必须新建会话，避免历史混入另一模式。 */
    public Session create(String owner, String title, String mode, List<String> knowledgeBases) {
        cn.ray.agent.server.chat.ChatMode.parse(mode);
        if (knowledgeBases == null || knowledgeBases.size() > 5
                || (mode.equals("CHAT") && !knowledgeBases.isEmpty()) || (mode.equals("KNOWLEDGE") && knowledgeBases.isEmpty())
                || knowledgeBases.stream().anyMatch(id -> id == null || !id.matches("[a-f0-9-]{36}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "KNOWLEDGE需要1到5个知识库，AUTO可选0到5个，CHAT不接收知识库");
        }
        return transactions.execute(tx -> {
            for (String kb : knowledgeBases) {
                if (jdbc.queryForObject("SELECT COUNT(*) FROM ra_knowledge_base WHERE id=? AND owner_id=?", Integer.class, kb, owner) != 1) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在");
                }
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ra_session VALUES (?,?,?,?,?)", id, owner, title, mode, System.currentTimeMillis());
            for (String kb : new java.util.TreeSet<>(knowledgeBases)) {
                jdbc.update("INSERT INTO ra_session_knowledge_base VALUES (?,?)", id, kb);
            }
            return session(owner, id);
        });
    }

    public List<Session> sessions(String owner) {
        return jdbc.query("SELECT * FROM ra_session WHERE owner_id=? ORDER BY created_at DESC, id", (r, n) ->
                new Session(r.getString("id"), r.getString("title"), r.getString("mode"), r.getLong("created_at"), knowledgeBases(r.getString("id"), r.getString("mode"))), owner);
    }

    public Session session(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM ra_session WHERE id=? AND owner_id=?", (r, n) ->
                new Session(r.getString("id"), r.getString("title"), r.getString("mode"), r.getLong("created_at"), knowledgeBases(r.getString("id"), r.getString("mode"))), id, owner);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.get(0);
    }

    public List<ChatMessage> messages(String owner, String sessionId) {
        session(owner, sessionId);
        return jdbc.query("SELECT m.* FROM ra_message m JOIN ra_run r ON r.id=m.run_id WHERE m.session_id=? ORDER BY r.queue_seq, CASE m.role WHEN 'USER' THEN 0 ELSE 1 END", (r, n) ->
                new ChatMessage(r.getString("id"), r.getString("run_id"), r.getString("role"), r.getString("content"), r.getBoolean("complete")), sessionId);
    }

    /** 只把成功的完整轮次投影给模型，失败或取消的部分回答仍保存在历史中。 */
    public List<Message> history(String owner, String sessionId) {
        session(owner, sessionId);
        return jdbc.query("SELECT m.* FROM ra_message m JOIN ra_run r ON r.id=m.run_id WHERE m.session_id=? AND r.status='SUCCEEDED' ORDER BY r.queue_seq, CASE m.role WHEN 'USER' THEN 0 ELSE 1 END", (r, n) ->
                new Message(Message.Role.valueOf(r.getString("role")), r.getString("content")), sessionId);
    }

    /** 锁住会话行后检查重复请求和活动运行，避免同一会话产生交错写入。 */
    public Run begin(String owner, String sessionId, String requestId, String channel, String model, String input) {
        return begin(owner,sessionId,requestId,channel,model,input,"RUNNING");
    }

    public Run beginQueued(String owner,String sessionId,String requestId,String channel,String model,String input) {
        return begin(owner,sessionId,requestId,channel,model,input,"QUEUED");
    }

    private Run begin(String owner,String sessionId,String requestId,String channel,String model,String input,String initialState) {
        return transactions.execute(status -> {
            new cn.ray.agent.server.run.admission.SessionWriteLease().acquire(jdbc,owner,sessionId);
            new cn.ray.agent.server.run.admission.RequestIdempotencyService(jdbc,this).assertNew(owner,sessionId,requestId,input,channel);
            // 等待用户补充时，下一轮上下文取决于这次回答；不开新运行，也不放行后续消息。
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ra_run WHERE session_id=? AND status='WAITING_USER'", Integer.class, sessionId) > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "等待你补充信息，请先回答或取消");
            }
            int active = jdbc.queryForObject("SELECT COUNT(*) FROM ra_run WHERE session_id=? AND status IN ('CREATED','QUEUED','RUNNING','WAITING_USER')", Integer.class, sessionId);
            if (active >= maxActivePerSession) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "该会话排队已满，请等待或取消");
            }
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            // queue_seq 由数据库默认值生成，保证同一毫秒内也按准入顺序排列。
            jdbc.update("INSERT INTO ra_run(id,session_id,owner_id,client_request_id,channel_id,configured_model,status,created_at) VALUES (?,?,?,?,?,?,?,?)", id, sessionId, owner, requestId, channel, model, initialState, now);
            jdbc.update("INSERT INTO ra_message VALUES (?,?,?,'USER',?,TRUE,?)", UUID.randomUUID().toString(), sessionId, id, input, now);
            if(initialState.equals("QUEUED")) {
                cn.ray.agent.server.run.dispatch.TaskStateRepository.enqueue(jdbc,owner,id,"NEW");
            }
            event(id, initialState.equals("QUEUED") ? "QUEUED" : "STARTED", "channel=" + channel);
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

    /** 固定RAG的多次生成按各次实际组装的消息累计估算；与供应商usage分别展示。 */
    public void updateInputEstimate(String owner, String id, int estimated) {
        jdbc.update("UPDATE ra_run SET estimated_input=? WHERE id=? AND owner_id=? AND status='RUNNING'", estimated, id, owner);
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
            int changed = jdbc.update("UPDATE ra_run SET status=?,ended_at=?,input_tokens=?,output_tokens=?,error_message=? WHERE id=? AND status IN ('CREATED','QUEUED','RUNNING','WAITING_USER')", state, System.currentTimeMillis(), usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(), error, id);
            if (changed == 0) {
                return false;
            }
            if (output != null && !output.isBlank()) {
                saveOutput(current.sessionId(), id, output, "SUCCEEDED".equals(state));
            }
            event(id, state, error != null ? error : (reason == null ? "" : "reason=" + reason));
            return true;
        }));
    }

    /** 暂停和最终完成更新同一条助手消息，避免恢复后出现重复的部分回答。 */
    public void saveOutput(String session, String run, String content, boolean complete) {
        if (content == null || content.isBlank()) {
            return;
        }
        jdbc.update("INSERT INTO ra_message VALUES (?,?,?,'ASSISTANT',?,?,?) ON CONFLICT(run_id) WHERE role='ASSISTANT' DO UPDATE SET content=EXCLUDED.content, complete=EXCLUDED.complete",
                UUID.randomUUID().toString(), session, run, content, complete, System.currentTimeMillis());
    }

    public Run run(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM ra_run WHERE id=? AND owner_id=?", (r, n) -> new Run(
                r.getString("id"), r.getString("session_id"), r.getString("client_request_id"), r.getString("channel_id"),
                r.getString("configured_model"), r.getString("status"), r.getLong("created_at"), r.getLong("queue_seq"), (Long) r.getObject("ended_at"),
                (Integer) r.getObject("estimated_input"), (Integer) r.getObject("input_tokens"), (Integer) r.getObject("output_tokens"), r.getString("error_message")), id, owner);
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.get(0);
    }

    public List<Run> runs(String owner, String sessionId) {
        session(owner, sessionId);
        var ids = jdbc.queryForList("SELECT id FROM ra_run WHERE session_id=? ORDER BY queue_seq DESC", String.class, sessionId);
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
            if (!java.util.Set.of("RUNNING","QUEUED").contains(run(owner, id).status())) {
                return null;
            }
            return event(id, type, detail);
        });
    }

    /** 进程重启后不自动重放模型请求；之前的活动运行标记为中断。 */
    public void recover() {
        var rows = jdbc.query("SELECT id,owner_id FROM ra_run WHERE status IN ('CREATED','QUEUED','RUNNING')", (r, n) -> new String[]{r.getString("id"), r.getString("owner_id")});
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

    private List<String> knowledgeBases(String id, String mode) {
        if (mode.equals("CHAT")) {
            return List.of();
        }
        return jdbc.queryForList("SELECT kb_id FROM ra_session_knowledge_base WHERE session_id=? ORDER BY kb_id", String.class, id);
    }

    public record Session(String id, String title, String mode, long createdAt, List<String> knowledgeBaseIds) { }
    public record ChatMessage(String id, String runId, String role, String content, boolean complete) { }
    public record Run(String id, String sessionId, String clientRequestId, String channelId, String configuredModel,
                      String status, long createdAt, long queueSeq, Long endedAt, Integer estimatedInput, Integer inputTokens,
                      Integer outputTokens, String errorMessage) { }
    public record Event(int seq, String type, String detail, long createdAt) { }
}
