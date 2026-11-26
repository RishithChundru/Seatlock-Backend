package com.seatlock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/** Real Postgres, Redis and RabbitMQ in Docker for tests: the locking behaviour is the database's, not H2's. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine"); }

    @Bean @ServiceConnection(name = "redis")
    GenericContainer<?> redis() { return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379); }

    @Bean @ServiceConnection
    RabbitMQContainer rabbit() { return new RabbitMQContainer("rabbitmq:4-management-alpine"); }
}
