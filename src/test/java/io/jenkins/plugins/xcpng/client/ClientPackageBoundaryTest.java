package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps this package free of Jenkins and of the plugin package above it.
 *
 * <p>The client is backend plumbing in plugin terms only: it speaks to Xen Orchestra and knows nothing of
 * nodes, clouds or the controller. Today it imports the JDK, Jackson and the SpotBugs annotations and nothing
 * else, but only by convention, so one convenient {@code hudson.*} import would erase that without anyone
 * noticing. A separate Maven module would enforce it at compile time and was decided against in #267 as more
 * build than the boundary is worth. This test enforces it instead.
 *
 * <p>It reads the source, not the bytecode, so it also catches a fully qualified name used without an import.
 * The {@code package} line is skipped; everything else, comments included, is checked, since a Javadoc
 * {@code {@link hudson.model.Node}} is a dependency too, on the javadoc classpath.
 */
class ClientPackageBoundaryTest {

    private static final Path CLIENT = Path.of("src/main/java/io/jenkins/plugins/xcpng/client");

    /**
     * A reference to Jenkins core ({@code hudson.} or {@code jenkins.} not preceded by a dot, so
     * {@code io.jenkins.} does not count), or to a class in the plugin package outside {@code client}.
     */
    private static final Pattern FORBIDDEN =
            Pattern.compile("(?<![\\w.])(hudson|jenkins)\\.|io\\.jenkins\\.plugins\\.xcpng\\.(?!client\\b)\\w");

    @Test
    void theClientPackageReferencesNeitherJenkinsNorThePluginPackage() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(CLIENT)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        // The denominator: a wrong path would list nothing and pass every time.
        assertTrue(sources.size() >= 10, "expected the client sources under " + CLIENT + ", found " + sources);

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("package ")) {
                    continue;
                }
                if (FORBIDDEN.matcher(line).find()) {
                    violations.add(source.getFileName() + ":" + (i + 1) + ": " + line.strip());
                }
            }
        }
        assertEquals(
                List.of(),
                violations,
                "io.jenkins.plugins.xcpng.client must not depend on Jenkins or on the plugin package (#267)");
    }

    /** The pattern itself, so a regex that matches nothing cannot pass the test above vacuously. */
    @Test
    void thePatternCatchesWhatItIsFor() {
        assertTrue(FORBIDDEN.matcher("import hudson.model.Node;").find());
        assertTrue(FORBIDDEN.matcher("import jenkins.model.Jenkins;").find());
        assertTrue(FORBIDDEN.matcher("    jenkins.model.Jenkins.get();").find());
        assertTrue(
                FORBIDDEN.matcher("import io.jenkins.plugins.xcpng.XcpngCloud;").find());
        assertTrue(FORBIDDEN
                .matcher(" * {@link io.jenkins.plugins.xcpng.XcpngAgent}")
                .find());

        assertFalse(FORBIDDEN
                .matcher("import io.jenkins.plugins.xcpng.client.VmRef;")
                .find());
        assertFalse(FORBIDDEN
                .matcher("import com.fasterxml.jackson.databind.JsonNode;")
                .find());
        assertFalse(FORBIDDEN.matcher(" * the Jenkins half of the plugin").find());
    }
}
