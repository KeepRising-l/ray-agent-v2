package cn.ray.agent.server.conversation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 手动启用的真实 PostgreSQL 验证；每次使用独立随机 schema，结束仅清理该 schema。 */
@EnabledIfEnvironmentVariable(named = "RAY_PG_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConversationTest {
    private JdbcTemplate admin;
    private ConversationRepository store;
    private String schema;

    @BeforeAll void setup() {
        var base = new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL"),
                System.getenv("RAY_TEST_DB_USER"), System.getenv("RAY_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(base);
        schema = "r02_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL") + "?currentSchema=" + schema,
                System.getenv("RAY_TEST_DB_USER"), System.getenv("RAY_TEST_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        store = new ConversationRepository(new JdbcTemplate(source), new DataSourceTransactionManager(source));
    }

    @AfterAll void cleanup() {
        if (schema != null && schema.matches("r02_test_[a-f0-9]{32}")) {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void historyOwnershipAndUniqueTerminal() {
        var session = store.create("owner-a", "test");
        var run = store.begin("owner-a", session.id(), "request", "channel", "model", "question");
        assertThrows(ResponseStatusException.class, () -> store.messages("other-owner", session.id()));
        assertThrows(ResponseStatusException.class, () -> store.run("other-owner", run.id()));
        store.context("owner-a", run.id(), 20, 0);
        assertTrue(store.finish("owner-a", run.id(), "SUCCEEDED", "answer", null, null));
        assertFalse(store.finish("owner-a", run.id(), "CANCELLED", "duplicate", null, null));
        assertEquals(2, store.messages("owner-a", session.id()).size());
        assertEquals(2, store.history("owner-a", session.id()).size());
        assertNull(store.run("owner-a", run.id()).inputTokens());
        var events = store.events("owner-a", run.id());
        assertEquals(3, events.size());
        assertEquals(3, events.get(2).seq());
        assertThrows(ResponseStatusException.class, () -> store.begin("owner-a", session.id(), "request", "channel", "model", "duplicate"));
    }

    @Test void concurrentSessionRequestsCannotBothStart() throws Exception {
        var session = store.create("owner-a", "concurrency");
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        Callable<Boolean> start = () -> {
            gate.await();
            try {
                store.begin("owner-a", session.id(), UUID.randomUUID().toString(), "channel", "model", "question");
                return true;
            } catch (ResponseStatusException e) {
                return false;
            }
        };
        try {
            var a = executor.submit(start); var b = executor.submit(start); gate.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test void failedPartialOutputIsVisibleButNotModelHistory() {
        var session = store.create("owner-a", "partial");
        var run = store.begin("owner-a", session.id(), "request", "channel", "model", "question");
        store.finish("owner-a", run.id(), "CANCELLED", "partial", null, null);
        assertEquals(2, store.messages("owner-a", session.id()).size());
        assertFalse(store.messages("owner-a", session.id()).get(1).complete());
        assertTrue(store.history("owner-a", session.id()).isEmpty());
    }

    @Test void restartRecoveryMarksOldRunsInterrupted() {
        var session = store.create("owner-b", "restart");
        var run = store.begin("owner-b", session.id(), "request", "channel", "model", "question");
        store.recover();
        assertEquals("INTERRUPTED", store.run("owner-b", run.id()).status());
    }
}
