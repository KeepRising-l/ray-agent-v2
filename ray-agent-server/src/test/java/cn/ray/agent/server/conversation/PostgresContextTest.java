package cn.ray.agent.server.conversation;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.context.*;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.run.question.PendingQuestionStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RAY_PG_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresContextTest {
    private JdbcTemplate admin,jdbc;private String schema;
    private ConversationRepository store;private PendingQuestionStore questions;private SessionRebuilder rebuilder;
    private CompactionRecorder recorder;private SessionRestoreService restore;
    private DataSourceTransactionManager manager;
    @BeforeAll void setup() {
        var base=new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL"),System.getenv("RAY_TEST_DB_USER"),System.getenv("RAY_TEST_DB_PASSWORD"));
        admin=new JdbcTemplate(base);schema="r07_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL")+"?currentSchema="+schema,System.getenv("RAY_TEST_DB_USER"),System.getenv("RAY_TEST_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("db/migration/V004__knowledge_document.sql"),
                new ClassPathResource("db/migration/V005__document_chunk_vector.sql"),new ClassPathResource("db/migration/V006__knowledge_chat_lifecycle.sql"),
                new ClassPathResource("db/migration/V007__context_and_user_questions.sql"),new ClassPathResource("db/migration/V008__run_queue_and_document_attempts.sql")).execute(source);
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);store=new ConversationRepository(jdbc,manager);
        questions=new PendingQuestionStore(jdbc,manager,store,60000);rebuilder=new SessionRebuilder(jdbc,store);
        recorder=new CompactionRecorder(jdbc,manager,rebuilder);restore=new SessionRestoreService(rebuilder,recorder);
    }
    @AfterAll void clean() { if(schema!=null&&schema.matches("r07_test_[a-f0-9]{32}"))admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    private ConversationRepository.Run run(String owner,String session,String input) {return store.begin(owner,session,UUID.randomUUID().toString(),"test","test",input);}
    private PendingQuestionStore.Snapshot snapshot() {
        var request=new GenerateRequest(List.of(new Message(Message.Role.USER,"原问题")));
        return new PendingQuestionStore.Snapshot(1,new UserQuestionRequired.Continuation("AUTO_ROUTE","你希望查哪类来源？",request,null),
                new RunBudget(5,4,60000,1000,24000),1,10,100,new TokenUsage(4,2),"已输出的部分内容");
    }

    @Test void summaryProjectionIsStableAndOriginalHistoryIsUntouched() {
        var session=store.create("a","summary");
        for(int i=0;i<6;i++){var r=run("a",session.id(),"原问题"+i);store.finish("a",r.id(),"SUCCEEDED","原答案"+i,null,null);}
        var active=run("a",session.id(),"当前问题");var turns=rebuilder.turns("a",session.id());
        var prefix=turns.subList(0,3);var range=new SummaryRange(prefix.stream().map(SessionRebuilder.Turn::runId).toList(),SessionRebuilder.hash(prefix));
        var original=store.messages("a",session.id());
        assertTrue(recorder.publish("a",session.id(),active.id(),0,range,"早期事实编号Mango91 [K9]"));
        var view=restore.restore("a",session.id());assertEquals(3,view.covered());assertTrue(view.valid());
        assertFalse(view.messages().get(0).content().contains("[K9]"));
        assertEquals(8,view.messages().size());assertEquals(original,store.messages("a",session.id()));
        var reconstructed=new SessionRestoreService(new SessionRebuilder(jdbc,store),new CompactionRecorder(jdbc,manager,rebuilder));
        assertEquals(view.messages(),reconstructed.restore("a",session.id()).messages());
        assertFalse(recorder.publish("a",session.id(),active.id(),0,range,"旧revision不能重复覆盖"));
        assertThrows(ResponseStatusException.class,()->restore.restore("b",session.id()));
    }

    @Test void invalidRangeIsNotAppliedAndCancelledRunCannotPublish() {
        var session=store.create("a","range");var first=run("a",session.id(),"q");store.finish("a",first.id(),"SUCCEEDED","a",null,null);
        var current=run("a",session.id(),"next");var prefix=rebuilder.turns("a",session.id());
        var range=new SummaryRange(List.of(first.id()),SessionRebuilder.hash(prefix));
        assertTrue(recorder.publish("a",session.id(),current.id(),0,range,"正确摘要"));
        jdbc.update("UPDATE ra_session_summary SET source_hash=? WHERE session_id=?","0".repeat(64),session.id());
        assertFalse(restore.restore("a",session.id()).valid());assertEquals(0,restore.restore("a",session.id()).covered());
        store.finish("a",current.id(),"CANCELLED",null,null,null);
        assertFalse(recorder.publish("a",session.id(),current.id(),1,range,"不应发布"));
    }

    @Test void waitingSurvivesRecoveryAndAnswerIsConsumedOnceWithOwnership() {
        var session=store.create("a","waiting","AUTO",List.of());var r=run("a",session.id(),"原问题");
        var q=questions.suspend("a",r.id(),snapshot());
        assertEquals("WAITING_USER",store.run("a",r.id()).status());store.recover();assertEquals("WAITING_USER",store.run("a",r.id()).status());
        assertThrows(ResponseStatusException.class,()->run("a",session.id(),"不许新建并发run"));
        assertThrows(ResponseStatusException.class,()->questions.consume("b",r.id(),q.id(),"other"));
        assertThrows(ResponseStatusException.class,()->questions.consume("a",r.id(),UUID.randomUUID().toString(),"wrong"));
        var accepted=questions.consume("a",r.id(),q.id(),"知识库");assertEquals("知识库",accepted.answer());
        assertEquals("RUNNING",store.run("a",r.id()).status());
        assertThrows(ResponseStatusException.class,()->questions.consume("a",r.id(),q.id(),"重复"));
        store.finish("a",r.id(),"SUCCEEDED","完整答案",new TokenUsage(8,4),null);
        assertEquals(2,store.messages("a",session.id()).size());
        assertTrue(rebuilder.turns("a",session.id()).get(0).messages().get(0).content().contains("知识库"));
    }

    @Test void expiredAndCancelledQuestionsNeverResume() {
        for(boolean expiry:List.of(true,false)) {
            var session=store.create("a","end","AUTO",List.of());var r=run("a",session.id(),"q");var q=questions.suspend("a",r.id(),snapshot());
            if(expiry){jdbc.update("UPDATE ra_user_question SET expires_at=0 WHERE id=?",q.id());questions.expire();}
            else questions.cancel("a",r.id());
            assertEquals(expiry?"TIMED_OUT":"CANCELLED",store.run("a",r.id()).status());
            assertThrows(ResponseStatusException.class,()->questions.consume("a",r.id(),q.id(),"late"));
            assertEquals("已输出的部分内容",store.messages("a",session.id()).get(1).content());
            assertFalse(store.messages("a",session.id()).get(1).complete());
        }
    }

    @Test void competingAnswersOnlyOneCanClaimQuestion() throws Exception {
        var session=store.create("a","race","AUTO",List.of());var r=run("a",session.id(),"q");var q=questions.suspend("a",r.id(),snapshot());
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobs=workers.invokeAll(List.of(()->{try{questions.consume("a",r.id(),q.id(),"one");return true;}catch(ResponseStatusException e){return false;}},
                    ()->{try{questions.consume("a",r.id(),q.id(),"two");return true;}catch(ResponseStatusException e){return false;}}));
            assertEquals(1,jobs.stream().filter(job->{try{return Boolean.TRUE.equals(job.get());}catch(Exception e){throw new RuntimeException(e);}}).count());
        } finally { workers.shutdownNow(); }
    }
}
