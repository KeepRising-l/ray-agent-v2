package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 手动探测实际 Tavily 和公网正文；默认测试不消耗搜索额度。 */
@EnabledIfEnvironmentVariable(named = "RAY_WEB_TEST", matches = "true")
class LiveWebToolTest {
    @Test
    void searchAndFetchRealPublicSources() throws Exception {
        var policy = AllowedUrlPolicy.configured(System.getenv().getOrDefault("RAY_WEB_DNS", "system"));
        var transport = new PublicWebTransport(policy);
        var mapper = new ObjectMapper();
        var search = new WebSearchTool(System.getenv("RAY_WEB_API_KEY"), transport, policy)
                .execute(mapper.readTree("{\"query\":\"site:docs.tavily.com Tavily search API max_results\"}"), new ToolContext());
        assertTrue(search.success(), search.code() + ": " + search.content());
        assertFalse(search.sources().isEmpty());
        var fetch = new WebFetchTool(transport, policy, new WebContentExtractor())
                .execute(mapper.readTree("{\"url\":\"https://example.com/\"}"), new ToolContext());
        assertTrue(fetch.success(), fetch.code());
        assertTrue(fetch.content().contains("documentation examples"));
        assertEquals("Example Domain", fetch.sources().get(0).title());
        assertEquals("FETCH", fetch.sources().get(0).kind());
    }
}
