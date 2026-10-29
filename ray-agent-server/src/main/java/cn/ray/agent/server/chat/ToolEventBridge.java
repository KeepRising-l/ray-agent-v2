package cn.ray.agent.server.chat;

import cn.ray.agent.runtime.agent.AgentEventSink;
import cn.ray.agent.server.conversation.ConversationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 先保存结构化轨迹，再发送给浏览器；不记录密钥、搜索参数或网页全文。 */
public final class ToolEventBridge {
    private final ConversationRepository store;
    private final String owner;
    private final String runId;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolEventBridge(ConversationRepository store, String owner, String runId) {
        this.store = store;
        this.owner = owner;
        this.runId = runId;
    }

    public ConversationRepository.Event save(AgentEventSink.Event event) {
        try {
            return store.appendEvent(owner, runId, event.type(), mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize tool event");
        }
    }
}
