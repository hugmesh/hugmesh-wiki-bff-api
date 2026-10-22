package net.hugmesh.wikibff.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A public repository needs its own security policy. The organisation-wide SECURITY.md lives in
 * the hugmesh/.github repository, and GitHub only applies those defaults while that repository
 * is public; this repo cannot rely on it. Without a policy file a person who finds a hole has no
 * documented private route and is likely to file a public issue with the details.
 *
 * <p>This pins three things: the file exists at the repo root (where GitHub looks for it), it
 * names GitHub's private vulnerability reporting as the route, and it tells reporters to keep
 * the details out of public issues.
 */
@DisplayName("SECURITY.md — the repo carries its own private reporting policy")
class SecurityPolicyDocTest {

    private static String policy() throws IOException {
        Path p = Path.of("SECURITY.md");
        assertThat(p)
                .as("SECURITY.md must exist at the module root (surefire's working directory),"
                        + " where GitHub looks for a repository security policy")
                .exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("names GitHub private vulnerability reporting as the way to report")
    void namesPrivateReporting() throws IOException {
        String text = policy().toLowerCase(Locale.ROOT);
        assertThat(text)
                .as("the policy must point reporters at a private channel")
                .contains("private vulnerability reporting")
                .contains("report a vulnerability");
    }

    @Test
    @DisplayName("tells reporters not to put the details in a public issue")
    void forbidsPublicDetails() throws IOException {
        String text = policy().toLowerCase(Locale.ROOT);
        assertThat(text)
                .as("the policy must say not to disclose the problem in a public issue")
                .contains("do not open a public issue");
    }
}
