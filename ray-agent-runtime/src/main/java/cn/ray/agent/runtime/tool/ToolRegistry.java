package cn.ray.agent.runtime.tool;

import cn.ray.agent.runtime.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 工具只有登记后才能执行；每次运行还需传入自己的允许名单。 */
public final class ToolRegistry {
    private final ConcurrentHashMap<String, Entry> tools = new ConcurrentHashMap<>();
    private final ToolArgumentsParser parser = new ToolArgumentsParser();

    public void register(Tool tool) {
        ToolDefinition definition = tool.definition();
        if (!definition.name().matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid tool name");
        }
        Entry entry = new Entry(tool, definition, parser.schema(definition));
        if (tools.putIfAbsent(definition.name(), entry) != null) {
            throw new IllegalArgumentException("duplicate tool name");
        }
    }

    public List<ToolDefinition> definitions(Set<String> allowed) {
        return tools.values().stream().filter(entry -> allowed.contains(entry.definition().name()))
                .map(Entry::definition).sorted(java.util.Comparator.comparing(ToolDefinition::name)).toList();
    }

    Entry find(String name) {
        return tools.get(name);
    }

    record Entry(Tool tool, ToolDefinition definition, JsonNode schema) { }
}
