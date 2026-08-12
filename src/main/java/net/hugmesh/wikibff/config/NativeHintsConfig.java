package net.hugmesh.wikibff.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import net.hugmesh.wikibff.wiki.WikiAttachment;
import net.hugmesh.wikibff.wiki.WikiAuditLog;
import net.hugmesh.wikibff.wiki.WikiDto;
import net.hugmesh.wikibff.wiki.WikiPage;

/**
 * GraalVM native-image / AOT reachability hints for the bits the static analyzer
 * cannot see through on its own — mirrors hugmesh-forum-bff-api / user-bff /
 * federation-api {@code NativeHintsConfig}.
 *
 * <p>Spring Boot's own AOT processing covers the framework surface (the WebFlux
 * codecs, the reactive OAuth2 resource-server JWT pipeline, R2DBC, and the Jackson
 * auto-configuration). What it cannot always infer:
 *
 * <ul>
 *   <li><b>JSON-bound DTOs / entities</b> reached reflectively through Jackson — the
 *       records crossing the HTTP boundary ({@link WikiDto} nested records) and the
 *       persistence entities ({@link WikiPage}, {@link WikiAttachment},
 *       {@link WikiAuditLog}). They need explicit binding hints so their canonical
 *       constructors/accessors survive native image.</li>
 *   <li><b>The springdoc Swagger UI webjar resources</b> — native-image strips
 *       classpath resources by default; the bundled assets 404 without an explicit
 *       resource-include pattern. The raw {@code /v3/api-docs} JSON (what
 *       ng-openapi-gen + the docs pipeline consume) is bean-produced and unaffected.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeHintsConfig.Hints.class)
public class NativeHintsConfig {

    static class Hints implements RuntimeHintsRegistrar {

        /**
         * Registers reflection and resource reachability hints with the AOT runtime.
         * Registers all JSON-bound DTO records and persistence entity classes for full
         * member reflection, and registers the Swagger UI webjar resource patterns so
         * the bundled assets are included in the native image classpath.
         *
         * @param hints       the {@link RuntimeHints} registry to populate
         * @param classLoader the class loader in use during the AOT build (unused here;
         *                    all registrations are by class reference)
         */
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.reflection()
                    .registerType(WikiDto.PageSummary.class, dtoCategories())
                    .registerType(WikiDto.Page.class, dtoCategories())
                    .registerType(WikiDto.CreatePage.class, dtoCategories())
                    .registerType(WikiDto.UpdatePage.class, dtoCategories())
                    .registerType(WikiDto.PageList.class, dtoCategories())
                    .registerType(WikiPage.class, dtoCategories())
                    .registerType(WikiAttachment.class, dtoCategories())
                    .registerType(WikiAuditLog.class, dtoCategories());

            hints.resources()
                    .registerPattern("META-INF/resources/webjars/swagger-ui/*")
                    .registerPattern("META-INF/resources/webjars/swagger-ui/**");
        }

        /**
         * Returns the three {@link MemberCategory} values needed for Jackson and Spring
         * Data R2DBC to reflectively construct and access record types:
         * {@link MemberCategory#INVOKE_DECLARED_CONSTRUCTORS} (canonical record constructor),
         * {@link MemberCategory#INVOKE_DECLARED_METHODS} (accessor methods and any custom
         * methods), and {@link MemberCategory#DECLARED_FIELDS} (field introspection).
         *
         * @return array of the three member categories required for DTO / entity reflection
         */
        private static MemberCategory[] dtoCategories() {
            return new MemberCategory[] {
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS,
            };
        }
    }
}
