package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.model.ToolDefinition;
import cn.ray.agent.runtime.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;

/** 单页抓取，最多跟随三跳；每跳重新校验，禁止把公网跳转当作访问内网的通行证。 */
public final class WebFetchTool implements Tool {
    private final WebTransport transport;
    private final AllowedUrlPolicy policy;
    private final WebContentExtractor extractor;

    public WebFetchTool(WebTransport transport, AllowedUrlPolicy policy, WebContentExtractor extractor) {
        this.transport = transport;
        this.policy = policy;
        this.extractor = extractor;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("web_fetch", "抓取一个公开 HTTP(S) 网页的静态正文，不支持登录页、PDF或执行网页脚本。",
                """
                {"type":"object","properties":{"url":{"type":"string","maxLength":2048,"description":"公开网页的完整URL"}},"required":["url"],"additionalProperties":false}
                """);
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) throws Exception {
        URI uri;
        try {
            uri = policy.parse(arguments.path("url").asText());
            for (int hop = 0; hop <= 3; hop++) {
                context.checkCancelled();
                policy.resolve(uri);
                var response = transport.exchange(uri, null, null, context);
                if (java.util.Set.of(301, 302, 303, 307, 308).contains(response.status())) {
                    if (hop == 3 || response.location() == null) {
                        return ToolResult.failure("REDIRECT_LIMIT", "网页重定向次数过多或缺少目标");
                    }
                    uri = policy.parse(uri.resolve(response.location()).toString());
                    continue;
                }
                if (response.status() != 200) {
                    return ToolResult.failure("FETCH_HTTP_ERROR", "网页返回 HTTP " + response.status());
                }
                var content = extractor.extract(response.body(), response.contentType(), uri.toString());
                if (content.text().isBlank()) {
                    return ToolResult.failure("EMPTY_CONTENT", "网页没有可提取的静态正文");
                }
                return new ToolResult(true, "OK", content.text(),
                        List.of(new ToolResult.Source("", content.title(), uri.toString(), "FETCH")));
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("URL_OR_CONTENT_REJECTED", "地址被公网策略拒绝，或不是支持的网页类型");
        }
        return ToolResult.failure("FETCH_FAILED", "网页抓取失败");
    }
}
