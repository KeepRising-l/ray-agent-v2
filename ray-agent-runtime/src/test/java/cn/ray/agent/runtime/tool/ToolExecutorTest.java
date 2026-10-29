package cn.ray.agent.runtime.tool;

import cn.ray.agent.runtime.model.ToolCall;
import cn.ray.agent.runtime.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {
    private static final String SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","maxLength":10}},"required":["query"],"additionalProperties":false}
            """;

    private Tool tool(AtomicInteger executions) {
        return new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("test", "test", SCHEMA);
            }

            public ToolResult execute(JsonNode arguments, ToolContext context) throws Exception {
                executions.incrementAndGet();
                if (arguments.path("query").asText().equals("error")) {
                    throw new IllegalStateException("private-token-must-not-leak");
                }
                if (arguments.path("query").asText().equals("slow")) {
                    Thread.sleep(5000);
                }
                return new ToolResult(true, "OK", "observed", List.of());
            }
        };
    }

    @Test
    void enforcesAllowlistSchemaAndRegistrationBeforeExecuting() {
        var registry = new ToolRegistry();
        var executions = new AtomicInteger();
        registry.register(tool(executions));
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool(executions)));
        assertTrue(registry.definitions(Set.of()).isEmpty());
        try (var executor = new ToolExecutor(registry)) {
            assertEquals("FORBIDDEN", executor.execute(new ToolCall("a", "test", "{\"query\":\"ok\"}"), Set.of(), new ToolContext(), 1000).code());
            assertEquals("UNKNOWN_TOOL", executor.execute(new ToolCall("a", "unknown", "{}"), Set.of("unknown"), new ToolContext(), 1000).code());
            for (String bad : List.of("{}", "[]", "{\"query\":null}", "{\"query\":42}", "{\"query\":\" \"}",
                    "{\"query\":\"ok\",\"extra\":1}", "{\"query\":\"ok\"} {}", "{\"query\":\"a\",\"query\":\"b\"}", "{\"query\":\"12345678901\"}")) {
                assertEquals("INVALID_ARGUMENTS", executor.execute(new ToolCall("a", "test", bad), Set.of("test"), new ToolContext(), 1000).code(), bad);
            }
            assertEquals(0, executions.get());
            assertTrue(executor.execute(new ToolCall("a", "test", "{\"query\":\"ok\"}"), Set.of("test"), new ToolContext(), 1000).success());
            assertEquals(1, executions.get());
        }
    }

    @Test
    void normalizesFailuresAndTimesOutWithoutLeakingException() {
        var registry = new ToolRegistry();
        registry.register(tool(new AtomicInteger()));
        try (var executor = new ToolExecutor(registry)) {
            var failed = executor.execute(new ToolCall("a", "test", "{\"query\":\"error\"}"), Set.of("test"), new ToolContext(), 1000);
            assertFalse(failed.success());
            assertFalse(failed.content().contains("private-token"));
            assertEquals("TIMEOUT", executor.execute(new ToolCall("b", "test", "{\"query\":\"slow\"}"), Set.of("test"), new ToolContext(), 30).code());
        }
    }

    @Test
    void cancellationClosesActiveToolResource() throws Exception {
        var registry = new ToolRegistry();
        var started = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        registry.register(new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("test", "test", SCHEMA);
            }
            public ToolResult execute(JsonNode arguments, ToolContext context) throws Exception {
                try (var ignored = context.onCancel(closed::countDown)) {
                    started.countDown();
                    Thread.sleep(5000);
                    return ToolResult.failure("UNREACHABLE", "unreachable");
                }
            }
        });
        var caller = Executors.newSingleThreadExecutor();
        try (var executor = new ToolExecutor(registry)) {
            var run = new ToolContext();
            var future = caller.submit(() -> executor.execute(new ToolCall("a", "test", "{\"query\":\"slow\"}"), Set.of("test"), run, 5000));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            run.cancel();
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        } finally {
            caller.shutdownNow();
        }
    }
}
