package cn.ray.agent.server.run;

import cn.ray.agent.server.conversation.ConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 请求编号属于固定会话；重复只指向原run，不重新订阅一次性流或重复执行工具。 */
@Service
public class RequestIdempotencyService {
    private final JdbcTemplate jdbc;
    private final ConversationRepository store;
    public RequestIdempotencyService(JdbcTemplate jdbc,ConversationRepository store){this.jdbc=jdbc;this.store=store;}

    public void assertNew(String owner,String session,String request,String input,String channel) {
        store.session(owner,session);
        var rows=jdbc.queryForList("SELECT r.id,r.status,r.channel_id,m.content FROM ra_run r JOIN ra_message m ON m.run_id=r.id AND m.role='USER' WHERE r.session_id=? AND r.owner_id=? AND r.client_request_id=?",session,owner,request);
        if(!rows.isEmpty()) {
            var row=rows.get(0);
            throw new Duplicate(row.get("id").toString(),row.get("status").toString(),row.get("content").equals(input)&&row.get("channel_id").equals(channel));
        }
    }

    public ConversationRepository.Run find(String owner,String session,String request) {
        store.session(owner,session);
        var ids=jdbc.queryForList("SELECT id FROM ra_run WHERE session_id=? AND owner_id=? AND client_request_id=?",String.class,session,owner,request);
        if(ids.isEmpty()){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"请求尚未创建运行");}
        return store.run(owner,ids.get(0));
    }

    public static final class Duplicate extends ResponseStatusException {
        public final String runId;
        public final String state;
        public final boolean sameInput;
        public Duplicate(String runId,String state,boolean sameInput) {
            super(HttpStatus.CONFLICT,sameInput?"请求已接收，请查询原运行":"同一请求编号不能用于不同消息或渠道");
            this.runId=runId;this.state=state;this.sameInput=sameInput;
        }
    }
}
