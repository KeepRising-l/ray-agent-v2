package cn.ray.agent.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 解析一次 Chat Completions SSE 数据，累计结束原因和可选用量。 */
public final class StreamAssembler {
    private final ObjectMapper mapper = new ObjectMapper();
    private StreamChunk.FinishReason finishReason;
    private TokenUsage usage;
    private boolean done;

    /** 接收一个完整 SSE data 字段，返回其中的文本或工具增量。 */
    public List<StreamChunk> accept(String data) throws IOException {
        if (done) throw new IOException("data received after stream completion");
        if ("[DONE]".equals(data)) {
            if (finishReason == null) throw new IOException("stream completed without finish reason");
            done = true;
            return List.of(new StreamChunk.Finished(finishReason, usage));
        }
        JsonNode root = mapper.readTree(data);
        if (root == null || !root.isObject()) throw new IOException("invalid stream chunk");
        if (root.hasNonNull("error")) throw new IOException("upstream returned a stream error");
        JsonNode u = root.path("usage");
        if (u.isObject()) usage = new TokenUsage(optionalInteger(u, "prompt_tokens"), optionalInteger(u, "completion_tokens"));
        List<StreamChunk> result = new ArrayList<>();
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) throw new IOException("stream chunk is missing choices");
        for (JsonNode choice : choices) {
            if (choice.path("index").asInt(-1) != 0) throw new IOException("multiple choices are not supported");
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) result.add(new StreamChunk.TextDelta(content.asText()));
            JsonNode calls = delta.path("tool_calls");
            if (calls.isArray()) for (JsonNode call : calls) {
                JsonNode fn = call.path("function");
                String id = optionalText(call, "id");
                String name = optionalText(fn, "name");
                String args = optionalText(fn, "arguments");
                if (!call.path("index").canConvertToInt()) throw new IOException("tool call index missing");
                if (id != null || name != null || args != null) result.add(new StreamChunk.ToolCallDelta(call.path("index").asInt(), id, name, args));
            }
            if (choice.hasNonNull("finish_reason")) {
                if (finishReason != null) throw new IOException("duplicate finish reason");
                finishReason = switch (choice.path("finish_reason").asText()) {
                    case "stop" -> StreamChunk.FinishReason.STOP;
                    case "tool_calls" -> StreamChunk.FinishReason.TOOL_CALLS;
                    case "length" -> StreamChunk.FinishReason.LENGTH;
                    default -> StreamChunk.FinishReason.OTHER;
                };
            }
        }
        return result;
    }

    public boolean isDone() { return done; }
    private static String optionalText(JsonNode node, String key) throws IOException {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new IOException("invalid stream text field");
        return value.asText();
    }
    private static Integer optionalInteger(JsonNode node, String key) throws IOException {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) throw new IOException("invalid token usage");
        return value.asInt();
    }
}
