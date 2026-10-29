package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AllowedUrlPolicy publicDns = new AllowedUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});

    @Test
    void forbidsPrivateSpecialAndMixedDnsAddresses() throws Exception {
        var policy = new AllowedUrlPolicy();
        for (String host : java.util.List.of("127.0.0.1", "127.1", "2130706433", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "224.0.0.1", "[::1]", "[fc00::1]", "[::ffff:127.0.0.1]", "[2001:db8::1]", "[2002:7f00:1::1]")) {
            assertThrows(IllegalArgumentException.class, () -> policy.resolve(policy.parse("http://" + host + "/")), host);
        }
        for (String url : java.util.List.of("file:///etc/passwd", "http://user:password@example.com", "http://localhost/", "http://example.com:8080/", "https://example.com/#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> policy.parse(url));
        }
        assertTrue(AllowedUrlPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(AllowedUrlPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
        var mixed = new AllowedUrlPolicy(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});
        assertThrows(IllegalArgumentException.class, () -> mixed.resolve(mixed.parse("https://example.com/")));
    }

    @Test
    void redirectsAreRecheckedAndForbiddenDestinationIsNeverRequested() throws Exception {
        var requests = new AtomicInteger();
        WebTransport transport = (uri, body, key, context) -> {
            requests.incrementAndGet();
            return new WebTransport.Response(302, "text/html", "http://127.0.0.1/secret", new byte[0]);
        };
        var dns = new AllowedUrlPolicy(host -> new InetAddress[]{InetAddress.getByName(host.equals("127.0.0.1") ? "127.0.0.1" : "8.8.8.8")});
        var tool = new WebFetchTool(transport, dns, new WebContentExtractor());
        var result = tool.execute(mapper.readTree("{\"url\":\"https://example.com/start\"}"), new ToolContext());
        assertFalse(result.success());
        assertTrue(result.sources().isEmpty());
        assertEquals(1, requests.get());
    }

    @Test
    void publicRedirectExtractsCleanTextAndFinalSource() throws Exception {
        WebTransport transport = (uri, body, key, context) -> uri.getPath().equals("/start")
                ? new WebTransport.Response(302, "text/html", "/end", new byte[0])
                : new WebTransport.Response(200, "text/html;charset=UTF-8", null,
                "<title>测试页</title><nav>导航</nav><main>正文 Pine42<script>steal()</script><style>.x{}</style></main>".getBytes(StandardCharsets.UTF_8));
        var result = new WebFetchTool(transport, publicDns, new WebContentExtractor())
                .execute(mapper.readTree("{\"url\":\"https://example.com/start\"}"), new ToolContext());
        assertTrue(result.success());
        assertEquals("正文 Pine42", result.content());
        assertEquals("https://example.com/end", result.sources().get(0).url());
        assertEquals("FETCH", result.sources().get(0).kind());
    }

    @Test
    void emptyBinaryHttpErrorAndRedirectLoopAreFailures() throws Exception {
        for (var response : java.util.List.of(
                new WebTransport.Response(200, "text/html", null, "<script>hi</script>".getBytes()),
                new WebTransport.Response(200, "application/pdf", null, "binary".getBytes()),
                new WebTransport.Response(403, "text/html", null, "private".getBytes()),
                new WebTransport.Response(302, "text/html", "/loop", new byte[0]))) {
            var tool = new WebFetchTool((uri, body, key, context) -> response, publicDns, new WebContentExtractor());
            var result = tool.execute(mapper.readTree("{\"url\":\"https://example.com/\"}"), new ToolContext());
            assertFalse(result.success());
            assertTrue(result.sources().isEmpty());
        }
    }

    @Test
    void searchUsesBearerAndReturnsSnippetsWithoutGeneratedAnswer() throws Exception {
        WebTransport transport = (uri, body, key, context) -> {
            assertEquals("https://api.tavily.com/search", uri.toString());
            assertEquals("test-only-key", key);
            var request = mapper.readTree(body);
            assertFalse(request.path("include_answer").asBoolean());
            assertEquals(3, request.path("max_results").asInt());
            assertEquals("测试", request.path("query").asText());
            return new WebTransport.Response(200, "application/json", null, """
                    {"answer":"do-not-use-this-answer","results":[{"title":"来源","url":"https://example.com/page","content":"来源摘要 Pine42"}]}
                    """.getBytes(StandardCharsets.UTF_8));
        };
        var result = new WebSearchTool("test-only-key", transport, publicDns)
                .execute(mapper.readTree("{\"query\":\"测试\"}"), new ToolContext());
        assertTrue(result.success());
        assertTrue(result.content().contains("Pine42"));
        assertFalse(result.content().contains("do-not-use"));
        assertEquals("SEARCH", result.sources().get(0).kind());
    }

    @Test
    void missingKeyEmptySearchAndHttpErrorDoNotCreateSources() throws Exception {
        for (String body : java.util.List.of("{\"results\":[]}", "{}")) {
            var tool = new WebSearchTool("test-only", (uri, json, key, context) -> new WebTransport.Response(200, "application/json", null, body.getBytes()), publicDns);
            assertFalse(tool.execute(mapper.readTree("{\"query\":\"test\"}"), new ToolContext()).success());
        }
        var missing = new WebSearchTool("", (uri, json, key, context) -> { throw new AssertionError("must not request"); }, publicDns);
        assertEquals("NOT_CONFIGURED", missing.execute(mapper.readTree("{\"query\":\"test\"}"), new ToolContext()).code());
        var error = new WebSearchTool("test-only", (uri, json, key, context) -> new WebTransport.Response(401, "application/json", null, new byte[0]), publicDns);
        var result = error.execute(mapper.readTree("{\"query\":\"test\"}"), new ToolContext());
        assertFalse(result.success());
        assertTrue(result.sources().isEmpty());
    }
}
