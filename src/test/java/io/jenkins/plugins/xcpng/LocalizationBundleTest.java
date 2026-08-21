package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the localization invariant #40 was filed for: every {@code ${%...}} label a config.jelly view
 * uses must have a backing entry in the config.properties sitting next to it, or a translator following
 * the base bundle's own instructions produces an incomplete translation. The warm-pool "Warm pool size"
 * label drifted out of the template bundle exactly this way; this test fails if either view's bundle falls
 * behind its jelly again.
 */
class LocalizationBundleTest {

    private static final Path RESOURCES = Path.of("src/main/resources/io/jenkins/plugins/xcpng");
    private static final Pattern JELLY_KEY = Pattern.compile("\\$\\{%([^}]+)\\}");

    @Test
    void cloudViewBundleCoversEveryJellyKey() throws IOException {
        assertBundleCoversView("XcpngCloud");
    }

    @Test
    void templateViewBundleCoversEveryJellyKey() throws IOException {
        assertBundleCoversView("XcpngTemplate");
    }

    /**
     * Every apostrophe in Messages.properties must be doubled, whether or not the value has a placeholder.
     *
     * <p>The generated accessor calls {@code holder.format(key)} for every message, so MessageFormat parses
     * all of them and eats a lone apostrophe: {@code controller's} renders as "controllers". This is not a
     * theoretical guard. The bundle's own header comment used to state the opposite rule, and the first
     * placeholderless apostrophe written into the file was silently swallowed in a form message with the
     * whole suite green. A convention that only lives in a comment gets believed until it costs something;
     * this makes it fail the build instead.
     */
    @Test
    void everyApostropheInTheMessageBundleIsDoubled() throws IOException {
        Path bundle = RESOURCES.resolve("Messages.properties");
        assertTrue(Files.isRegularFile(bundle), "missing " + bundle);

        List<String> offenders = new ArrayList<>();
        for (String line : Files.readAllLines(bundle, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int separator = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || separator < 0) {
                continue;
            }
            // Remove the legitimate doubled pairs; anything left is a lone apostrophe MessageFormat would eat.
            if (trimmed.substring(separator + 1).replace("''", "").indexOf('\'') >= 0) {
                offenders.add(trimmed.substring(0, separator));
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "these message values contain a single apostrophe, which " + "MessageFormat swallows; double it: "
                        + offenders);
    }

    private static void assertBundleCoversView(String descriptorDir) throws IOException {
        Path viewDir = RESOURCES.resolve(descriptorDir);
        Set<String> jellyKeys = jellyKeys(viewDir.resolve("config.jelly"));
        assertFalse(jellyKeys.isEmpty(), descriptorDir + "/config.jelly declares no ${%...} keys to check");

        Properties bundle = new Properties();
        Path propertiesFile = viewDir.resolve("config.properties");
        assertTrue(Files.isRegularFile(propertiesFile), "missing base bundle " + propertiesFile);
        try (Reader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }

        for (String key : jellyKeys) {
            assertTrue(
                    bundle.containsKey(key),
                    descriptorDir + "/config.properties is missing the key \"" + key + "\" used by config.jelly");
        }
    }

    private static Set<String> jellyKeys(Path jelly) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = JELLY_KEY.matcher(Files.readString(jelly, StandardCharsets.UTF_8));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }
}
