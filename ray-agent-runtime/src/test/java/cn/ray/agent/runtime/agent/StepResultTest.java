package cn.ray.agent.runtime.agent;

import cn.ray.agent.runtime.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepResultTest {
    @Test
    void interleavedCallsAreAssembledByIndexInOrder() {
        var result = new StepResult();
        result.accept(new StreamChunk.ToolCallDelta(1, "b", "web_", "{\"url\":"));
        result.accept(new StreamChunk.ToolCallDelta(0, "a", "web_search", "{\"query\":\"hi\"}"));
        result.accept(new StreamChunk.ToolCallDelta(1, null, "fetch", "\"https://example.com\"}"));
        result.accept(new StreamChunk.Finished(StreamChunk.FinishReason.TOOL_CALLS, null));
        var calls = result.message().toolCalls();
        assertEquals("a", calls.get(0).id());
        assertEquals("b", calls.get(1).id());
        assertEquals("web_fetch", calls.get(1).name());
        assertEquals("{\"url\":\"https://example.com\"}", calls.get(1).argumentsJson());
    }

    @Test
    void incompleteDuplicateAndTruncatedCallsCannotExecute() {
        var incomplete = new StepResult();
        incomplete.accept(new StreamChunk.ToolCallDelta(0, "a", "tool", "{}"));
        assertThrows(IllegalStateException.class, incomplete::message);
        incomplete.accept(new StreamChunk.Finished(StreamChunk.FinishReason.LENGTH, null));
        assertThrows(IllegalStateException.class, incomplete::message);
        var duplicate = new StepResult();
        duplicate.accept(new StreamChunk.ToolCallDelta(0, "a", "tool", "{}"));
        duplicate.accept(new StreamChunk.ToolCallDelta(1, "a", "tool", "{}"));
        duplicate.accept(new StreamChunk.Finished(StreamChunk.FinishReason.TOOL_CALLS, null));
        assertThrows(IllegalStateException.class, duplicate::message);
    }

    @Test
    void boundsToolFragmentsAndRejectsEventsAfterFinish() {
        var large = new StepResult();
        assertThrows(IllegalStateException.class, () -> large.accept(new StreamChunk.ToolCallDelta(8, "a", "tool", "{}")));
        assertThrows(IllegalStateException.class, () -> large.accept(new StreamChunk.ToolCallDelta(0, "a", "tool", "x".repeat(8193))));
        var finished = new StepResult();
        finished.accept(new StreamChunk.TextDelta("done"));
        finished.accept(new StreamChunk.Finished(StreamChunk.FinishReason.STOP, null));
        assertThrows(IllegalStateException.class, () -> finished.accept(new StreamChunk.TextDelta("late")));
    }
}
