package cn.ray.agent.server.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;

/** 可选的固定 Cloudflare DoH 解析，适配本机代理 Fake-IP；结果仍需通过公网策略检查。 */
final class PublicDnsResolver implements AllowedUrlPolicy.Resolver {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        // IP 字面量不送 DNS；仅接受十进制 IPv4 和 Java 能解析的 IPv6 字面量。
        if (host.matches("[0-9.]+") || host.contains(":")) {
            return new InetAddress[]{InetAddress.getByName(host)};
        }
        try {
            String name = URLEncoder.encode(host, java.nio.charset.StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create("https://1.1.1.1/dns-query?name=" + name + "&type=A"))
                    .timeout(Duration.ofSeconds(4)).header("Accept", "application/dns-json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 16384) {
                throw new IllegalStateException("public DNS unavailable");
            }
            var data = mapper.readTree(response.body());
            if (data.path("Status").asInt(-1) != 0) {
                throw new IllegalStateException("public DNS returned an error");
            }
            var addresses = new ArrayList<InetAddress>();
            for (var answer : data.path("Answer")) {
                String ip = answer.path("data").asText();
                if (answer.path("type").asInt() == 1 && ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                    addresses.add(InetAddress.getByName(ip));
                }
            }
            if (addresses.isEmpty()) {
                throw new IllegalStateException("public DNS has no IPv4 answer");
            }
            return addresses.toArray(InetAddress[]::new);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UnknownHostException("公网 DNS 解析失败");
        }
    }
}
