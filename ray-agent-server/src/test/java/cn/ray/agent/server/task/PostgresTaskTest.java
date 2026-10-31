package cn.ray.agent.server.task;

import cn.ray.agent.runtime.agent.*;
import cn.ray.agent.runtime.model.*;
import cn.ray.agent.server.conversation.*;
import cn.ray.agent.server.run.*;
import cn.ray.agent.server.knowledge.*;
import cn.ray.agent.server.rag.chunk.*;
import cn.ray.agent.server.rag.embedding.*;
import cn.ray.agent.server.rag.ingest.*;
import cn.ray.agent.server.rag.parse.*;
import cn.ray.agent.server.rag.store.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RAY_PG_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTaskTest {
    @TempDir static Path storage;
    private JdbcTemplate admin,jdbc;private String schema;private ConversationRepository runs;private DocumentRepository docs;
    private DataSourceTransactionManager manager;private TaskStateRepository states;private TaskQueryService queries;
    private DocumentFileStore files;private DocumentService uploads;
    private final EmbeddingProfile profile=new EmbeddingProfile(URI.create("https://example.com/embedding"),"test",3,"v1");
    @BeforeAll void setup(){
        var base=new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL"),System.getenv("RAY_TEST_DB_USER"),System.getenv("RAY_TEST_DB_PASSWORD"));admin=new JdbcTemplate(base);
        schema="r08_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL")+"?currentSchema="+schema,System.getenv("RAY_TEST_DB_USER"),System.getenv("RAY_TEST_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("db/migration/V004__knowledge_document.sql"),new ClassPathResource("db/migration/V005__document_chunk_vector.sql"),new ClassPathResource("db/migration/V006__knowledge_chat_lifecycle.sql"),new ClassPathResource("db/migration/V007__context_and_user_questions.sql"),new ClassPathResource("db/migration/V008__run_queue_and_document_attempts.sql")).execute(source);
        jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);runs=new ConversationRepository(jdbc,manager);
        docs=new DocumentRepository(jdbc,manager,new ChunkRepository(jdbc),new PgVectorStore(jdbc));states=new TaskStateRepository(jdbc,manager,runs);
        queries=new TaskQueryService(jdbc,new SessionRegistry());files=new DocumentFileStore(storage.toString());uploads=new DocumentService(docs,files,new ParserSelector());
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE ra_session,ra_knowledge_base CASCADE");}
    @AfterAll void clean(){if(schema!=null&&schema.matches("r08_test_[a-f0-9]{32}"))admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    private ConversationRepository.Run queued(String owner,String session,String request){return runs.beginQueued(owner,session,request,"channel","model","question");}
    @Test void queuedStateSerializesSessionAndDuplicateReturnsOriginalRun(){
        var session=runs.create("a","q");var r=queued("a",session.id(),"same");assertEquals("QUEUED",r.status());
        var duplicate=assertThrows(RequestIdempotencyService.Duplicate.class,()->queued("a",session.id(),"same"));assertEquals(r.id(),duplicate.runId);
        assertThrows(ResponseStatusException.class,()->queued("a",session.id(),"different"));
        var idempotency=new RequestIdempotencyService(jdbc,runs);assertEquals(r.id(),idempotency.find("a",session.id(),"same").id());
        assertFalse(assertThrows(RequestIdempotencyService.Duplicate.class,()->idempotency.assertNew("a",session.id(),"same","other","channel")).sameInput);
        assertThrows(ResponseStatusException.class,()->idempotency.find("b",session.id(),"same"));
        assertTrue(states.start("a",r.id()));assertFalse(states.start("a",r.id()));assertEquals("RUNNING",runs.run("a",r.id()).status());
    }
    @Test void concurrentDuplicateRequestsCreateExactlyOneDatabaseRun() throws Exception {
        var session=runs.create("a","race");var workers=Executors.newFixedThreadPool(6);
        try{
            var jobs=new ArrayList<Callable<Boolean>>();for(int i=0;i<6;i++)jobs.add(()->{try{queued("a",session.id(),"one");return true;}catch(ResponseStatusException error){return false;}});
            var results=workers.invokeAll(jobs);int success=0;for(var result:results)if(result.get())success++;
            assertEquals(1,success);assertEquals(1,runs.runs("a",session.id()).size());
        }finally{workers.shutdownNow();}
    }
    @Test void queuedCancellationAndRestartDoNotReplayConversation(){
        var s=runs.create("a","cancel");var r=queued("a",s.id(),"1");runs.finish("a",r.id(),"CANCELLED",null,null,null);assertFalse(states.start("a",r.id()));
        var next=queued("a",s.id(),"2");runs.recover();assertEquals("INTERRUPTED",runs.run("a",next.id()).status());assertFalse(states.start("a",next.id()));
        assertEquals("NONE",queries.list("a").stream().filter(t->t.id().equals(next.id())).findFirst().orElseThrow().recovery());
    }
    @Test void waitingAnswerRequeuesSameRunAndPreservesQuestionConsumption(){
        var session=runs.create("a","resume","AUTO",List.of());var r=queued("a",session.id(),"r");states.start("a",r.id());
        var questions=new PendingQuestionStore(jdbc,manager,runs,60000);var request=new GenerateRequest(List.of(new Message(Message.Role.USER,"q")));
        var snapshot=new PendingQuestionStore.Snapshot(1,new UserQuestionRequired.Continuation("AUTO_ROUTE","范围？",request,null),new RunBudget(5,4,60000,1000,24000),1,10,100,new TokenUsage(2,1),"");
        var question=questions.suspend("a",r.id(),snapshot);questions.consumeQueued("a",r.id(),question.id(),"直接解释Java");
        assertEquals("QUEUED",runs.run("a",r.id()).status());assertEquals(2,jdbc.queryForObject("SELECT phase FROM ra_run_schedule WHERE run_id=?",Integer.class,r.id()));
        assertThrows(ResponseStatusException.class,()->questions.consumeQueued("a",r.id(),question.id(),"again"));assertTrue(states.start("a",r.id()));
    }
    private DocumentRepository.Version upload(String owner,String kb){return uploads.upload(owner,kb,null,new MockMultipartFile("file",UUID.randomUUID()+".txt","text/plain","自写重试资料 Mango83".getBytes(java.nio.charset.StandardCharsets.UTF_8)),null);}
    private EmbeddingClient embedding(AtomicInteger calls,int failures,String code){return new EmbeddingClient(){
        public EmbeddingProfile profile(){return profile;}
        public List<float[]> embed(List<String> input){if(calls.incrementAndGet()<=failures)throw new EmbeddingClient.Failure(code,"test failure");return input.stream().map(text->new float[]{1,0,0}).toList();}
    };}
    private void process(EmbeddingClient embedding){var work=docs.claim(profile.id());assertNotNull(work);var worker=new DocumentIngestionService(docs,files,new ParserSelector(),embedding);try{worker.process(work);}finally{worker.close();}}
    private void due(RetryScheduler retry){retry.tick();jdbc.update("UPDATE ra_document_retry SET next_attempt_at=0 WHERE next_attempt_at IS NOT NULL");retry.tick();}
    @Test void transientEmbeddingRetryRecordsBothAttemptsAndPublishesWithoutDuplicateChunks(){
        String kb=docs.createBase("a",UUID.randomUUID().toString()).id();var version=upload("a",kb);var calls=new AtomicInteger();var embedding=embedding(calls,1,"HTTP_503");
        process(embedding);assertEquals("FAILED",docs.version("a",kb,version.documentId(),version.id()).status());
        due(new RetryScheduler(jdbc,docs,2,100));process(embedding);
        var ready=docs.version("a",kb,version.documentId(),version.id());assertEquals("READY",ready.status());assertEquals(2,calls.get());
        assertEquals(ready.chunkCount(),docs.vectorCount("a",kb,version.documentId(),version.id()));
        var attempts=queries.attempts("a",version.id());assertEquals(2,attempts.size());assertEquals("FAILED",attempts.get(0).get("status"));assertEquals("SUCCEEDED",attempts.get(1).get("status"));
        assertThrows(ResponseStatusException.class,()->queries.attempts("b",version.id()));
    }
    @Test void automaticRetryStopsAtLimitAndManualRetryStartsExplicitCycle(){
        String kb=docs.createBase("a",UUID.randomUUID().toString()).id();var v=upload("a",kb);var calls=new AtomicInteger();var embedding=embedding(calls,20,"HTTP_503");var retry=new RetryScheduler(jdbc,docs,2,100);
        process(embedding);for(int i=0;i<2;i++){due(retry);process(embedding);}due(retry);
        assertEquals("FAILED",docs.version("a",kb,v.documentId(),v.id()).status());assertEquals(3,queries.attempts("a",v.id()).size());assertNull(docs.claim(profile.id()));
        docs.retry("a",kb,v.documentId(),v.id());process(embedding);assertEquals(4,queries.attempts("a",v.id()).size());
    }
    @Test void authenticationFailureIsNotAutomaticallyRetriedAndOwnerQueriesStaySeparated(){
        String kb=docs.createBase("private",UUID.randomUUID().toString()).id();var v=upload("private",kb);var calls=new AtomicInteger();process(embedding(calls,10,"HTTP_401"));due(new RetryScheduler(jdbc,docs,2,100));
        assertEquals(1,calls.get());assertNull(docs.claim(profile.id()));assertTrue(queries.list("a").stream().noneMatch(t->t.id().equals(v.id())));
        assertEquals("RETRY_DOCUMENT",queries.list("private").stream().filter(t->t.id().equals(v.id())).findFirst().orElseThrow().recovery());
    }
    @Test void interruptedAttemptIsQueryableAndNotAutomaticallyReplayed(){
        String kb=docs.createBase("restart",UUID.randomUUID().toString()).id();var v=upload("restart",kb);assertNotNull(docs.claim(profile.id()));docs.recover();
        assertEquals("INTERRUPTED",docs.version("restart",kb,v.documentId(),v.id()).status());assertEquals("INTERRUPTED",queries.attempts("restart",v.id()).get(0).get("status"));
        due(new RetryScheduler(jdbc,docs,2,100));assertNull(docs.claim(profile.id()));
    }
}
