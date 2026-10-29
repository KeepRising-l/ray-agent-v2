package cn.ray.agent.server.tools.web;

import org.jsoup.Jsoup;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** 只提取静态 HTML/TXT；脚本不执行，脚本、样式、导航和表单不进入模型上下文。 */
public final class WebContentExtractor {
    public Content extract(byte[] bytes, String contentType, String url) throws java.io.IOException {
        String type = contentType.toLowerCase(Locale.ROOT);
        String charset = null;
        var match = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (match.find()) {
            charset = match.group(1);
        }
        if (type.startsWith("text/plain")) {
            String text = new String(bytes, charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset)).strip();
            return new Content(url, limit(text, 5000));
        }
        if (!type.startsWith("text/html") && !type.startsWith("application/xhtml+xml")) {
            throw new IllegalArgumentException("仅支持 HTML 和纯文本网页");
        }
        var document = Jsoup.parse(new ByteArrayInputStream(bytes), charset, url);
        document.select("script,style,noscript,nav,header,footer,form,svg,iframe").remove();
        var main = document.selectFirst("main,article");
        return new Content(limit(document.title(), 200), limit((main == null ? document.body() : main).text().strip(), 5000));
    }

    static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    public record Content(String title, String text) { }
}
