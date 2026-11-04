package cn.ray.agent.server.run;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunComponentScanTest {
    @Test
    void movedComponentsRemainDiscoverableFromServerRoot() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        Set<String> components = scanner.findCandidateComponents("cn.ray.agent.server").stream()
                .map(bean -> bean.getBeanClassName())
                .collect(Collectors.toSet());
        for (String component : Set.of(
                "cn.ray.agent.server.run.dispatch.QueuedRunDispatcher",
                "cn.ray.agent.server.run.dispatch.TaskStateRepository",
                "cn.ray.agent.server.run.question.PendingQuestionStore",
                "cn.ray.agent.server.run.question.RunResumeService",
                "cn.ray.agent.server.run.api.RunQueryController",
                "cn.ray.agent.server.run.api.RunRecoveryController",
                "cn.ray.agent.server.run.api.UserAnswerController",
                "cn.ray.agent.server.rag.ingest.RetryScheduler")) {
            assertTrue(components.contains(component), "Spring 未发现组件：" + component);
        }
    }
}
