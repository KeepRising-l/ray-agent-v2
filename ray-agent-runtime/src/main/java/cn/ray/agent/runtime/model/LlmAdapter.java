package cn.ray.agent.runtime.model;

import java.util.concurrent.Flow;

/** 模型供应商适配边界；实现负责把供应商协议转换为统一事件。 */
public interface LlmAdapter {
    /**
     * 创建一次生成的事件流。
     * 每次调用返回独立流；订阅后按需发送事件，正常结束先发送一个 Finished 再 onComplete，
     * 失败使用 onError；取消订阅后应停止下游工作。具体适配器负责兑现这些约定。
     * @param request 本次模型输入和允许提供给模型的工具
     * @return 不包含真实实现的流式契约
     */
    Flow.Publisher<StreamChunk> generate(GenerateRequest request);
}
