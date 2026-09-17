package net.hugmesh.wikibff.access;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The JSON shapes crossing the {@code /bff/wiki/.../access} HTTP boundary for the
 * grant/timeout/ban overlay. Plain records ({@code @JsonIgnoreProperties(ignoreUnknown)}).
 * The acting principal's {@code grantedBy} + {@code domain} are NEVER read from the body —
 * the service stamps them from the validated JWT (granted_by == Casdoor sub; domain ==
 * validated issuer) so a caller cannot act under another admin or forge a cross-tenant
 * scope.
 */
public final class WikiAccessDto {

    private WikiAccessDto() {}

    /**
     * A {@code wiki_access} row as returned to a manage/audit reader.
     *
     * @param id               surrogate UUID primary key of the override row
     * @param domain           brand/tenant the override belongs to
     * @param userSubject      Casdoor subject of the principal whose access is overridden
     * @param accessType       {@code "read"} (grant) or {@code "banned"} (ban/timeout)
     * @param state            {@code "active"} while in force; {@code "revoked"} once lifted
     * @param grantedBySubject Casdoor subject of the admin who issued the override
     * @param reason           optional free-text audit reason; {@code null} if not supplied
     * @param createdAt        wall-clock instant the row was inserted
     * @param expiresAt        {@code null} for a permanent override; a future instant for a timeout
     * @param revokedAt        {@code null} while active; set when the row was revoked
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Access(String id, String domain, String userSubject, String accessType,
                         String state, String grantedBySubject, String reason,
                         Instant createdAt, Instant expiresAt, Instant revokedAt) {
        /**
         * Constructs an {@code Access} response record from a {@link WikiAccess} domain object,
         * copying all fields by accessor.
         *
         * @param a the domain record to project; must not be {@code null}
         * @return a new {@code Access} whose fields mirror those of {@code a}
         */
        public static Access of(WikiAccess a) {
            return new Access(a.id(), a.domain(), a.userSubject(), a.accessType(), a.state(),
                    a.grantedBySubject(), a.reason(), a.createdAt(), a.expiresAt(), a.revokedAt());
        }
    }

    /**
     * The list-response wrapper for a collection of {@link Access} records.
     *
     * @param access the page of access override records; never {@code null}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessList(List<Access> access) {
        /**
         * Defensive copy on the way IN. The accessor below already copies on the way OUT,
         * but a record's canonical constructor still stores the CALLER's list by reference
         * — a caller that kept and mutated it would mutate this record (EI_EXPOSE_REP2).
         * Copying here makes the field immutable at rest (which in turn makes the
         * accessor's {@code copyOf} a no-op returning the same instance).
         */
        public AccessList {
            access = List.copyOf(access);
        }

        @Override
        public List<Access> access() {
            return List.copyOf(access);
        }
    }

    /**
     * {@code GET /bff/wiki/superadmin/brands} — the BRAND CATALOGUE the console's target-brand
     * picker is built from (hugmesh-wiki-bff-api#12).
     *
     * <p>The three fields answer the three different questions a picker has to get right, and
     * they are NOT interchangeable:
     *
     * <ul>
     *   <li>{@code brands} — every value this bff will ACCEPT as a
     *       {@link GrantOrBan#targetDomain()}. Built by filtering
     *       {@link net.hugmesh.commons.security.DomainRegistry#knownDomains()} through the very
     *       predicate the write path validates with
     *       ({@code DomainRegistry.isKnownDomain}), so a listed brand is accepted BY
     *       CONSTRUCTION and the sentinels that always 400 ({@code default}, blank) cannot
     *       appear. Sorted, so the rendered order is stable.</li>
     *   <li>{@code launchDomain} — the domain THIS WIKI IS LAUNCHED ON, from the same
     *       {@link net.hugmesh.wikibff.config.WikiLaunchDomain} derivation the 007 re-stamp
     *       target uses, so the console and the migration can never disagree about it. Never
     *       blank: it falls back to {@code hugmesh} exactly as that derivation does. It is NOT
     *       necessarily a member of {@code brands} — on a 2+-brand deployment there is no
     *       single launch domain and the fallback is reported instead — so a caller that wants
     *       to preselect it MUST check membership first.</li>
     *   <li>{@code targetDomainHonoured} — whether {@code targetDomain} is consulted AT ALL.
     *       {@code WikiAccessService.writeDomain} reads the field whenever this deployment
     *       configures at least one real brand: with 2+ a SUPERADMIN must name one, and with
     *       exactly one the field may be omitted (that brand is stamped) but naming a
     *       different brand is a {@code 400}. It is false ONLY on a back-compat single-issuer
     *       deployment, where every principal genuinely is {@code default}, the field is inert
     *       and {@code brands} is empty — so false now always means "nothing to pick", never
     *       "a real choice the server will discard". See {@link WikiBrandCatalog}.</li>
     * </ul>
     *
     * @param brands               the accepted {@code targetDomain} values, sorted; never {@code null},
     *                             possibly empty (single-issuer back-compat)
     * @param launchDomain         the domain this wiki is launched on; never {@code null} or blank
     * @param targetDomainHonoured whether the bff consults a request's {@code targetDomain}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Brands(List<String> brands, String launchDomain, boolean targetDomainHonoured) {
        /**
         * Defensive copy on the way IN — a record's canonical constructor otherwise stores the
         * CALLER's list by reference (EI_EXPOSE_REP2), as {@link AccessList} documents.
         */
        public Brands {
            brands = List.copyOf(brands);
        }

        @Override
        public List<String> brands() {
            return List.copyOf(brands);
        }
    }

    // ── request bodies ─────────────────────────────────────────────────

    /**
     * {@code POST .../access/grant} and {@code .../access/ban} — a permanent override.
     * {@code reason} is optional free text (audited). {@code targetDomain} is the VICTIM's
     * brand, consulted ONLY for a SUPERADMIN cross-brand write (a scoped ADMIN's brand is
     * always issuer-derived and this field is ignored): a SUPERADMIN bans a brand-X user by
     * passing {@code targetDomain="brandx"} so the row lands in (and the read guard matches)
     * the victim's brand instead of the inert {@code default} domain.
     *
     * <p>How the field is read depends on how many brands the deployment configures, and the
     * full table is on {@code WikiAccessService.writeDomain}. In short: with TWO OR MORE
     * configured brands a SUPERADMIN must name one or take a {@code 400}; with EXACTLY ONE
     * the field may be omitted (that brand is stamped) and naming any other brand is a
     * {@code 400}; with NO brand configured at all the field is inert and the write is
     * stamped {@code default} as it always was.
     *
     * @param userSubject  Casdoor subject of the principal to grant or ban; required,
     *                     max 400 characters
     * @param reason       optional free-text audit reason for the action; max 1 024 characters
     * @param targetDomain the VICTIM's brand for a SUPERADMIN cross-brand write; ignored
     *                     for a scoped ADMIN (issuer-derived); max 64 characters
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GrantOrBan(
            @NotBlank(message = "userSubject is required")
            @Size(max = 400, message = "userSubject is too long")
            String userSubject,
            @Size(max = 1024, message = "reason is too long")
            String reason,
            @Size(max = 64, message = "targetDomain is too long")
            String targetDomain) {}

    /**
     * {@code POST .../access/timeout} — a {@code banned} override with a FUTURE expiry.
     * {@code ttlSeconds} drives the auto-restore instant; it must be positive.
     * {@code targetDomain} is the VICTIM's brand for a SUPERADMIN cross-brand timeout (same
     * contract as {@link GrantOrBan#targetDomain()}, including the single-configured-brand
     * rules; ignored for a scoped ADMIN).
     *
     * @param userSubject  Casdoor subject of the principal to timeout; required,
     *                     max 400 characters
     * @param ttlSeconds   duration in seconds until the timeout auto-restores; must be positive
     * @param reason       optional free-text audit reason for the action; max 1 024 characters
     * @param targetDomain the VICTIM's brand for a SUPERADMIN cross-brand timeout; ignored
     *                     for a scoped ADMIN (issuer-derived); max 64 characters
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Timeout(
            @NotBlank(message = "userSubject is required")
            @Size(max = 400, message = "userSubject is too long")
            String userSubject,
            @Positive(message = "ttlSeconds must be positive")
            long ttlSeconds,
            @Size(max = 1024, message = "reason is too long")
            String reason,
            @Size(max = 64, message = "targetDomain is too long")
            String targetDomain) {}
}
