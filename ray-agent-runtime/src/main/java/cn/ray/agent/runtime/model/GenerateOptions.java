package cn.ray.agent.runtime.model;

/** 模型生成选项；null 表示未指定，具体默认值由适配器或渠道配置决定。 */
public record GenerateOptions(String model, Double temperature, Integer maxOutputTokens) {
    public GenerateOptions {
        if (model != null && model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0)) {
            throw new IllegalArgumentException("temperature must be finite and non-negative");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        // 不假设所有供应商的 temperature 上限相同；适配时按实际协议校验。
    }
}
