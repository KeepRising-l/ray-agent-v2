package cn.ray.agent.runtime.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class ModelContractTest {
    @Test
    void requestKeepsSnapshotOfMessagesAndTools() {
        var messages = new ArrayList<>(List.of(new Message(Message.Role.USER, "你好")));
        var tools = new ArrayList<>(List.of(new ToolDefinition("search", "搜索网页", "{}")));
        var request = new GenerateRequest(messages, new GenerateOptions(null, 0.5, 100), tools);
        messages.clear();
        tools.clear();
        assertEquals("你好", request.messages().get(0).content());
        assertEquals("search", request.tools().get(0).name());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
        assertThrows(IllegalArgumentException.class, () -> new GenerateRequest(List.of()));
        assertThrows(NullPointerException.class, () -> new GenerateRequest(null));
    }

    @Test
    void toolCallsAndResultsRetainTheirConnection() {
        var call = new ToolCall("call-1", "search", "{\"query\":\"Java\"}");
        var assistant = new Message(Message.Role.ASSISTANT, null, List.of(call), null);
        var result = new Message(Message.Role.TOOL, "结果", List.of(), "call-1");
        assertEquals(assistant.toolCalls().get(0).id(), result.toolCallId());
        assertThrows(IllegalArgumentException.class, () -> new Message(Message.Role.USER, "你好", List.of(call), null));
        assertThrows(IllegalArgumentException.class, () -> new Message(Message.Role.TOOL, "结果", List.of(), null));
    }

    @Test
    void partialToolArgumentsCanBeJoinedBeforeExecution() {
        var first = new StreamChunk.ToolCallDelta(0, "call-1", "search", "{\"query\":");
        var second = new StreamChunk.ToolCallDelta(0, null, null, "\"Java\"}");
        assertEquals(first.index(), second.index());
        assertEquals("{\"query\":\"Java\"}", first.argumentsFragment() + second.argumentsFragment());
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk.ToolCallDelta(-1, null, null, "{}"));
    }

    @Test
    void simulatedAdapterDeliversTextAndFinishedEvent() {
        // 模拟适配器只演示调用契约，不连接真实模型。
        LlmAdapter adapter = request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final List<StreamChunk> chunks = List.of(
                    new StreamChunk.TextDelta("你好"),
                    new StreamChunk.Finished(StreamChunk.FinishReason.STOP, null));
            private int next;
            private boolean completed;
            @Override public void request(long demand) {
                if (completed) return;
                if (demand <= 0) {
                    completed = true;
                    subscriber.onError(new IllegalArgumentException("demand must be positive"));
                    return;
                }
                while (!completed && demand-- > 0 && next < chunks.size()) {
                    subscriber.onNext(chunks.get(next++));
                }
                if (!completed && next == chunks.size()) {
                    completed = true;
                    subscriber.onComplete();
                }
            }
            @Override public void cancel() { completed = true; }
        });
        var events = new ArrayList<StreamChunk>();
        var completed = new boolean[1];
        adapter.generate(new GenerateRequest(List.of(new Message(Message.Role.USER, "你好"))))
                .subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(2); }
                    @Override public void onNext(StreamChunk item) { events.add(item); }
                    @Override public void onError(Throwable throwable) { fail(throwable); }
                    @Override public void onComplete() { completed[0] = true; }
                });
        assertEquals(new StreamChunk.TextDelta("你好"), events.get(0));
        assertEquals(StreamChunk.FinishReason.STOP, ((StreamChunk.Finished) events.get(1)).reason());
        assertNull(((StreamChunk.Finished) events.get(1)).usage());
        assertTrue(completed[0]);
    }
}
