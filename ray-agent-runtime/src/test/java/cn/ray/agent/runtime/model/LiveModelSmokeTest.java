package cn.ray.agent.runtime.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 仅手动启用的真实接口探测；不输出凭据或完整响应。 */
@EnabledIfEnvironmentVariable(named = "RAY_LIVE_TEST", matches = "true")
class LiveModelSmokeTest {
    @Test void returnsTextFromConfiguredModel() throws Exception {
        var endpoint = URI.create(System.getenv("RAY_MODEL_BASE_URL").replaceAll("/+$", "") + "/chat/completions");
        var model = System.getenv("RAY_MODEL_MODEL");
        var key = System.getenv("RAY_MODEL_API_KEY");
        try (var adapter = new OpenAiCompatibleAdapter(endpoint, key, model, new RetryPolicy(1, Duration.ZERO))) {
            var done = new CountDownLatch(1); var text = new StringBuilder();
            var error = new AtomicReference<Throwable>(); var finished = new AtomicReference<StreamChunk.Finished>();
            var input = new GenerateRequest(List.of(new Message(Message.Role.USER, "Reply OK.")), new GenerateOptions(null, null, 64), List.of());
            adapter.generate(input).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(StreamChunk item) { if (item instanceof StreamChunk.TextDelta t) text.append(t.text()); if (item instanceof StreamChunk.Finished f) finished.set(f); }
                public void onError(Throwable t) { error.set(t); done.countDown(); }
                public void onComplete() { done.countDown(); }
            });
            assertTrue(done.await(50, TimeUnit.SECONDS), "model timed out");
            assertNull(error.get(), "model request failed");
            assertNotNull(finished.get(), "missing finish event");
            assertFalse(text.toString().isBlank(), "model returned no text");
        }
    }
}
