package net.cumba.corej.core.rulespec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The D4 pin-sync guard of {@code plans/done/PLAN-parity-java-lane-native-corpus.md}: a spec
 * carrying {@code pinned_from} embeds a <b>fully inline pinned rule</b> that was derived once from
 * the named corpus rule and deliberately does not track it (the D1 y-wildcard pins). The price of
 * that independence is a sync duty — and a sync duty that lives only in prose is exactly the
 * failure mode the classifier redesign existed to fix. So the duty is enforced: this test
 * fingerprints each pin's source file and fails the moment the source moves, telling the maintainer
 * to re-derive the pinned {@code rule:} block and update the recorded fingerprint.
 */
class PinnedSpecSyncGuardTest
{

    private static final Path SPECS = Path.of("rulespec", "specs");

    // The authoring corpus lives in the sibling corej-rules module; ../ resolves from this
    // module's basedir (surefire cwd), same as SpecLoader / NoStaleFixtureGuardTest.
    private static final Path CHECKS = Path.of("rules-src", "checks");

    private static final Pattern FAMILY = Pattern.compile("[A-Za-z]+");

    @Test
    void everyPinnedSpecFingerprintMatchesItsSourceRule() throws IOException
    {
        List<String> stale = new ArrayList<>();
        int pinned = 0;
        List<Path> specFiles;
        try (Stream<Path> files = Files.list(SPECS))
        {
            specFiles = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path specFile : specFiles)
        {
            SpecSchema spec = SpecLoader.load(specFile);
            SpecSchema.PinnedFrom pin = spec.pinnedFrom();
            if (pin == null)
            {
                continue;
            }
            pinned++;
            // A typo'd core_id would fingerprint the wrong source file and guard the wrong rule.
            String inlineId = spec.rule() == null ? null
                    : spec.rule().path("Core").path("Id").asText(null);
            if (!pin.coreId().equals(inlineId))
            {
                stale.add(specFile.getFileName() + ": pinned_from.core_id " + pin.coreId()
                        + " does not match the inline rule's Core.Id " + inlineId);
            }
            String reason = staleReason(specFile.getFileName().toString(), pin, CHECKS);
            if (reason != null)
            {
                stale.add(reason);
            }
        }
        assertTrue(pinned >= 5, "expected at least the five D1 y-wildcard pins, found " + pinned);
        assertEquals(List.of(), stale,
                "a pinned spec's source rule moved — the pin does not track it, so it must be"
                        + " re-derived deliberately (PLAN-parity-java-lane-native-corpus D1/D4)");
    }


    @Test
    void aDoctoredFingerprintIsReportedStale(@org.junit.jupiter.api.io.TempDir Path tmp)
        throws IOException
    {
        Path family = Files.createDirectories(tmp.resolve("CORE"));
        Files.writeString(family.resolve("CORE-000001.yaml"), "Core:\n  Id: CORE-000001\n");
        SpecSchema.PinnedFrom doctored = new SpecSchema.PinnedFrom("CORE-000001",
                "0000000000000000000000000000000000000000000000000000000000000000");
        String reason = staleReason("x.yaml", doctored, tmp);
        assertTrue(reason != null && reason.contains("re-derive"), String.valueOf(reason));
        // and the happy path: the real fingerprint passes
        SpecSchema.PinnedFrom fresh = new SpecSchema.PinnedFrom("CORE-000001",
                sha256(family.resolve("CORE-000001.yaml")));
        assertEquals(null, staleReason("x.yaml", fresh, tmp));
        // a vanished source rule is stale too, not silently skipped
        SpecSchema.PinnedFrom gone = new SpecSchema.PinnedFrom("CORE-000002",
                doctored.rulesSrcSha256());
        String vanished = staleReason("x.yaml", gone, tmp);
        assertTrue(vanished != null && vanished.contains("no longer exists"),
                String.valueOf(vanished));
    }


    /**
     * Why this pin is stale against {@code checksDir}, or {@code null} when the recorded
     * fingerprint still matches the source rule file.
     */
    private static String staleReason(String specName, SpecSchema.PinnedFrom pin, Path checksDir)
        throws IOException
    {
        Path source = checksDir.resolve(family(pin.coreId())).resolve(pin.coreId() + ".yaml");
        if (!Files.isRegularFile(source))
        {
            return specName + ": pinned source rule " + pin.coreId() + " no longer exists at "
                    + source;
        }
        String actual = sha256(source);
        if (!actual.equals(pin.rulesSrcSha256()))
        {
            return specName + ": source rule " + pin.coreId()
                    + " changed — re-derive the pinned rule: block and update"
                    + " pinned_from.rules_src_sha256 (recorded " + pin.rulesSrcSha256()
                    + ", actual " + actual + ")";
        }
        return null;
    }


    /** Leading alpha token of the Core.Id, e.g. {@code CORE-000351 -> CORE}. */
    private static String family(String coreId)
    {
        Matcher m = FAMILY.matcher(coreId);
        return m.lookingAt() ? m.group() : "OTHER";
    }


    private static String sha256(Path file) throws IOException
    {
        try
        {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
