package cn.ray.agent.server.run.dispatch;

import cn.ray.agent.server.conversation.ConversationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 队列状态与run共用数据库事务；内存队列负责本进程调度，数据库保存可查询的接收与启动记录。 */
@Repository
public class TaskStateRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ConversationRepository store;

    public TaskStateRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ConversationRepository store) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.store=store;
    }

    public static void enqueue(JdbcTemplate jdbc,String owner,String run,String kind) {
        jdbc.update("INSERT INTO ra_run_schedule VALUES (?,?,1,?,?,NULL) ON CONFLICT(run_id) DO UPDATE SET phase=ra_run_schedule.phase+1,kind=EXCLUDED.kind,enqueued_at=EXCLUDED.enqueued_at,started_at=NULL",
                run,owner,kind,System.currentTimeMillis());
    }

    public boolean start(String owner,String run) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            jdbc.queryForList("SELECT id FROM ra_run WHERE id=? AND owner_id=? FOR UPDATE",run,owner);
            if(jdbc.update("UPDATE ra_run SET status='RUNNING' WHERE id=? AND owner_id=? AND status='QUEUED'",run,owner)!=1) {return false;}
            jdbc.update("UPDATE ra_run_schedule SET started_at=? WHERE run_id=?",System.currentTimeMillis(),run);
            store.appendEvent(owner,run,"EXECUTION_STARTED","{}");
            return true;
        }));
    }
}
