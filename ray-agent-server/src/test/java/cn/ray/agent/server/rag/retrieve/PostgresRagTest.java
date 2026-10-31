package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.server.conversation.ConversationRepository;
import cn.ray.agent.server.knowledge.*;
import cn.ray.agent.server.rag.answer.*;
import cn.ray.agent.server.rag.chunk.*;
import cn.ray.agent.server.rag.embedding.*;
import cn.ray.agent.server.rag.ingest.*;
import cn.ray.agent.server.rag.parse.ParserSelector;
import cn.ray.agent.server.rag.store.PgVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RAY_PG_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresRagTest {
    @TempDir static Path storage;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private DocumentRepository documents;
    private ConversationRepository conversations;
    private DocumentService uploads;
    private DocumentFileStore files;
    private PgVectorStore vectors;
    private final EmbeddingProfile profile = new EmbeddingProfile(URI.create("https://example.com/embedding"), "test", 3, "v1");

    @BeforeAll
    void setup() {
        var base = new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL"), System.getenv("RAY_TEST_DB_USER"), System.getenv("RAY_TEST_DB_PASSWORD"));
        admin = new JdbcTemplate(base);
        schema = "r05_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(System.getenv("RAY_TEST_DB_URL") + "?currentSchema=" + schema, System.getenv("RAY_TEST_DB_USER"), System.getenv("RAY_TEST_DB_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("db/migration/V004__knowledge_document.sql"),
                new ClassPathResource("db/migration/V005__document_chunk_vector.sql"), new ClassPathResource("db/migration/V006__knowledge_chat_lifecycle.sql"),new ClassPathResource("db/migration/V008__run_queue_and_document_attempts.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        vectors = new PgVectorStore(jdbc);
        documents = new DocumentRepository(jdbc, manager, new ChunkRepository(jdbc), vectors);
        conversations = new ConversationRepository(jdbc, manager);
        files = new DocumentFileStore(storage.toString());
        uploads = new DocumentService(documents, files, new ParserSelector());
    }

    @AfterAll
    void close() {
        if (schema != null && schema.matches("r05_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private String kb(String owner) {
        return documents.createBase(owner, UUID.randomUUID().toString()).id();
    }

    private MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/plain", text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private EmbeddingClient embedding(EmbeddingProfile configured) {
        return new EmbeddingClient() {
            public EmbeddingProfile profile() { return configured; }
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> {
                    float[] vector = new float[configured.dimension()];
                    vector[0] = 1;
                    return vector;
                }).toList();
            }
        };
    }

    private void process(EmbeddingProfile configured) {
        var task = documents.claim();
        assertNotNull(task);
        var worker = new DocumentIngestionService(documents, files, new ParserSelector(), embedding(configured));
        try { worker.process(task); } finally { worker.close(); }
    }

    private DocumentRepository.Version seed(String owner, String kb, String text, EmbeddingProfile configured) {
        var version = uploads.upload(owner, kb, null, file(UUID.randomUUID() + ".txt", text), null);
        process(configured);
        assertEquals("READY", documents.version(owner, kb, version.documentId(), version.id()).status());
        return version;
    }

    private List<RetrievedChunk> search(String owner, String kb) {
        return vectors.search(new RetrievalQuery("问题", new RetrievalFilter(owner, List.of(kb)), 6, null), profile, new float[]{1, 0, 0});
    }

    @Test
    void threeModeCreationPersistsExplicitScopeAndRejectsForeignKnowledgeBases() {
        String own = kb("mode-owner");
        String foreign = kb("another-owner");
        var emptyAuto = conversations.create("mode-owner", "无资料AUTO", "AUTO", List.of());
        assertEquals("AUTO", conversations.session("mode-owner", emptyAuto.id()).mode());
        assertTrue(emptyAuto.knowledgeBaseIds().isEmpty());
        var scoped = conversations.create("mode-owner", "有资料AUTO", "AUTO", List.of(own));
        assertEquals(List.of(own), conversations.session("mode-owner", scoped.id()).knowledgeBaseIds());
        assertThrows(ResponseStatusException.class, () -> conversations.create("mode-owner", "越权", "AUTO", List.of(foreign)));
        assertThrows(ResponseStatusException.class, () -> conversations.create("mode-owner", "非法", "INVALID", List.of()));
        assertThrows(ResponseStatusException.class, () -> conversations.create("mode-owner", "CHAT越界", "CHAT", List.of(own)));
        assertThrows(ResponseStatusException.class, () -> conversations.create("mode-owner", "空KB", "KNOWLEDGE", List.of()));
        assertThrows(ResponseStatusException.class, () -> conversations.session("another-owner", scoped.id()));
    }

    @Test
    void ownerKbActiveVersionAndDifferentDimensionsAreFilteredBeforeDistance() {
        String mine = kb("owner-a");
        var own = seed("owner-a", mine, "授权资料 Pine42", profile);
        String otherKb = kb("owner-a");
        seed("owner-a", otherKb, "不能越库的资料 Oak73", profile);
        String privateKb = kb("owner-b");
        seed("owner-b", privateKb, "其他用户的资料 Secret99", profile);
        var anotherSpace = new EmbeddingProfile(profile.endpoint(), "another-model", 2, "v1");
        seed("owner-a", mine, "同库不同向量空间", anotherSpace);
        var hits = search("owner-a", mine);
        assertEquals(1, hits.size());
        assertEquals(own.id(), hits.get(0).versionId());
        assertEquals(1.0, hits.get(0).score(), .0001);
        var service = new RetrievalService(documents, embedding(profile), vectors, "");
        assertThrows(ResponseStatusException.class, () -> service.retrieve("问题", new RetrievalFilter("owner-b", List.of(mine))));
    }

    @Test
    void citationBecomesExpiredAfterDeleteAndCannotBeReadByAnotherOwner() throws Exception {
        String kb = kb("owner-a");
        var version = seed("owner-a", kb, "原文中的 Pine42", profile);
        var evidence = new EvidenceAssembler().assemble(search("owner-a", kb));
        var session = conversations.create("owner-a", "知识库", "KNOWLEDGE", List.of(kb));
        var run = conversations.begin("owner-a", session.id(), "request", "model", "model", "question");
        conversations.appendEvent("owner-a", run.id(), "RAG_EVIDENCE", new ObjectMapper().writeValueAsString(Map.of("citations", evidence.citations())));
        var answers = new GroundedAnswerService(new RetrievalService(documents, embedding(profile), vectors, ""), documents, conversations);
        assertTrue(answers.citation("owner-a", run.id(), "K1").text().contains("Pine42"));
        assertThrows(ResponseStatusException.class, () -> answers.citation("owner-b", run.id(), "K1"));
        new DocumentDeleteService(documents, files).delete("owner-a", kb, version.documentId());
        assertTrue(search("owner-a", kb).isEmpty());
        var old = answers.citation("owner-a", run.id(), "K1");
        assertFalse(old.valid());
        assertEquals("", old.text());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ra_chunk_vector x JOIN ra_document_chunk c ON c.id=x.chunk_id WHERE c.version_id=?", Integer.class, version.id()));
    }

    @Test
    void updateAndReindexReplaceOnlyAfterPublicationAndInvalidateOldSource() {
        String kb = kb("owner-a");
        var first = seed("owner-a", kb, "旧代号 Pine42", profile);
        var second = uploads.upload("owner-a", kb, first.documentId(), file("new.txt", "新代号 Oak73"), null);
        assertEquals(first.id(), search("owner-a", kb).get(0).versionId());
        process(profile);
        assertEquals(second.id(), search("owner-a", kb).get(0).versionId());
        var oldChunk = documents.chunks("owner-a", kb, first.documentId(), first.id()).get(0);
        assertNull(documents.currentText("owner-a", kb, first.documentId(), first.id(), oldChunk.id()));
        var third = new DocumentReindexService(documents, new ParserSelector(), embedding(profile)).reindex("owner-a", kb, first.documentId());
        assertEquals(second.id(), search("owner-a", kb).get(0).versionId());
        process(profile);
        assertEquals(third.id(), search("owner-a", kb).get(0).versionId());
        assertEquals(1, search("owner-a", kb).size());
    }

    @Test
    void deletionDuringProcessingCannotRepublishAndSameNameCanBeUploadedAgain() {
        String kb = kb("owner-a");
        var version = uploads.upload("owner-a", kb, null, file("same.txt", "待删除资料"), null);
        var claimed = documents.claim();
        assertNotNull(claimed);
        new DocumentDeleteService(documents, files).delete("owner-a", kb, version.documentId());
        var worker = new DocumentIngestionService(documents, files, new ParserSelector(), embedding(profile));
        try { worker.process(claimed); } finally { worker.close(); }
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM ra_document_version WHERE id=?", String.class, version.id()));
        assertTrue(search("owner-a", kb).isEmpty());
        var fresh = uploads.upload("owner-a", kb, null, file("same.txt", "新资料"), null);
        assertNotEquals(version.documentId(), fresh.documentId());
        process(profile);
    }

    @Test
    void sessionsRejectUnauthorizedKbAndReindexRejectsChangedProfile() {
        String kb = kb("owner-a");
        assertThrows(ResponseStatusException.class, () -> conversations.create("owner-b", "bad", "KNOWLEDGE", List.of(kb)));
        assertThrows(ResponseStatusException.class, () -> conversations.create("owner-a", "bad", "CHAT", List.of(kb)));
        var session = conversations.create("owner-a", "good", "KNOWLEDGE", List.of(kb));
        assertEquals(List.of(kb), session.knowledgeBaseIds());
        var first = seed("owner-a", kb, "重建原文", profile);
        var target = new EmbeddingProfile(profile.endpoint(), "changed-model", 3, "v2");
        var reindexed = new DocumentReindexService(documents, new ParserSelector(), embedding(target)).reindex("owner-a", kb, first.documentId());
        process(profile);
        assertEquals("FAILED", documents.version("owner-a", kb, first.documentId(), reindexed.id()).status());
        assertEquals(first.id(), search("owner-a", kb).get(0).versionId());
    }
}
