package cn.ray.agent.server.knowledge;

import cn.ray.agent.server.rag.chunk.*;
import cn.ray.agent.server.rag.embedding.*;
import cn.ray.agent.server.rag.ingest.DocumentIngestionService;
import cn.ray.agent.server.rag.parse.ParserSelector;
import cn.ray.agent.server.rag.store.PgVectorStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL/pgvector，Embedding 为可控模拟；不把它记作真实向量模型验证。 */
@EnabledIfEnvironmentVariable(named = "RAY_PG_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresKnowledgeTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DocumentRepository store;
    private DocumentService service;
    private DocumentFileStore files;
    private String schema;
    @TempDir static Path storage;
    private final EmbeddingProfile profile = new EmbeddingProfile(URI.create("https://example.com/embeddings"), "mock-embedding", 3, "v1");

    @BeforeAll
    void setup() {
        var source = new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL"), System.getenv("RAY_TEST_DB_USER"), System.getenv("RAY_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(source);
        schema = "r04_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var isolated = new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL") + "?currentSchema=" + schema, System.getenv("RAY_TEST_DB_USER"), System.getenv("RAY_TEST_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("db/migration/V004__knowledge_document.sql"),
                new ClassPathResource("db/migration/V005__document_chunk_vector.sql")).execute(isolated);
        jdbc = new JdbcTemplate(isolated);
        store = new DocumentRepository(jdbc, new DataSourceTransactionManager(isolated), new ChunkRepository(jdbc), new PgVectorStore(jdbc));
        files = new DocumentFileStore(storage.toString());
        service = new DocumentService(store, files, new ParserSelector());
    }

    @AfterAll
    void cleanup() {
        if (schema != null && schema.matches("r04_test_[a-f0-9]{32}")) {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    private DocumentRepository.KnowledgeBase base() {
        return store.createBase("owner-a", UUID.randomUUID().toString());
    }

    private EmbeddingClient fake(boolean fail, boolean badDimension) {
        return new EmbeddingClient() {
            public EmbeddingProfile profile() {
                return profile;
            }
            public List<float[]> embed(List<String> texts) {
                if (fail) {
                    throw new Failure("TEST_FAILURE", "模拟 Embedding 失败");
                }
                return texts.stream().map(text -> badDimension ? new float[]{1, 0} : new float[]{1, 0, 0}).toList();
            }
        };
    }

    private void process(EmbeddingClient embedding) {
        var work = store.claim();
        assertNotNull(work);
        var ingestion = new DocumentIngestionService(store, files, new ParserSelector(), embedding);
        try {
            ingestion.process(work);
        } finally {
            ingestion.close();
        }
    }

    @Test
    void uploadOwnershipHashTypeAndDuplicateVersionAreEnforced() throws Exception {
        var kb = base();
        long before;
        try (var entries = Files.list(storage)) {
            before = entries.count();
        }
        assertThrows(ResponseStatusException.class, () -> service.upload("other", kb.id(), null, file("file.txt", "secret"), null));
        assertThrows(ResponseStatusException.class, () -> service.upload("owner-a", kb.id(), null, file("../file.txt", "x"), null));
        assertThrows(ResponseStatusException.class, () -> service.upload("owner-a", kb.id(), null, file("file.exe", "x"), null));
        assertThrows(ResponseStatusException.class, () -> service.upload("owner-a", kb.id(), null,
                new MockMultipartFile("file", "big.txt", "text/plain", new byte[5 * 1024 * 1024 + 1]), null));
        assertThrows(ResponseStatusException.class, () -> service.upload("owner-a", kb.id(), null, file("file.txt", "x"), "0".repeat(64)));
        var first = service.upload("owner-a", kb.id(), null, file("file.txt", "Pine42"), null);
        var duplicate = service.upload("owner-a", kb.id(), null, file("file.txt", "Pine42"), null);
        assertEquals(first.id(), duplicate.id());
        assertEquals(1, store.versions("owner-a", kb.id(), first.documentId()).size());
        try (var entries = Files.list(storage)) {
            assertEquals(before + 1, entries.count());
        }
        var another = store.createBase("other", UUID.randomUUID().toString());
        assertThrows(ResponseStatusException.class, () -> service.upload("other", another.id(), first.documentId(), file("x.txt", "x"), null));
        assertThrows(ResponseStatusException.class, () -> store.chunks("other", kb.id(), first.documentId(), first.id()));
        // 不让本例的待处理版本干扰后面的领取测试。
        process(fake(false, false));
    }

    @Test
    void publishesRealPgvectorRowsWithSourceAndIdempotentChunkIds() {
        var kb = base();
        var version = service.upload("owner-a", kb.id(), null, file("source.md", "# 标题\n\n自写资料 Pine42，树木需要浇水。"), null);
        process(fake(false, false));
        var result = store.version("owner-a", kb.id(), version.documentId(), version.id());
        assertEquals("READY", result.status());
        assertEquals(profile.id(), result.embeddingProfile());
        var chunks = store.chunks("owner-a", kb.id(), version.documentId(), version.id());
        assertEquals(chunks.size(), store.vectorCount("owner-a", kb.id(), version.documentId(), version.id()));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.heading().equals("标题") && chunk.content().contains("Pine42")));
        assertEquals(3, jdbc.queryForObject("SELECT public.vector_dims(embedding) FROM ra_chunk_vector WHERE chunk_id=?", Integer.class, chunks.get(0).id()));
        assertEquals("[1,0,0]", jdbc.queryForObject("SELECT embedding::text FROM ra_chunk_vector WHERE chunk_id=?", String.class, chunks.get(0).id()));
        assertEquals(version.id(), store.documents("owner-a", kb.id()).get(0).activeVersionId());
        assertThrows(ResponseStatusException.class, () -> store.retry("owner-a", kb.id(), version.documentId(), version.id()));
    }

    @Test
    void failedNewVersionKeepsPreviousActiveAndRetryReplacesInsteadOfDuplicating() {
        var kb = base();
        var first = service.upload("owner-a", kb.id(), null, file("history.txt", "first Pine42"), null);
        process(fake(false, false));
        var second = service.upload("owner-a", kb.id(), first.documentId(), file("history.txt", "second Oak73"), null);
        process(fake(true, false));
        assertEquals("FAILED", store.version("owner-a", kb.id(), second.documentId(), second.id()).status());
        assertEquals(first.id(), store.documents("owner-a", kb.id()).get(0).activeVersionId());
        assertEquals(0, store.vectorCount("owner-a", kb.id(), second.documentId(), second.id()));
        var originalChunks = store.chunks("owner-a", kb.id(), second.documentId(), second.id());
        store.retry("owner-a", kb.id(), second.documentId(), second.id());
        process(fake(false, false));
        assertEquals(second.id(), store.documents("owner-a", kb.id()).get(0).activeVersionId());
        assertEquals(originalChunks, store.chunks("owner-a", kb.id(), second.documentId(), second.id()));
        assertEquals(originalChunks.size(), store.vectorCount("owner-a", kb.id(), second.documentId(), second.id()));
    }

    @Test
    void badDimensionRollsBackPublicationAndRestartIsExplicit() {
        var kb = base();
        var version = service.upload("owner-a", kb.id(), null, file("bad.txt", "dimension test"), null);
        process(fake(false, true));
        assertEquals("FAILED", store.version("owner-a", kb.id(), version.documentId(), version.id()).status());
        assertNull(store.documents("owner-a", kb.id()).get(0).activeVersionId());
        assertEquals(0, store.vectorCount("owner-a", kb.id(), version.documentId(), version.id()));
        store.retry("owner-a", kb.id(), version.documentId(), version.id());
        assertNotNull(store.claim());
        store.recover();
        assertEquals("INTERRUPTED", store.version("owner-a", kb.id(), version.documentId(), version.id()).status());
    }

    @Test
    void concurrentIdenticalUploadsReserveOnlyOneVersion() throws Exception {
        var kb = base();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<DocumentRepository.Version> upload = () -> {
                gate.await();
                return service.upload("owner-a", kb.id(), null, file("same.txt", "same content"), null);
            };
            var a = pool.submit(upload);
            var b = pool.submit(upload);
            gate.countDown();
            assertEquals(a.get(5, TimeUnit.SECONDS).id(), b.get(5, TimeUnit.SECONDS).id());
            assertEquals(1, store.documents("owner-a", kb.id()).size());
        } finally {
            pool.shutdownNow();
        }
        process(fake(false, false));
    }
}
