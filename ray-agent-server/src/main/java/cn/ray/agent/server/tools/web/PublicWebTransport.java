package cn.ray.agent.server.tools.web;

import cn.ray.agent.runtime.tool.ToolContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.*;

/** 每跳解析并固定 IP，保留原域名进行 TLS 证书验证；不使用系统代理、Cookie 或自动重试。 */
public final class PublicWebTransport implements WebTransport {
    private final AllowedUrlPolicy policy;

    public PublicWebTransport(AllowedUrlPolicy policy) {
        this.policy = policy;
    }

    @Override
    public Response exchange(URI uri, String jsonBody, String bearer, ToolContext context) throws Exception {
        context.checkCancelled();
        InetAddress[] pinned = policy.resolve(uri);
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!host.equalsIgnoreCase(uri.getHost())) {
                    throw new UnknownHostException("unexpected host");
                }
                return pinned.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
        try (var client = HttpClients.custom().setConnectionManager(connections)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2)).setResponseTimeout(Timeout.ofSeconds(10)).build()).build()) {
            HttpUriRequestBase request = new HttpUriRequestBase(jsonBody == null ? "GET" : "POST", uri);
            request.setHeader("User-Agent", "RayAgent/0.1 (personal web reader)");
            request.setHeader("Accept", jsonBody == null ? "text/html,text/plain" : "application/json");
            if (jsonBody != null) {
                request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
            }
            if (bearer != null) {
                request.setHeader("Authorization", "Bearer " + bearer);
            }
            try (var ignored = context.onCancel(request::cancel)) {
                context.checkCancelled();
                return client.execute(request, response -> {
                    int status = response.getCode();
                    String location = response.getFirstHeader("Location") == null ? null : response.getFirstHeader("Location").getValue();
                    String contentType = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue();
                    var entity = response.getEntity();
                    // 错误与重定向不读取任意正文，也不把供应商错误详情（可能含凭据）传给上层。
                    if (status != 200 || entity == null) {
                        request.cancel();
                        return new Response(status, contentType, location, new byte[0]);
                    }
                    var encoding = response.getFirstHeader("Content-Encoding");
                    if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity")) {
                        request.cancel();
                        throw new IOException("unexpected compressed response");
                    }
                    if (entity.getContentLength() > 1_000_000) {
                        request.cancel();
                        throw new IOException("response too large");
                    }
                    try (var input = entity.getContent()) {
                        byte[] body = input.readNBytes(1_000_001);
                        context.checkCancelled();
                        if (body.length > 1_000_000) {
                            request.cancel();
                            throw new IOException("response too large");
                        }
                        return new Response(status, contentType, location, body);
                    }
                });
            }
        }
    }
}
