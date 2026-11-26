package com.seatlock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Real Postgres, Redis, RabbitMQ and an SMTP inbox (Mailpit) in Docker for tests. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine"); }

    @Bean @ServiceConnection(name = "redis")
    GenericContainer<?> redis() { return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379); }

    @Bean @ServiceConnection
    RabbitMQContainer rabbit() { return new RabbitMQContainer("rabbitmq:4-management-alpine"); }

    @Bean
    GenericContainer<?> mailpit() { return new GenericContainer<>("axllent/mailpit:latest").withExposedPorts(1025, 8025); }

    @Bean
    DynamicPropertyRegistrar mailProperties(GenericContainer<?> mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
            registry.add("test.mailpit.api", () -> "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025));
        };
    }
}
