package cn.ray.agent.server.knowledge;

import cn.ray.agent.server.conversation.LocalOwner;
import cn.ray.agent.server.rag.chunk.DocumentChunk;
import cn.ray.agent.server.rag.embedding.OpenAiEmbeddingClient;
import cn.ray.agent.server.rag.ingest.DocumentReindexService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

/** 知识库管理和入库查询；owner 始终取服务端身份，当前仍为本机固定 owner。 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class DocumentUploadController {
    private final KnowledgeBaseService bases;
    private final DocumentService documents;
    private final DocumentRepository store;
    private final LocalOwner owner;
    private final OpenAiEmbeddingClient embedding;
    private final DocumentDeleteService deletion;
    private final DocumentReindexService reindex;

    public DocumentUploadController(KnowledgeBaseService bases, DocumentService documents, DocumentRepository store,
                                    LocalOwner owner, OpenAiEmbeddingClient embedding, DocumentDeleteService deletion, DocumentReindexService reindex) {
        this.bases = bases;
        this.documents = documents;
        this.store = store;
        this.owner = owner;
        this.embedding = embedding;
        this.deletion = deletion;
        this.reindex = reindex;
    }

    @GetMapping
    public List<DocumentRepository.KnowledgeBase> list() {
        return store.bases(owner.id());
    }

    @PostMapping
    public DocumentRepository.KnowledgeBase create(@RequestBody CreateBase request) {
        return bases.create(owner.id(), request.name());
    }

    @GetMapping("/embedding")
    public Map<String, Object> embedding() {
        return Map.of("configured", embedding.configured(), "model", embedding.profile().model(),
                "dimension", embedding.profile().dimension(),"profileId",embedding.profile().id());
    }

    @GetMapping("/{kb}/documents")
    public List<DocumentRepository.Document> documents(@PathVariable String kb) {
        return store.documents(owner.id(), kb);
    }

    @PostMapping(value = "/{kb}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentRepository.Version upload(@PathVariable String kb, @RequestParam MultipartFile file,
                                             @RequestParam(required = false) String sha256) {
        return documents.upload(owner.id(), kb, null, file, sha256);
    }

    @PostMapping(value = "/{kb}/documents/{doc}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentRepository.Version update(@PathVariable String kb, @PathVariable String doc, @RequestParam MultipartFile file,
                                             @RequestParam(required = false) String sha256) {
        return documents.upload(owner.id(), kb, doc, file, sha256);
    }

    @GetMapping("/{kb}/documents/{doc}/versions")
    public List<DocumentRepository.Version> versions(@PathVariable String kb, @PathVariable String doc) {
        return store.versions(owner.id(), kb, doc);
    }

    @GetMapping("/{kb}/documents/{doc}/versions/{version}")
    public Map<String, Object> detail(@PathVariable String kb, @PathVariable String doc, @PathVariable String version) {
        return Map.of("version", store.version(owner.id(), kb, doc, version), "vectorCount", store.vectorCount(owner.id(), kb, doc, version));
    }

    @GetMapping("/{kb}/documents/{doc}/versions/{version}/chunks")
    public List<DocumentChunk> chunks(@PathVariable String kb, @PathVariable String doc, @PathVariable String version) {
        return store.chunks(owner.id(), kb, doc, version);
    }

    @PostMapping("/{kb}/documents/{doc}/versions/{version}/retry")
    public DocumentRepository.Version retry(@PathVariable String kb, @PathVariable String doc, @PathVariable String version) {
        return store.retry(owner.id(), kb, doc, version);
    }

    public record CreateBase(String name) { }

    @DeleteMapping("/{kb}/documents/{doc}")
    public Map<String, Boolean> delete(@PathVariable String kb, @PathVariable String doc) {
        deletion.delete(owner.id(), kb, doc);
        return Map.of("deleted", true);
    }

    @PostMapping("/{kb}/documents/{doc}/reindex")
    public DocumentRepository.Version reindex(@PathVariable String kb, @PathVariable String doc) {
        return reindex.reindex(owner.id(), kb, doc);
    }
}
