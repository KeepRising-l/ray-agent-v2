package cn.ray.agent.runtime.tool;

import java.util.List;
import java.util.Objects;

/** 工具观察结果；失败同样回填模型，不能伪装成搜索成功。 */
public record ToolResult(boolean success, String code, String content, List<Source> sources) {
    public ToolResult {
        Objects.requireNonNull(code);
        Objects.requireNonNull(content);
        sources = List.copyOf(sources);
        if (!success && !sources.isEmpty()) {
            throw new IllegalArgumentException("failed tool cannot provide sources");
        }
        if (content.length() > 12000 || sources.size() > 5) {
            throw new IllegalArgumentException("tool result exceeds limit");
        }
    }

    public static ToolResult failure(String code, String message) {
        return new ToolResult(false, code, message, List.of());
    }

    /** id 由每次运行分配，搜索摘要和抓取正文在 kind 中区分。 */
    public record Source(String id, String title, String url, String kind) { }
}
