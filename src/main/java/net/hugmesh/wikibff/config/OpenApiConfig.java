package net.hugmesh.wikibff.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal OpenAPI document metadata for the wiki BFF spec served by
 * springdoc-openapi-starter-webflux-ui at {@code /v3/api-docs}.
 *
 * <p>Sets a stable {@code info.title} (the service name) + {@code info.version} so
 * the generated artefacts downstream are sensibly named: the platform
 * ng-openapi-gen typed client for the paired hugmesh-wiki-ui (PR5) and the docs
 * pipeline key off these fields. A programmatic {@link OpenAPI} bean is used
 * (rather than the {@code @OpenAPIDefinition} annotation) so the metadata is plain
 * bean wiring — no annotation reflection scan, which keeps the GraalVM native
 * build hint surface minimal.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /**
     * Produces the {@link OpenAPI} document root that springdoc-openapi serves at
     * {@code /v3/api-docs}. Sets the stable {@code info.title}, {@code info.version},
     * and {@code info.description} fields consumed by ng-openapi-gen and the docs pipeline.
     *
     * @return a configured {@link OpenAPI} instance describing the wiki BFF API
     */
    @Bean
    OpenAPI wikiBffOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("hugmesh-wiki-bff-api")
                        .version("0.1.0")
                        .description("Wiki BFF API (/bff/wiki/**) — TIER 1 of the 3-tier wiki: the writable "
                                + "Postgres content store (wiki_page/wiki_attachment/wiki_audit_log) the "
                                + "admins author markdown into. Read (list/get) gated WIKI_READ "
                                + "(admin|superadmin|mod+WIKI); edit (create/update, markdown->HTML at write, "
                                + "version bump) + publish gated WIKI_WRITE (admin|superadmin); manage gated "
                                + "WIKI_MANAGE (superadmin). The rendered content_html is the source the ZIM "
                                + "(re)build (PR4) packages for kiwix-serve (TIER 3 READ). All surfaces are "
                                + "domain-scoped per multi-brand: a scoped ADMIN sees only own-brand pages."));
    }
}
