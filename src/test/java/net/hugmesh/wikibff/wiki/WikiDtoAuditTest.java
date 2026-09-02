package net.hugmesh.wikibff.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit-trail DTOs ({@link WikiDto.AuditEntry} / {@link WikiDto.AuditList}) — the
 * named schema that replaced the endpoint's untyped {@code Map<String, Object>} so the
 * generated ng-openapi-gen clients get a real model instead of
 * {@code Record<string, unknown>} (hugmesh-wiki-ui issue #2).
 *
 * <p>Unit-level: no Spring context, no database. The wire-level proof (that the JSON is
 * unchanged and binds to {@link WikiDto.AuditList}) lives in {@code WikiControllerTest}.
 */
class WikiDtoAuditTest {

    private static final Instant WHEN = Instant.parse("2026-08-20T10:15:30Z");

    private static WikiAuditLog row(String detail) {
        return new WikiAuditLog("a-1", "brandx", "actor-1", "wiki.page.create", "home", detail, WHEN);
    }

    @Test
    void of_carriesEveryColumnAcross_noneDroppedOrReordered() {
        // Field-for-field: a mapping that silently dropped (or transposed) a column would
        // still compile and still serialize — only an exhaustive comparison catches it.
        WikiDto.AuditEntry e = WikiDto.AuditEntry.of(row("v1"));

        assertThat(e.id()).isEqualTo("a-1");
        assertThat(e.domain()).isEqualTo("brandx");
        assertThat(e.actorSubject()).isEqualTo("actor-1");
        assertThat(e.action()).isEqualTo("wiki.page.create");
        assertThat(e.target()).isEqualTo("home");
        assertThat(e.detail()).isEqualTo("v1");
        assertThat(e.createdAt()).isEqualTo(WHEN);
    }

    @Test
    void of_nullDetailStaysNull_theColumnIsNullable() {
        assertThat(WikiDto.AuditEntry.of(row(null)).detail()).isNull();
    }

    @Test
    void auditList_defensiveCopyIn_callerCannotMutateTheRecordAfterConstruction() {
        List<WikiDto.AuditEntry> mutable = new ArrayList<>();
        mutable.add(WikiDto.AuditEntry.of(row("v1")));

        WikiDto.AuditList list = new WikiDto.AuditList(mutable);
        mutable.clear();

        assertThat(list.actions()).as("the record kept its own copy (EI_EXPOSE_REP2)").hasSize(1);
    }

    @Test
    void auditList_accessorCopiesOut_theReturnedListIsImmutable() {
        WikiDto.AuditList list = new WikiDto.AuditList(List.of(WikiDto.AuditEntry.of(row("v1"))));

        assertThatThrownBy(() -> list.actions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void auditList_serializesUnderTheActionsKey_withTheWikiAuditLogFieldNames() throws Exception {
        // The wire contract the existing UI already reads: {"actions":[{id,domain,
        // actorSubject,action,target,detail,createdAt}]}. Typing the response must not
        // rename or drop a key.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(
                new WikiDto.AuditList(List.of(WikiDto.AuditEntry.of(row("v1"))))));

        assertThat(json.has("actions")).isTrue();
        assertThat(json.get("actions")).hasSize(1);
        assertThat(json.get("actions").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "domain", "actorSubject", "action",
                        "target", "detail", "createdAt");
    }

    @Test
    void committedSpec_audit200IsARefToANamedSchema_notAnOpenObject() throws Exception {
        // The DEFECT this closes, stated directly against the artefact the typed
        // clients are generated from: the 200 used to be
        // `{"type":"object","additionalProperties":{"type":"object"}}`, which
        // ng-openapi-gen renders as `{[key: string]: {}}` — i.e. untyped.
        //
        // OpenApiSpecDumpTest already byte-compares this file against the live API,
        // so a controller revert is caught there — but ONLY while the committed copy
        // is not refreshed alongside it. This asserts the SHAPE, so reverting the
        // endpoint AND regenerating the spec still fails.
        JsonNode spec = new ObjectMapper().readTree(
                Files.readString(Path.of("src", "main", "resources", "openapi", "openapi.json"),
                        StandardCharsets.UTF_8));

        JsonNode schema = spec.path("paths").path("/bff/wiki/superadmin/audit").path("get")
                .path("responses").path("200").path("content").path("application/json").path("schema");

        assertThat(schema.path("$ref").asText())
                .as("the audit 200 must reference a NAMED component schema")
                .isEqualTo("#/components/schemas/AuditList");
        assertThat(schema.has("additionalProperties"))
                .as("an open additionalProperties object is what made the generated client untyped")
                .isFalse();

        JsonNode entry = spec.path("components").path("schemas").path("AuditEntry").path("properties");
        assertThat(entry.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "domain", "actorSubject", "action",
                        "target", "detail", "createdAt");
        assertThat(spec.path("components").path("schemas").path("AuditList")
                .path("properties").path("actions").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/AuditEntry");
    }

    @Test
    void auditList_emptyTrailKeepsTheActionsKey_asAnEmptyArray() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectNode json = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(new WikiDto.AuditList(List.of())));

        assertThat(json.path("actions").isArray()).isTrue();
        assertThat(json.path("actions")).isEmpty();
    }
}
