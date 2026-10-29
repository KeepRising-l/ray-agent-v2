package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.model.*;
import cn.ray.agent.runtime.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactLoopAgentTest {
    private final RunBudget budget = new RunBudget(3, 3, 3000, 1000, 16000);
    private final GenerateRequest input = new GenerateRequest(List.of(new Message(Message.Role.USER, "查资料")));

    private ToolRegistry registry(boolean fail) {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("lookup", "test", "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"maxLength\":20}},\"required\":[\"q\"],\"additionalProperties\":false}");
            }
            public ToolResult execute(JsonNode arguments, ToolContext context) {
                return fail ? ToolResult.failure("TEST_FAILED", "查找失败") : new ToolResult(true, "OK", "真实观察值 Pine42",
                        List.of(new ToolResult.Source("", "测试网页", "https://example.com/", "SEARCH")));
            }
        });
        return registry;
    }

    private LlmAdapter fake(java.util.function.Function<GenerateRequest, List<StreamChunk>> response) {
        return request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            boolean sent;
            public void request(long n) {
                if (sent) {
                    return;
                }
                sent = true;
                try {
                    response.apply(request).forEach(subscriber::onNext);
                    subscriber.onComplete();
                } catch (Throwable error) {
                    subscriber.onError(error);
                }
            }
            public void cancel() { }
        });
    }

    private List<StreamChunk> tool(String id) {
        return List.of(new StreamChunk.ToolCallDelta(0, id, "look", "{\"q\":"),
                new StreamChunk.ToolCallDelta(0, null, "up", "\"value\"}"),
                new StreamChunk.Finished(StreamChunk.FinishReason.TOOL_CALLS, new TokenUsage(10, 2)));
    }

    private List<StreamChunk> text(String text) {
        return List.of(new StreamChunk.TextDelta(text), new StreamChunk.Finished(StreamChunk.FinishReason.STOP, new TokenUsage(20, 4)));
    }

    @Test
    void toolObservationIsBackfilledBeforeSecondModelCallAndEventsAreReal() throws Exception {
        var registry = registry(false);
        var events = new CopyOnWriteArrayList<AgentEventSink.Event>();
        AtomicInteger calls = new AtomicInteger();
        LlmAdapter model = fake(request -> {
            assertEquals(1, request.tools().size());
            if (calls.incrementAndGet() == 1) {
                return tool("call-1");
            }
            assertEquals(Message.Role.ASSISTANT, request.messages().get(1).role());
            var observation = request.messages().get(2);
            assertEquals(Message.Role.TOOL, observation.role());
            assertEquals("call-1", observation.toolCallId());
            assertTrue(observation.content().contains("Pine42"));
            assertTrue(observation.content().contains("W1"));
            return text("结果 Pine42 [W1]");
        });
        try (var executor = new ToolExecutor(registry); var agent = new ReactLoopAgent(registry, executor)) {
            Probe probe = new Probe();
            agent.bind(model, Set.of("lookup"), budget, events::add).generate(input).subscribe(probe);
            probe.done.get(3, TimeUnit.SECONDS);
            assertNull(probe.error);
            assertEquals(2, calls.get());
            assertEquals(List.of("MODEL_STARTED", "MODEL_FINISHED", "TOOL_STARTED", "TOOL_SUCCEEDED", "MODEL_STARTED", "MODEL_FINISHED"), events.stream().map(AgentEventSink.Event::type).toList());
            var finish = (StreamChunk.Finished) probe.chunks.get(probe.chunks.size() - 1);
            assertEquals(new TokenUsage(30, 6), finish.usage());
        }
    }

    @Test
    void failedToolReturnsObservationWithoutInventedSources() throws Exception {
        var registry = registry(true);
        var events = new CopyOnWriteArrayList<AgentEventSink.Event>();
        var calls = new AtomicInteger();
        var model = fake(request -> {
            if (calls.incrementAndGet() == 1) {
                return tool("call-1");
            }
            assertTrue(request.messages().get(2).content().contains("TEST_FAILED"));
            return text("工具失败，目前无法确认。");
        });
        try (var executor = new ToolExecutor(registry); var agent = new ReactLoopAgent(registry, executor)) {
            Probe probe = new Probe();
            agent.bind(model, Set.of("lookup"), budget, events::add).generate(input).subscribe(probe);
            probe.done.get(3, TimeUnit.SECONDS);
            assertNull(probe.error);
            assertTrue(events.stream().anyMatch(event -> event.type().equals("TOOL_FAILED") && event.sources().isEmpty()));
            assertFalse(events.stream().anyMatch(event -> event.type().equals("TOOL_SUCCEEDED")));
        }
    }

    @Test
    void ordinaryChatCallsModelOnceAndUnknownUsageStaysUnknown() throws Exception {
        var registry = registry(false);
        var calls = new AtomicInteger();
        var model = fake(request -> {
            calls.incrementAndGet();
            return List.of(new StreamChunk.TextDelta("你好"), new StreamChunk.Finished(StreamChunk.FinishReason.STOP, null));
        });
        try (var executor = new ToolExecutor(registry); var agent = new ReactLoopAgent(registry, executor)) {
            Probe probe = new Probe();
            agent.bind(model, Set.of(), budget, event -> { }).generate(input).subscribe(probe);
            probe.done.get(3, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertNull(((StreamChunk.Finished) probe.chunks.get(1)).usage().inputTokens());
        }
    }

    @Test
    void budgetsStopRepeatedCallsAndRejectUnknownCitations() throws Exception {
        var registry = registry(false);
        try (var executor = new ToolExecutor(registry); var agent = new ReactLoopAgent(registry, executor)) {
            for (boolean citation : List.of(false, true)) {
                Probe probe = new Probe();
                var calls = new AtomicInteger();
                var model = fake(request -> citation ? text("编造来源 [W999]") : tool("call-" + calls.incrementAndGet()));
                agent.bind(model, Set.of("lookup"), new RunBudget(2, 1, 3000, 1000, 16000), event -> { }).generate(input).subscribe(probe);
                probe.done.get(3, TimeUnit.SECONDS);
                assertInstanceOf(ReactLoopAgent.Failure.class, probe.error);
                assertEquals(citation ? "INVALID_CITATION" : "BUDGET_EXCEEDED", ((ReactLoopAgent.Failure) probe.error).code());
                assertFalse(probe.chunks.stream().anyMatch(StreamChunk.Finished.class::isInstance));
            }
        }
    }

    @Test
    void cancelsCurrentModelAndDoesNotStartNextStep() throws Exception {
        var registry = registry(false);
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        LlmAdapter model = request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {
                started.countDown();
            }
            public void cancel() {
                cancelled.countDown();
            }
        });
        try (var executor = new ToolExecutor(registry); var agent = new ReactLoopAgent(registry, executor)) {
            Probe probe = new Probe();
            agent.bind(model, Set.of("lookup"), budget, event -> { }).generate(input).subscribe(probe);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            probe.subscription.cancel();
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertFalse(probe.done.isDone());
        }
    }

    @Test
    void modelTimeBudgetTerminatesIdleStream() throws Exception {
        var registry = registry(false);
        LlmAdapter model = request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { }
        });
        try (var executor = new ToolExecutor(registry); var agent = new ReactLoopAgent(registry, executor)) {
            Probe probe = new Probe();
            agent.bind(model, Set.of(), new RunBudget(2, 1, 100, 50, 16000), event -> { }).generate(input).subscribe(probe);
            probe.done.get(2, TimeUnit.SECONDS);
            assertEquals("TIMEOUT", ((ReactLoopAgent.Failure) probe.error).code());
        }
    }

    private static final class Probe implements Flow.Subscriber<StreamChunk> {
        private final List<StreamChunk> chunks = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile Throwable error;
        private Flow.Subscription subscription;
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }
        public void onNext(StreamChunk value) {
            chunks.add(value);
        }
        public void onError(Throwable value) {
            error = value;
            done.complete(null);
        }
        public void onComplete() {
            done.complete(null);
        }
    }
}
