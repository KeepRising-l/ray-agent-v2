package cn.ray.agent.server.run.admission;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** 事务内的会话行锁只保护创建与检查，运行互斥还需检查活动状态，不持有整轮数据库事务。 */
public final class SessionWriteLease {
    public void acquire(JdbcTemplate jdbc,String owner,String session) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("session write lease requires a transaction");
        }
        if (jdbc.queryForList("SELECT id FROM ra_session WHERE id=? AND owner_id=? FOR UPDATE",session,owner).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
        }
    }
}
