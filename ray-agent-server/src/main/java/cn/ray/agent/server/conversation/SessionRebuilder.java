package cn.ray.agent.server.conversation;

import cn.ray.agent.runtime.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** 从原始成功轮次重建模型历史；用户补充参与该轮投影，等待/取消/失败轮次不混入后续上下文。 */
@Component
public class SessionRebuilder {
    private final JdbcTemplate jdbc;
    private final ConversationRepository store;

    public SessionRebuilder(JdbcTemplate jdbc, ConversationRepository store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public List<Turn> turns(String owner, String session) {
        store.session(owner, session);
        var result = new ArrayList<Turn>();
        var runs = jdbc.queryForList("SELECT id FROM ra_run WHERE session_id=? AND owner_id=? AND status='SUCCEEDED' ORDER BY created_at,id", session, owner);
        for (var row : runs) {
            String run = row.get("id").toString();
            var messages = jdbc.query("SELECT role,content FROM ra_message WHERE run_id=? AND complete=TRUE ORDER BY CASE role WHEN 'USER' THEN 0 ELSE 1 END",
                    (r, n) -> new Message(Message.Role.valueOf(r.getString(1)), r.getString(2)), run);
            if (messages.size() != 2 || messages.get(0).role() != Message.Role.USER || messages.get(1).role() != Message.Role.ASSISTANT) {
                continue;
            }
            var answers = jdbc.queryForList("SELECT answer FROM ra_user_question WHERE run_id=? AND owner_id=? AND status='ANSWERED' ORDER BY created_at,id", String.class, run, owner);
            if (!answers.isEmpty()) {
                messages.set(0, new Message(Message.Role.USER, messages.get(0).content() + "\n用户补充（原顺序）：\n" + String.join("\n", answers)));
            }
            result.add(new Turn(run, messages));
        }
        return List.copyOf(result);
    }

    public static String hash(List<Turn> turns) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsString(turns).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash session source", e);
        }
    }

    public record Turn(String runId, List<Message> messages) {
        public Turn { messages = List.copyOf(messages); }
    }
}
