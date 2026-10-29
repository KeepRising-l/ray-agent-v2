package cn.ray.agent.runtime.run;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RunHandleTest {
    @Test void cancellationBeforeSubscriptionIsRetained() {
        var handle = new RunHandle();
        var cancelled = new AtomicBoolean();
        handle.cancel();
        handle.attach(new Flow.Subscription() {
            public void request(long n) { fail("cancelled handle must not request data"); }
            public void cancel() { cancelled.set(true); }
        });
        handle.request(1);
        assertTrue(cancelled.get());
    }
}
