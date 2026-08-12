package net.hugmesh.wikibff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wiki BFF — TIER 1 (WRITE) of the 3-tier, Kiwix-backed wiki optional service. It owns
 * the writable {@code wiki_page} Postgres content store admins author markdown into; the
 * BFF renders markdown to HTML at write and stores both. The rendered {@code content_html}
 * is the SOURCE OF TRUTH the TIER 2 (re)build (PR4: a CronJob packaging a ZIM via
 * zim-tools) consumes, which TIER 3 ({@code kiwix-serve}, the READ backend) then serves.
 * The two tiers fail independently — a kiwix outage never blocks an edit, and a BFF
 * outage leaves readers on the last published ZIM.
 *
 * <pre>browser -> fe-gateway /bff/wiki/** -> THIS BFF -> wiki_page Postgres store (TIER 1)
 * browser -> fe-gateway /social/wiki/** (kiwix content path) -> kiwix-serve (TIER 3, READ)</pre>
 *
 * <p>This BFF wraps the store per ROLE + ACTION (defence in depth on top of the gateway
 * route-access ROLE_WIKI floor):
 *
 * <ul>
 *   <li>READ (list/get) — {@code WIKI_READ = hasRole('ADMIN') or hasRole('SUPERADMIN')
 *       or (hasRole('MOD') and hasRole('WIKI'))} — the role cascade's default read set
 *       (regular USERs reach it only via a grant, PR3);</li>
 *   <li>EDIT (create/update) + PUBLISH — {@code WIKI_WRITE = hasRole('ADMIN') or
 *       hasRole('SUPERADMIN')} — markdown authoring; the write renders markdown->HTML and
 *       bumps the page version;</li>
 *   <li>MANAGE — {@code WIKI_MANAGE = hasRole('SUPERADMIN')} — manage-all (settings + the
 *       ZIM-rebuild trigger arrives in PR4).</li>
 * </ul>
 *
 * <p>Every surface is DOMAIN-SCOPED (multi-brand): the {@code domain} is derived
 * SERVER-SIDE from the validated token issuer, so a scoped ADMIN of brand X sees ONLY
 * brand X's pages; SUPERADMIN is the cross-domain bypass. Author/owner is tracked from
 * the JWT subject ({@code author_subject}); a caller can never author under another
 * subject. The grant/timeout/ban {@code wiki_access} overlay (PR3) is shipped — a USER
 * without an admin/mod role reaches read only via an active grant, enforced by
 * {@code WikiAccessGuard}. Only the TIER 2/3 kiwix/ZIM (re)build pipeline (PR4) remains
 * out of repo (see the README roadmap); everything else described above is on {@code main}.
 */
@SpringBootApplication
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
public class WikiBffApplication {

    /**
     * Launches the Spring Boot application, bootstrapping the Spring context, the embedded
     * web server, and all auto-configured components (R2DBC, Security, WebFlux).
     *
     * @param args command-line arguments forwarded to {@link SpringApplication#run}
     */
    public static void main(String[] args) {
        SpringApplication.run(WikiBffApplication.class, args);
    }
}
