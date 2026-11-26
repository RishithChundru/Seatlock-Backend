package com.seatlock.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html. Click "Authorize" and paste the token from /api/auth/login to try protected calls. */
@Configuration
class OpenApiConfig {
    @Bean
    OpenAPI seatlockApi() {
        return new OpenAPI()
                .info(new Info().title("Seatlock API").version("1.0")
                        .description("Concurrency-safe event seat booking: Redis holds, optimistic locking, RabbitMQ ticketing, SSE seat maps."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
