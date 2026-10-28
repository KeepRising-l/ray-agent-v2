package cn.ray.agent.runtime.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryTest {
    @Test
    void findsTheRegisteredAdapterWithoutCallingTheModel() {
        var registry = new ModelRegistry();
        LlmAdapter adapter = request -> subscriber -> { };
        registry.register("openai-compatible", adapter);
        assertSame(adapter, registry.getRequired("openai-compatible"));
    }

    @Test
    void duplicateRegistrationCannotReplaceTheOriginal() {
        var registry = new ModelRegistry();
        LlmAdapter first = request -> subscriber -> { };
        LlmAdapter second = request -> subscriber -> { };
        registry.register("channel", first);
        assertThrows(IllegalStateException.class, () -> registry.register("channel", second));
        assertSame(first, registry.getRequired("channel"));
    }

    @Test
    void rejectsMissingNamesAndAdapters() {
        var registry = new ModelRegistry();
        LlmAdapter adapter = request -> subscriber -> { };
        assertThrows(NullPointerException.class, () -> registry.register(null, adapter));
        assertThrows(IllegalArgumentException.class, () -> registry.register(" ", adapter));
        assertThrows(NullPointerException.class, () -> registry.register("channel", null));
        assertThrows(IllegalArgumentException.class, () -> registry.getRequired("missing"));
    }
}
