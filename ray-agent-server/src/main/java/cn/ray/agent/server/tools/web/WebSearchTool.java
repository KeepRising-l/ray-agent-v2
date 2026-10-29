package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.model.ToolDefinition;
import cn.ray.agent.runtime.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Tavily 搜索只取摘要和链接，不把供应商生成的 answer 冒充网页事实。 */
public final class WebSearchTool implements Tool {
    private final String apiKey;
    private final WebTransport transport;
    private final AllowedUrlPolicy policy;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSearchTool(String apiKey, WebTransport transport, AllowedUrlPolicy policy) {
        this.apiKey = apiKey;
        this.transport = transport;
        this.policy = policy;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("web_search", "搜索公开网页，返回摘要和来源；需要完整正文时再调用 web_fetch。不要搜索私密资料。",
                """
                {"type":"object","properties":{"query":{"type":"string","maxLength":300,"description":"搜索关键词"}},"required":["query"],"additionalProperties":false}
                """);
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            return ToolResult.failure("NOT_CONFIGURED", "网页搜索尚未配置 Tavily 密钥");
        }
        var body = mapper.createObjectNode().put("query", arguments.path("query").asText())
                .put("search_depth", "basic").put("max_results", 3)
                .put("include_answer", false).put("include_raw_content", false);
        var response = transport.exchange(URI.create("https://api.tavily.com/search"), body.toString(), apiKey, context);
        if (response.status() != 200) {
            return ToolResult.failure("SEARCH_HTTP_ERROR", "搜索服务返回 HTTP " + response.status());
        }
        var results = mapper.readTree(response.body()).path("results");
        if (!results.isArray()) {
            return ToolResult.failure("INVALID_RESPONSE", "搜索服务未返回有效结果列表");
        }
        List<ToolResult.Source> sources = new ArrayList<>();
        StringBuilder snippets = new StringBuilder();
        for (var item : results) {
            if (sources.size() == 3) {
                break;
            }
            String url = item.path("url").asText();
            String snippet = item.path("content").asText().strip();
            if (snippet.isBlank()) {
                continue;
            }
            try {
                policy.resolve(policy.parse(url));
            } catch (java.net.UnknownHostException | IllegalArgumentException e) {
                continue;
            }
            String title = WebContentExtractor.limit(item.path("title").asText(url), 200);
            sources.add(new ToolResult.Source("", title, url, "SEARCH"));
            snippets.append("网页摘要：").append(title).append("\nURL: ").append(url).append("\n")
                    .append(WebContentExtractor.limit(snippet, 900)).append("\n\n");
        }
        if (sources.isEmpty()) {
            return ToolResult.failure("NO_RESULTS", "没有找到可用的公开网页摘要");
        }
        return new ToolResult(true, "OK", snippets.toString(), sources);
    }
}
