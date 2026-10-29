package cn.ray.agent.server.knowledge;

import cn.ray.agent.server.rag.chunk.ChunkPolicy;
import cn.ray.agent.server.rag.parse.ParserSelector;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.Locale;

/** 上传入口只保存原文和待处理版本；异步处理到 READY 才算完成向量入库。 */
@Service
public class DocumentService {
    private final DocumentRepository store;
    private final DocumentFileStore files;
    private final ParserSelector parsers;

    public DocumentService(DocumentRepository store, DocumentFileStore files, ParserSelector parsers) {
        this.store = store;
        this.files = files;
        this.parsers = parsers;
    }

    public DocumentRepository.Version upload(String owner, String kb, String doc, MultipartFile file, String expectedHash) {
        store.base(owner, kb);
        if (doc != null) {
            store.versions(owner, kb, doc);
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 200 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl) || file.isEmpty() || file.getSize() > 5 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名无效、文件为空或超过 5 MB");
        }
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("txt", "md", "pdf").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 TXT、Markdown 和文本 PDF");
        }
        String mime = file.getContentType();
        if (mime != null && !mime.equals("application/octet-stream") && !(type.equals("pdf")
                ? mime.equals("application/pdf") : java.util.Set.of("text/plain", "text/markdown", "text/x-markdown").contains(mime))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件类型与扩展名不符");
        }
        String key = null;
        boolean retained = false;
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > 5 * 1024 * 1024) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件超过 5 MB");
            }
            String hash = DocumentFileStore.sha256(bytes);
            if (expectedHash != null && (!expectedHash.matches("[a-fA-F0-9]{64}") || !hash.equalsIgnoreCase(expectedHash))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件 SHA-256 摘要校验失败");
            }
            key = files.save(bytes);
            var accepted = store.accept(owner, kb, doc, name, hash, bytes.length, type, key, parsers.select(type).version(), ChunkPolicy.DEFAULT.id());
            retained = accepted.newFile();
            return accepted.version();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文档文件失败，请检查本机存储");
        } finally {
            if (key != null && !retained) {
                try {
                    files.discard(key);
                } catch (java.io.IOException ignored) {
                    // 不掩盖原始业务错误；该文件没有元数据引用，不会进入检索。
                }
            }
        }
    }
}
