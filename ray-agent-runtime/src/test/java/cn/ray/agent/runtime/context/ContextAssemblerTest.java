package cn.ray.agent.runtime.context;

import cn.ray.agent.runtime.model.Message;
import cn.ray.agent.runtime.model.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {
    @Test void keepsSystemAndNewestWholeTurn() {
        var system = new Message(Message.Role.SYSTEM, "rules");
        var user = new Message(Message.Role.USER, "latest");
        var history = List.of(system, new Message(Message.Role.USER, "x".repeat(200)),
                new Message(Message.Role.ASSISTANT, "old"), user);
        var result = new ContextAssembler().assemble(history, new ContextBudget(50, 10));
        assertEquals(List.of(system, user), result.messages());
        assertEquals(1, result.omittedTurns());
        assertThrows(IllegalArgumentException.class,
                () -> new ContextAssembler().assemble(history, new ContextBudget(10, 10)));
    }

    @Test void keepsToolCallAndResultTogether() {
        var call = new Message(Message.Role.ASSISTANT, null,
                List.of(new ToolCall("c1", "search", "{}")), null);
        var result = new Message(Message.Role.TOOL, "result", List.of(), "c1");
        var history = List.of(new Message(Message.Role.USER, "old"), call, result,
                new Message(Message.Role.ASSISTANT, "answer"), new Message(Message.Role.USER, "latest"));
        var selected = new ContextAssembler().assemble(history, new ContextBudget(30, 10));
        assertEquals(1, selected.messages().size());
        assertEquals("latest", selected.messages().get(0).content());
        assertThrows(IllegalArgumentException.class, () -> new ContextAssembler().assemble(
                List.of(new Message(Message.Role.USER, "old"), call), new ContextBudget(1000, 10)));
    }
}
