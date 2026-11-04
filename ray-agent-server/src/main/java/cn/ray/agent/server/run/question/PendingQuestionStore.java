package cn.ray.agent.server.run.question;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.model.TokenUsage;
import cn.ray.agent.server.conversation.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** 等待不占工作线程。行锁串行处理回答、取消、到期；同一问题只能消费一次。 */
@Repository
public class PendingQuestionStore {
    private final JdbcTemplate jdbc;
    private final ConversationRepository store;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long ttlMs;

    public PendingQuestionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ConversationRepository store,
                                @Value("${ray.question.ttl-ms:86400000}") long ttlMs) {
        if (ttlMs < 100 || ttlMs > 604800000L) { throw new IllegalArgumentException("invalid question TTL"); }
        this.jdbc = jdbc;
        this.store = store;
        this.transactions = new TransactionTemplate(manager);
        this.ttlMs = ttlMs;
    }

    public Question suspend(String owner, String run, Snapshot snapshot) {
        return transactions.execute(tx -> {
            var state = lock(owner, run);
            if (!state.status().equals("RUNNING")) { throw conflict(); }
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            String json = json(snapshot);
            if (json.length() > 250000) { throw new IllegalArgumentException("checkpoint exceeds limit"); }
            jdbc.update("INSERT INTO ra_user_question(id,run_id,owner_id,status,question,checkpoint,created_at,expires_at) VALUES (?,?,?,'WAITING_USER',?,?,?,?)",
                    id, run, owner, snapshot.continuation().question(), json, now, now+ttlMs);
            store.saveOutput(state.sessionId(), run, snapshot.output(), false);
            jdbc.update("UPDATE ra_run SET status='WAITING_USER',input_tokens=?,output_tokens=? WHERE id=?",
                    snapshot.usage().inputTokens(), snapshot.usage().outputTokens(), run);
            waitingEvent(run, "WAITING_USER", json(Map.of("questionId", id, "expiresAt", now+ttlMs)));
            return question(owner, run);
        });
    }

    /** 返回展示字段，绝不把检查点中的工具正文/权限/请求交给浏览器。 */
    public Question question(String owner, String run) {
        store.run(owner, run);
        return jdbc.query("SELECT * FROM ra_user_question WHERE run_id=? AND owner_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                (r,n) -> new Question(r.getString("id"), run, r.getString("question"), r.getString("status"), r.getLong("expires_at"), r.getString("answer")), run, owner)
                .stream().findFirst().orElse(null);
    }

    public Accepted consume(String owner, String run, String questionId, String answer) {
        return consume(owner,run,questionId,answer,false);
    }

    public Accepted consumeQueued(String owner,String run,String questionId,String answer) {
        return consume(owner,run,questionId,answer,true);
    }

    private Accepted consume(String owner,String run,String questionId,String answer,boolean queued) {
        // 到期扫描由后台调度完成；这里只拒绝已过有效期的回答，避免把已过期检查点重新执行。
        if (answer == null || answer.isBlank() || answer.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "补充回答需要1到2000字符");
        }
        return transactions.execute(tx -> {
            var state = lock(owner, run);
            var rows = jdbc.queryForList("SELECT * FROM ra_user_question WHERE id=? AND run_id=? AND owner_id=? FOR UPDATE", questionId, run, owner);
            if (rows.isEmpty()) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提问不存在"); }
            var row = rows.get(0);
            if (row.get("status").equals("EXPIRED")
                    || ((Number) row.get("expires_at")).longValue() <= System.currentTimeMillis()) {
                throw new ResponseStatusException(HttpStatus.GONE, "提问已过期");
            }
            if (!state.status().equals("WAITING_USER") || !row.get("status").equals("WAITING_USER")) { throw conflict(); }
            Snapshot snapshot;
            try { snapshot = mapper.readValue(row.get("checkpoint").toString(), Snapshot.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("invalid stored checkpoint", e); }
            jdbc.update("UPDATE ra_user_question SET status='ANSWERED',answer=?,answered_at=? WHERE id=?", answer.strip(), System.currentTimeMillis(), questionId);
            jdbc.update("UPDATE ra_run SET status=? WHERE id=?", queued?"QUEUED":"RUNNING",run);
            if(queued) {cn.ray.agent.server.run.dispatch.TaskStateRepository.enqueue(jdbc,owner,run,"RESUME");}
            store.appendEvent(owner, run, "USER_ANSWERED", json(Map.of("questionId", questionId)));
            return new Accepted(snapshot, answer.strip());
        });
    }

    public void cancel(String owner, String run) {
        transactions.executeWithoutResult(tx -> {
            var state = lock(owner, run);
            if (state.status().equals("WAITING_USER")) {
                jdbc.update("UPDATE ra_user_question SET status='CANCELLED' WHERE run_id=? AND status='WAITING_USER'", run);
                store.finish(owner, run, "CANCELLED", null, new TokenUsage(state.inputTokens(), state.outputTokens()), null);
            }
        });
    }

    /** 到期只结束等待，不自动重新执行；返回实际结束的运行，由上层释放会话闸门。 */
    public List<Expired> expire() {
        var expired = jdbc.queryForList("SELECT q.run_id,q.owner_id,r.session_id FROM ra_user_question q JOIN ra_run r ON r.id=q.run_id WHERE q.status='WAITING_USER' AND q.expires_at<=?", System.currentTimeMillis());
        var result = new ArrayList<Expired>();
        for (var row : expired) {
            transactions.executeWithoutResult(tx -> {
                String run = row.get("run_id").toString(), owner = row.get("owner_id").toString();
                String session = row.get("session_id").toString();
                var state = lock(owner, run);
                int changed = jdbc.update("UPDATE ra_user_question SET status='EXPIRED' WHERE run_id=? AND status='WAITING_USER' AND expires_at<=?", run, System.currentTimeMillis());
                // 只有确实把仍处于等待的 run 结束掉，才允许上层释放闸门；已回答并续跑的 run 不能提前放行。
                if (changed > 0 && state.status().equals("WAITING_USER")) {
                    store.finish(owner, run, "TIMED_OUT", null, new TokenUsage(state.inputTokens(), state.outputTokens()), "等待用户回答已过期");
                    result.add(new Expired(owner, session, run));
                }
            });
        }
        return result;
    }

    public record Expired(String ownerId, String sessionId, String runId) { }

    private ConversationRepository.Run lock(String owner, String run) {
        store.run(owner, run);
        jdbc.queryForList("SELECT id FROM ra_run WHERE id=? AND owner_id=? FOR UPDATE", run, owner);
        return store.run(owner, run);
    }

    private void waitingEvent(String run, String type, String detail) {
        jdbc.update("INSERT INTO ra_event VALUES (?,(SELECT COALESCE(MAX(seq),0)+1 FROM ra_event WHERE run_id=?),?,?,?)", run, run, type, detail, System.currentTimeMillis());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "提问已回答、取消或运行不在等待状态");
    }

    public record Question(String id, String runId, String question, String status, long expiresAt, String answer) { }
    public record Snapshot(int version, UserQuestionRequired.Continuation continuation, RunBudget budget,
                           int modelCalls, long activeMs, int estimatedInput, TokenUsage usage, String output) {
        public Snapshot {
            if (version != 1 || modelCalls < 0 || activeMs < 0) { throw new IllegalArgumentException("invalid checkpoint version"); }
        }
    }
    public record Accepted(Snapshot snapshot, String answer) { }
}
