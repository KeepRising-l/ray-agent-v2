package cn.ray.agent.server.knowledge;

import org.springframework.stereotype.Service;

/** 数据库先停止检索，原文件删除通过持久补偿队列重试，不假装文件系统参与了 SQL 事务。 */
@Service
public class DocumentDeleteService {
    private final DocumentRepository store;
    private final DocumentFileStore files;

    public DocumentDeleteService(DocumentRepository store, DocumentFileStore files) {
        this.store = store;
        this.files = files;
    }

    public void delete(String owner, String kb, String doc) {
        store.delete(owner, kb, doc);
        cleanup();
    }

    public void cleanup() {
        for (String key : store.cleanupKeys()) {
            try {
                files.discard(key);
                store.cleanupFinished(key, true);
            } catch (java.io.IOException | IllegalArgumentException e) {
                store.cleanupFinished(key, false);
            }
        }
    }
}
