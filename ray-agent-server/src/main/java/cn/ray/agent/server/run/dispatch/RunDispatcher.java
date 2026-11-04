package cn.ray.agent.server.run.dispatch;

import cn.ray.agent.server.run.admission.BoundedRunExecutor;

import cn.ray.agent.runtime.model.GenerateRequest;
import cn.ray.agent.runtime.model.LlmAdapter;
import cn.ray.agent.runtime.model.StreamChunk;
import cn.ray.agent.runtime.run.RunHandle;
import java.util.concurrent.Flow;

/** 运行执行入口；当前直接订阅，后续入队时可以更换实现。 */
public interface RunDispatcher {
    RunHandle dispatch(Context context,LlmAdapter adapter, GenerateRequest request, Flow.Subscriber<StreamChunk> subscriber);

    record Context(String ownerId,String runId,BoundedRunExecutor.Permit permit,Runnable started) { }
}
