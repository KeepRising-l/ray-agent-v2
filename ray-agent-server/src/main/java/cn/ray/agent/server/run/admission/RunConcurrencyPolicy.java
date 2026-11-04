package cn.ray.agent.server.run.admission;

/** 全局执行槽、等候区和单owner占用分别限额；等待用户的运行不占执行槽。 */
public record RunConcurrencyPolicy(int running, int queued, int perOwner) {
    public RunConcurrencyPolicy {
        if (running < 1 || running > 4 || queued < 0 || queued > 100 || perOwner < 1 || perOwner > running + queued) {
            throw new IllegalArgumentException("invalid run concurrency policy");
        }
    }
}
