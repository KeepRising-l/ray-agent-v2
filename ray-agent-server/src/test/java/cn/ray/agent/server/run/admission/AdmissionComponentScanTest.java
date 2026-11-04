package cn.ray.agent.server.run.admission;

import cn.ray.agent.server.conversation.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class AdmissionComponentScanTest {
    @Test
    void springDiscoversMovedAdmissionComponents() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(ConversationRepository.class, () -> mock(ConversationRepository.class));
            context.scan("cn.ray.agent.server.run.admission");
            context.refresh();

            assertEquals(1, context.getBeansOfType(BoundedRunExecutor.class).size());
            assertEquals(1, context.getBeansOfType(RequestIdempotencyService.class).size());
            assertNotNull(context.getBean(IdempotencyAdvice.class));
        }
    }
}
