package cn.ray.agent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 后端启动入口；业务能力会在后续增量中逐步装配。 */
@SpringBootApplication
public class RayAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RayAgentApplication.class, args);
    }
}
