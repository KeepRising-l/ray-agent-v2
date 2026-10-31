package cn.ray.agent.server.conversation;

import cn.ray.agent.runtime.context.SummaryRange;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 原历史不删，只原子替换当前摘要投影；range指纹失配时不复用，取消后不发布摘要。 */
@Repository
public class CompactionRecorder {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SessionRebuilder rebuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    public CompactionRecorder(JdbcTemplate jdbc, PlatformTransactionManager manager, SessionRebuilder rebuilder) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.rebuilder = rebuilder;
    }

    public Saved load(String session) {
        return jdbc.query("SELECT * FROM ra_session_summary WHERE session_id=?", (r,n) -> {
            try {
                var ids = Arrays.asList(mapper.readValue(r.getString("covered_run_ids"), String[].class));
                return new Saved(r.getInt("revision"), new SummaryRange(ids, r.getString("source_hash")), r.getString("summary"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("invalid stored summary range");
            }
        }, session).stream().findFirst().orElse(null);
    }

    public boolean publish(String owner, String session, String run, int expectedRevision, SummaryRange range, String summary) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            if (jdbc.queryForList("SELECT id FROM ra_run WHERE id=? AND owner_id=? AND session_id=? AND status='RUNNING' FOR UPDATE", run, owner, session).isEmpty()) {
                return false;
            }
            jdbc.queryForList("SELECT id FROM ra_session WHERE id=? AND owner_id=? FOR UPDATE", session, owner);
            Saved old = load(session);
            if ((old == null ? 0 : old.revision()) != expectedRevision) {
                return false;
            }
            var turns = rebuilder.turns(owner, session);
            int count = range.runIds().size();
            if (turns.size() < count || !turns.subList(0,count).stream().map(SessionRebuilder.Turn::runId).toList().equals(range.runIds())
                    || !SessionRebuilder.hash(turns.subList(0,count)).equals(range.sourceHash())) {
                return false;
            }
            try {
                jdbc.update("INSERT INTO ra_session_summary VALUES (?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET revision=EXCLUDED.revision,covered_run_ids=EXCLUDED.covered_run_ids,source_hash=EXCLUDED.source_hash,summary=EXCLUDED.summary,created_at=EXCLUDED.created_at",
                        session, expectedRevision+1, mapper.writeValueAsString(range.runIds()), range.sourceHash(), summary, System.currentTimeMillis());
                return true;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    public record Saved(int revision, SummaryRange range, String summary) { }
}
