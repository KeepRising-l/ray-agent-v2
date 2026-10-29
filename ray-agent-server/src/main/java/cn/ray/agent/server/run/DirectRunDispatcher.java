package cn.ray.agent.server.run;

import cn.ray.agent.runtime.model.GenerateRequest;
import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.runtime.model.StreamChunk;
import cn.ray.agent.runtime.run.RunHandle;
import org.springframework.stereotype.Component;
import java.util.concurrent.Flow;

/** 复用模型适配器的异步执行；本层不再创建一套无界执行队列。 */
@Component
public class DirectRunDispatcher implements RunDispatcher {
    @Override
    public RunHandle dispatch(LlmAdapter adapter, GenerateRequest request, Flow.Subscriber<StreamChunk> subscriber) {
        RunHandle handle = new RunHandle();
        try {
            adapter.generate(request).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription value) {
                    handle.attach(value);
                    subscriber.onSubscribe(handle);
                }
                public void onNext(StreamChunk chunk) {
                    subscriber.onNext(chunk);
                }
                public void onError(Throwable error) {
                    subscriber.onError(error);
                }
                public void onComplete() {
                    subscriber.onComplete();
                }
            });
        } catch (RuntimeException e) {
            handle.cancel();
            subscriber.onError(e);
        }
        return handle;
    }
}
