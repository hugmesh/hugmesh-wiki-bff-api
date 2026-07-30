package systems.hugmesh.wikibff.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only forgery helper for the WRONG-KID / wrong-key fail-closed case. Mints a
 * structurally-valid RS256 JWT whose {@code iss} claim is a TRUSTED issuer (so the
 * resource server's per-issuer resolver routes it to that issuer's real manager) but
 * which is signed by a freshly-generated RSA key carrying a kid that the trusted
 * issuer's published JWKS does NOT contain.
 *
 * <p>This is the adversary that a fail-OPEN bug would let through: a token that "looks
 * right" (correct issuer, well-formed, valid RS256 structure, even claiming elevated
 * roles) but is signed by a key the platform never published. The trusted issuer's
 * manager fetches that issuer's JWKS, finds no key matching the forged kid (or the
 * signature simply does not verify against the real key), and rejects with 401. Kept
 * SEPARATE from {@link TestIssuer} so that fixture stays byte-identical to the
 * gateway's verbatim copy.
 */
public final class WrongKidForger {

    private WrongKidForger() {}

    /**
     * Mint a JWT that CLAIMS {@code trustedIssuerUri} as its {@code iss} but is signed
     * by a foreign RSA key with a foreign kid. Valid in every way except the signing
     * key, so only real cryptographic verification against the trusted issuer's JWKS
     * can reject it.
     */
    public static String mintForeignSignedAs(String trustedIssuerUri, String subject,
                                             List<String> roleNames) {
        try {
            RSAKey foreignKey = new RSAKeyGenerator(2048)
                    .keyID("forged-" + UUID.randomUUID())
                    .generate();
            List<Map<String, String>> roles = roleNames.stream()
                    .map(r -> Map.of("name", r))
                    .toList();
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(trustedIssuerUri)          // CLAIMS the trusted brand...
                    .subject(subject)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("roles", roles)
                    .claim("name", subject)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(foreignKey.getKeyID())   // ...but a kid the brand never published
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(foreignKey));         // ...signed by a foreign key
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to mint wrong-kid test JWT", e);
        }
    }
}
