package cn.ray.agent.server.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseService {
    private final DocumentRepository store;

    public KnowledgeBaseService(DocumentRepository store) {
        this.store = store;
    }

    public DocumentRepository.KnowledgeBase create(String owner, String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库名称不能为空、含控制字符或超过 100 字符");
        }
        return store.createBase(owner, name.strip());
    }
}
