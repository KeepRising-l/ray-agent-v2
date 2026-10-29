package cn.ray.agent.server.model;

import cn.ray.agent.runtime.model.ModelRegistry;
import cn.ray.agent.runtime.model.OpenAiCompatibleAdapter;
import cn.ray.agent.runtime.model.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ModelChannelServiceTest {
    @Test
    void selectsConfiguredChannelWithoutExposingCredentials() {
        var registry = new ModelRegistry();
        var beans = new DefaultListableBeanFactory();
        try (var primary = new OpenAiCompatibleAdapter(URI.create("http://127.0.0.1:1234/v1/chat/completions"),
                "test-key", "model-a", new RetryPolicy(1, Duration.ZERO))) {
            beans.registerSingleton("primary", primary);
            registry.register("openai-compatible", primary);
            var env = new MockEnvironment()
                    .withProperty("ray.model.base-url", "http://127.0.0.1:1234/v1")
                    .withProperty("ray.model.api-key", "test-key")
                    .withProperty("ray.model.model", "model-a")
                    .withProperty("ray.model.extra-channels", "second")
                    .withProperty("ray.model.channel.second.model", "model-b");
            try (var service = new ModelChannelService(registry, beans.getBeanProvider(OpenAiCompatibleAdapter.class), env)) {
                assertEquals(2, service.list().size());
                assertEquals("openai-compatible", service.select(null).id());
                assertEquals("model-b", service.select("second").model());
                assertNotSame(service.adapter(service.select("second")), primary);
                assertThrows(ResponseStatusException.class, () -> service.select("unlisted"));
                assertFalse(service.list().toString().contains("test-key"));
            }
        }
    }

    @Test
    void reportsMissingConfigurationAndRejectsDuplicateNames() {
        var beans = new DefaultListableBeanFactory();
        try (var service = new ModelChannelService(new ModelRegistry(),
                beans.getBeanProvider(OpenAiCompatibleAdapter.class), new MockEnvironment())) {
            assertTrue(service.list().isEmpty());
            assertThrows(ResponseStatusException.class, () -> service.select(null));
        }
        var env = new MockEnvironment()
                .withProperty("ray.model.extra-channels", "same,same")
                .withProperty("ray.model.base-url", "http://127.0.0.1:1234/v1")
                .withProperty("ray.model.api-key", "test-key")
                .withProperty("ray.model.channel.same.model", "model-a");
        assertThrows(IllegalArgumentException.class,
                () -> new ModelChannelService(new ModelRegistry(), beans.getBeanProvider(OpenAiCompatibleAdapter.class), env));
    }
}
