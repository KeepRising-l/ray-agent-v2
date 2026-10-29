package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.tool.ToolContext;
import java.net.URI;

/** 可替换的网络边界用于模拟超时、重定向等测试；生产实现固定为公网校验传输。 */
@FunctionalInterface
public interface WebTransport {
    Response exchange(URI uri, String jsonBody, String bearer, ToolContext context) throws Exception;

    record Response(int status, String contentType, String location, byte[] body) { }
}
