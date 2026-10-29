package cn.ray.agent.server.run;

import cn.ray.agent.server.conversation.ConversationRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/** 当前单实例运行保护：持有 PostgreSQL 会话锁，启动时将遗留运行标记为中断。 */
@Component
public class RunStateService implements ApplicationRunner {
    private final DataSource source;
    private final ConversationRepository store;
    private Connection lease;

    public RunStateService(DataSource source, ConversationRepository store) {
        this.source = source;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        lease = source.getConnection();
        try (var statement = lease.createStatement();
             var rows = statement.executeQuery("SELECT pg_try_advisory_lock(hashtext(current_schema()), 20402)")) {
            rows.next();
            if (!rows.getBoolean(1)) {
                throw new IllegalStateException("当前数据库已有 RayAgent 实例，请停止旧实例或使用独立数据库");
            }
        }
        store.recover();
    }

    @PreDestroy
    public void close() throws Exception {
        if (lease != null) {
            try (var statement = lease.createStatement()) {
                statement.execute("SELECT pg_advisory_unlock(hashtext(current_schema()), 20402)");
            } finally {
                lease.close();
            }
        }
    }
}
