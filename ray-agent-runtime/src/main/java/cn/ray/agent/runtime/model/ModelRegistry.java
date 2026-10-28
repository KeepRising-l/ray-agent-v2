package cn.ray.agent.runtime.model;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 按配置的适配器名称登记和查找模型调用实现，不负责选择对话模式。 */
public final class ModelRegistry {

    /** 名称到适配器的映射；并发注册时不允许同名覆盖。 */
    private final ConcurrentMap<String, LlmAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * 注册一个适配器。名称是本系统的配置标识，不代表上游真实模型身份。
     * @param name 适配器名称，区分大小写，不能为 null 或空白
     * @param adapter 执行模型调用的适配器，不能为 null
     * @throws IllegalStateException 同名适配器已存在时拒绝覆盖
     */
    public void register(String name, LlmAdapter adapter) {
        validateName(name);
        Objects.requireNonNull(adapter, "adapter must not be null");
        if (adapters.putIfAbsent(name, adapter) != null) {
            throw new IllegalStateException("adapter already registered: " + name);
        }
    }

    /**
     * 查找已经注册的适配器；未找到时直接报错，避免悄悄调用错误渠道。
     * @param name 注册时使用的适配器名称
     * @return 对应的模型适配器
     * @throws IllegalArgumentException 名称无效或没有对应适配器
     */
    public LlmAdapter getRequired(String name) {
        validateName(name);
        LlmAdapter adapter = adapters.get(name);
        if (adapter == null) {
            throw new IllegalArgumentException("unknown adapter: " + name);
        }
        return adapter;
    }

    //检查名称是否合法
    private static void validateName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
