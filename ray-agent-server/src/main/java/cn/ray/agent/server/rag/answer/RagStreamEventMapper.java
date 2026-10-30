package cn.ray.agent.server.rag.answer;

import cn.ray.agent.server.conversation.ConversationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 保存有限检索元数据，再转发SSE；正文仅进入当前模型上下文，点引用时重新读取。 */
@Component
public class RagStreamEventMapper {
    private final ConversationRepository store;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagStreamEventMapper(ConversationRepository store) {
        this.store = store;
    }

    public ConversationRepository.Event save(String owner, String run, Event event) {
        try {
            return store.appendEvent(owner, run, event.type(), mapper.writeValueAsString(event.data()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot serialize RAG event");
        }
    }

    public record Event(String type, Map<String, Object> data) { }
}
