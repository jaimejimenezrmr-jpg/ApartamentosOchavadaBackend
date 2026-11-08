package com.ochavada.backend.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de los tests de integración: contexto Spring completo + MockMvc + PostgreSQL 16 embebido,
 * con el esquema creado por Flyway exactamente igual que en producción.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestDoublesConfig.class)
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
        registry.add("app.ttlock.base-url", () -> FakeTtlockServer.get().baseUrl());
        registry.add("app.ses.endpoint", () -> FakeSesServer.get().endpoint());
        registry.add("app.beds24.base-url", () -> FakeBeds24Server.get().baseUrl());
    }
}
