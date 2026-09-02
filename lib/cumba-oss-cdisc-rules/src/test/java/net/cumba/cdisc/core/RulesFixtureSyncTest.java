package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fixture-drift gate (PLAN-engine-rules-decoupling §8): the engine's curated fixture corpus
 * ({@code corej-cdisc-core/src/test/resources/fixtures/rules/}) must stay byte/content-identical to
 * this module's corpus, or the engine tests silently pin stale rules. On failure, rebuild the
 * fixtures via {@code scripts/build-core-fixtures.py}.
 */
class RulesFixtureSyncTest
{

    private static final Path ENGINE_FIXTURES = Path.of("..", "corej-cdisc-core", "src", "test",
            "resources", "fixtures", "rules");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void checkYamlFixturesMatchCorpus() throws IOException
    {
        try (Stream<Path> files = Files.walk(ENGINE_FIXTURES.resolve("checks")))
        {
            files.filter(p -> p.toString().endsWith(".yaml")).forEach(fixture ->
            {
                Path fam = fixture.getParent();
                Path src = Path.of("rules-src", "checks", name(fam), name(fixture));
                try
                {
                    assertTrue(Files.exists(src), "fixture without corpus source: " + fixture);
                    assertEquals(Files.readString(src), Files.readString(fixture),
                            "fixture drifted from corpus (rebuild via"
                                    + " scripts/build-core-fixtures.py): " + fixture);
                }
                catch (IOException e)
                {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }


    @Test
    void trimmedPackagesMatchCorpusRules() throws IOException
    {
        try (Stream<Path> files = Files.list(ENGINE_FIXTURES.resolve("packages")))
        {
            files.map(RulesFixtureSyncTest::name).filter(n -> n.startsWith("rules-")
                    && n.endsWith(".json") && !"packages.json".equals(n))
                    .forEach(this::assertPackageInSync);
        }
    }


    private void assertPackageInSync(String file)
    {
        try
        {
            JsonNode trimmed = MAPPER
                    .readTree(Files.readString(ENGINE_FIXTURES.resolve("packages").resolve(file)))
                    .get("rules");
            JsonNode full = MAPPER.readTree(Files.readString(CorpusPaths.rules(file))).get("rules");
            trimmed.properties().forEach(e ->
            {
                JsonNode source = full.get(e.getKey());
                assertTrue(source != null, "trimmed package rule missing from corpus: " + e.getKey()
                        + " (" + file + ")");
                assertEquals(source, e.getValue(),
                        "trimmed package rule drifted from corpus (rebuild via"
                                + " scripts/build-core-fixtures.py): " + e.getKey() + " (" + file
                                + ")");
            });
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static String name(Path p)
    {
        Path fn = p == null ? null : p.getFileName();
        return fn == null ? "" : fn.toString();
    }
}
