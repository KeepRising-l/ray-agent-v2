package cn.ray.agent.server.rag.ingest;

import cn.ray.agent.server.knowledge.DocumentRepository;
import cn.ray.agent.server.rag.chunk.ChunkPolicy;
import cn.ray.agent.server.rag.embedding.EmbeddingClient;
import cn.ray.agent.server.rag.parse.ParserSelector;
import org.springframework.stereotype.Service;

@Service
public class DocumentReindexService {
    private final DocumentRepository store;
    private final ParserSelector parsers;
    private final EmbeddingClient embedding;

    public DocumentReindexService(DocumentRepository store, ParserSelector parsers, EmbeddingClient embedding) {
        this.store = store;
        this.parsers = parsers;
        this.embedding = embedding;
    }

    public DocumentRepository.Version reindex(String owner, String kb, String doc) {
        var latest = store.versions(owner, kb, doc).get(0);
        return store.reindex(owner, kb, doc, parsers.select(latest.fileType()).version(), ChunkPolicy.DEFAULT.id(), embedding.profile().id());
    }
}
