package cn.ray.agent.server.tools.web;

import java.net.*;
import java.util.Locale;

/** 抓取只允许公网 HTTP(S) 标准端口；检查所有 DNS 结果，而非仅检查域名字符串。 */
public final class AllowedUrlPolicy {
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;

    public AllowedUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    public static AllowedUrlPolicy configured(String dnsMode) {
        return switch (dnsMode) {
            case "system" -> new AllowedUrlPolicy();
            case "public-doh" -> new AllowedUrlPolicy(new PublicDnsResolver());
            default -> throw new IllegalArgumentException("unsupported web DNS mode");
        };
    }

    AllowedUrlPolicy(Resolver resolver) {
        this.resolver = resolver;
    }

    public URI parse(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (url.length() > 2048 || scheme == null
                    || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != (scheme.equalsIgnoreCase("https") ? 443 : 80))) {
                throw new IllegalArgumentException("public HTTP(S) URL required");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains("%") || host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".onion")) {
                throw new IllegalArgumentException("private host forbidden");
            }
            return uri;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("URL 不符合公网访问规则");
        }
    }

    public InetAddress[] resolve(URI uri) throws UnknownHostException {
        parse(uri.toString());
        InetAddress[] addresses = resolver.resolve(uri.getHost());
        if (addresses.length == 0) {
            throw new IllegalArgumentException("empty DNS response");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("非公网地址禁止访问");
            }
        }
        return addresses.clone();
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        int c = bytes[2] & 255;
        if (bytes.length == 4) {
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99)))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    && !(a == 203 && b == 0 && c == 113);
        }
        // IPv6 仅放行普通全球单播，拒绝隧道、文档网段与特殊用途地址。
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x02)
                && !(a == 0x20 && b == 0x01 && (c < 2 || (c == 0x0d && (bytes[3] & 255) == 0xb8)));
    }
}
