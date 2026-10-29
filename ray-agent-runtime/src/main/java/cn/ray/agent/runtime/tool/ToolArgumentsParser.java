package cn.ray.agent.runtime.tool;

import cn.ray.agent.runtime.model.ToolDefinition;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 首版只支持严格的 object/string Schema；注册时拒绝未实现的约束，避免假校验。 */
public final class ToolArgumentsParser {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonNode schema(ToolDefinition definition) {
        try {
            JsonNode schema = mapper.readTree(definition.parametersSchemaJson());
            if (!schema.isObject() || !"object".equals(schema.path("type").asText())
                    || !schema.path("additionalProperties").isBoolean()
                    || schema.path("additionalProperties").asBoolean()
                    || !schema.path("properties").isObject() || !schema.path("required").isArray()) {
                throw new IllegalArgumentException("strict object schema required");
            }
            schema.fieldNames().forEachRemaining(key -> {
                if (!java.util.Set.of("type", "properties", "required", "additionalProperties", "description").contains(key)) {
                    throw new IllegalArgumentException("unsupported schema keyword");
                }
            });
            for (JsonNode required : schema.path("required")) {
                if (!required.isTextual() || !schema.path("properties").has(required.asText())) {
                    throw new IllegalArgumentException("invalid required property");
                }
            }
            for (JsonNode property : schema.path("properties")) {
                if (!"string".equals(property.path("type").asText())
                        || !property.path("maxLength").isInt() || property.path("maxLength").asInt() < 1) {
                    throw new IllegalArgumentException("bounded string property required");
                }
                property.fieldNames().forEachRemaining(key -> {
                    if (!java.util.Set.of("type", "description", "maxLength").contains(key)) {
                        throw new IllegalArgumentException("unsupported property constraint");
                    }
                });
            }
            return schema;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalid schema JSON");
        }
    }

    public JsonNode parse(String json, JsonNode schema) {
        try {
            if (json == null || json.length() > 8192) {
                throw new IllegalArgumentException("arguments too large");
            }
            JsonNode value = mapper.readTree(json);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("arguments must be an object");
            }
            for (JsonNode field : schema.path("required")) {
                if (!value.has(field.asText())) {
                    throw new IllegalArgumentException("missing required argument");
                }
            }
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode rule = schema.path("properties").path(field.getKey());
                if (rule.isMissingNode() || !field.getValue().isTextual()
                        || field.getValue().asText().isBlank()
                        || field.getValue().asText().length() > rule.path("maxLength").asInt()) {
                    throw new IllegalArgumentException("invalid argument field");
                }
            }
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalid arguments JSON");
        }
    }
}
