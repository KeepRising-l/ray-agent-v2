package cn.ray.agent.server.model;

import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.runtime.model.ModelRegistry;
import cn.ray.agent.runtime.model.OpenAiCompatibleAdapter;
import cn.ray.agent.runtime.model.RetryPolicy;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 读取本机渠道配置；渠道名称是配置标识，不代表真实上游身份。 */
@Service
public class ModelChannelService implements AutoCloseable {
    private static final String DEFAULT_CHANNEL = "openai-compatible";
    private final ModelRegistry registry;
    private final Map<String, ChannelView> channels = new LinkedHashMap<>();
    private final List<OpenAiCompatibleAdapter> ownedAdapters = new ArrayList<>();
    private final Map<ChannelView,LlmAdapter> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private String defaultChannel = DEFAULT_CHANNEL;

    public ModelChannelService(ModelRegistry registry,
                               ObjectProvider<OpenAiCompatibleAdapter> defaultAdapter,
                               Environment env) {
        this.registry = registry;
        if (defaultAdapter.getIfAvailable() != null) {
            channels.put(DEFAULT_CHANNEL, new ChannelView(DEFAULT_CHANNEL,
                    env.getRequiredProperty("ray.model.model")));
        }
        String names = env.getProperty("ray.model.extra-channels", "");
        try {
            for (String raw : names.split(",")) {
                String name = raw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!name.matches("[a-z0-9-]{1,40}") || channels.containsKey(name)) {
                    throw new IllegalArgumentException("invalid or duplicate channel name");
                }
                String prefix = "ray.model.channel." + name + ".";
                String base = env.getProperty(prefix + "base-url", env.getProperty("ray.model.base-url"));
                String key = env.getProperty(prefix + "api-key", env.getProperty("ray.model.api-key"));
                String model = env.getRequiredProperty(prefix + "model");
                if (base == null || key == null) {
                    throw new IllegalArgumentException("channel base URL and API key are required");
                }
                OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(
                        URI.create(base.replaceAll("/+$", "") + "/chat/completions"),
                        key, model, new RetryPolicy(2, Duration.ofMillis(200)));
                ownedAdapters.add(adapter);
                registry.register(name, adapter);
                channels.put(name, new ChannelView(name, model));
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** 可给前端展示的字段；不包含密钥或服务地址。 */
    public synchronized List<ChannelView> list() {
        return channels.values().stream().sorted(java.util.Comparator.comparingInt(view->view.id().equals(defaultChannel)?0:1)).toList();
    }

    /** 未选择时使用默认渠道；不允许请求传入任意上游地址。 */
    public synchronized ChannelView select(String requested) {
        if (channels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型尚未配置");
        }
        String name = requested == null || requested.isBlank()
                ? (channels.containsKey(defaultChannel) ? defaultChannel : channels.keySet().iterator().next())
                : requested;
        ChannelView channel = channels.get(name);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知模型渠道");
        }
        return channel;
    }

    public LlmAdapter adapter(ChannelView channel) {
        var adapter = versions.get(channel);
        return adapter == null ? registry.getRequired(channel.id()) : adapter;
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        ownedAdapters.forEach(OpenAiCompatibleAdapter::close);
    }

    /** 新运行取得不可变版本；旧adapter保留到进程关闭，配置热改不会关闭正在输出的连接。 */
    public synchronized void install(List<RuntimeChannel> definitions,String selectedDefault) {
        var next = new LinkedHashMap<String,ChannelView>();
        for(var definition:definitions) {
            if(!definition.enabled() || definition.key().isBlank()) continue;
            var view=new ChannelView(definition.id(),definition.model(),definition.version());
            if(!versions.containsKey(view)) {
                var adapter=new OpenAiCompatibleAdapter(URI.create(definition.baseUrl()+"/chat/completions"),definition.key(),
                        definition.model(),new RetryPolicy(2,Duration.ofMillis(200)));
                ownedAdapters.add(adapter);versions.put(view,adapter);
            }
            next.put(view.id(),view);
        }
        channels.clear();channels.putAll(next);defaultChannel=selectedDefault;
    }
    public record RuntimeChannel(String id,String baseUrl,String model,String key,boolean enabled,String version) {
        @Override public String toString(){return "RuntimeChannel["+id+", credentials redacted]";}
    }
    public record ChannelView(String id, String model,String version) {
        public ChannelView(String id,String model){this(id,model,"bootstrap");}
    }
}
