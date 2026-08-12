package net.hugmesh.wikibff;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import net.hugmesh.commons.testsupport.PostgresTestSupport;

/**
 * Context-load smoke test: the full Spring context boots under the {@code test}
 * profile. The DB is a REAL Postgres container (via {@link PostgresTestSupport}) the
 * app's R2DBC + Liquibase are wired at, so the repositories + r2dbc-converters wire
 * over the real Liquibase-migrated schema (replacing the dual-handle in-mem H2 that
 * stopped sharing a store under Spring Boot 4). The reactive JWT decoder is mocked so
 * no live Casdoor issuer is contacted. This proves the controller, service,
 * repositories, the markdown renderer, and the security + native-hints config wire
 * together.
 */
@SpringBootTest
@ActiveProfiles("test")
class WikiBffApplicationContextTest extends PostgresTestSupport {

    /** No live Casdoor issuer in CI — mock the reactive JWT decoder so the
     *  oauth2 resource-server wiring is satisfied without a network call. */
    @MockitoBean ReactiveJwtDecoder reactiveJwtDecoder;

    @Test
    void contextLoads() {
        // success = the application context started with every bean wired.
    }
}
