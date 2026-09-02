package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.cumba.cdisc.core.RulePackageManifest;
import net.cumba.cdisc.core.model.StandardRef;

/**
 * The packaging-time map from a rule package's {@code (standard, version)} to the CDISC Library
 * standards it runs against — {@code rules-src/package-standards.json} (Plan 2, R9).
 *
 * <p>
 * ⛔ <b>Why this is data and not code.</b> The ADaMIG→SDTMIG companion pairing used to live in
 * {@code CompanionSdtmDefaults.ADAMIG_TO_SDTMIG}, so changing a mapping required an engine release.
 * R13 classifies it: CDISC does <em>not</em> publish that pairing, so it is a house convention and
 * belongs in our data. Moving it into this generator's <em>Java</em> would have achieved nothing —
 * it had to become a file.
 * </p>
 *
 * <p>
 * ⚑ This runs at <b>packaging</b> time, not at run time: the generator lives in
 * {@code src/test/java}, outside the shipped engine.
 * </p>
 */
final class PackageStandardsMap
{

    /** Resolved against the module base directory, like {@code rules-src/} itself. */
    static final Path FILE = Path.of("rules-src", "package-standards.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VERSION_TOKEN = "{version}";

    private final JsonNode standards;

    private PackageStandardsMap(JsonNode aStandards)
    {
        standards = aStandards;
    }


    /**
     * As {@link #load}, but yields an EMPTY map when the file is absent instead of throwing.
     *
     * <p>
     * ⚑ Used by {@link RuleStorageAssembler}, which also serves synthetic {@code rules-src}-shaped
     * directories in tests that carry no packaging metadata. The shipped path stays strict:
     * {@link RuleCorpusGenerator} calls {@link #load}, so a missing file fails the real
     * regeneration rather than silently emitting a corpus that declares nothing.
     * </p>
     *
     * @param baseDir
     *            the module base directory
     * @return the parsed map, or an empty one
     * @throws IOException
     *             if the file exists but cannot be parsed
     */
    static PackageStandardsMap loadOrEmpty(Path baseDir) throws IOException
    {
        return Files.isRegularFile(baseDir.resolve(FILE)) ? load(baseDir) : empty();
    }


    /** A map that declares nothing. */
    static PackageStandardsMap empty()
    {
        return new PackageStandardsMap(JSON.createObjectNode());
    }


    /**
     * Loads the map.
     *
     * @param baseDir
     *            the module base directory
     * @return the parsed map
     * @throws IOException
     *             if the file is absent or unreadable — it is a required packaging input, so a
     *             missing file is an error rather than "declare nothing everywhere"
     */
    static PackageStandardsMap load(Path baseDir) throws IOException
    {
        Path p = baseDir.resolve(FILE);
        if (!Files.isRegularFile(p))
        {
            throw new IOException("required packaging input missing: " + p.toAbsolutePath());
        }
        JsonNode root = JSON.readTree(Files.readString(p));
        JsonNode s = root.get("standards");
        if (s == null || !s.isObject())
        {
            throw new IOException("no 'standards' object in " + p.toAbsolutePath());
        }
        return new PackageStandardsMap(s);
    }


    /**
     * The standards a package at {@code (standard, version)} declares, primaries first.
     *
     * @param standard
     *            the package's standard display name, e.g. {@code ADaMIG}
     * @param version
     *            the package's version, display or dash form
     * @return the declared standards; EMPTY when the standard is unmapped — such a package declares
     *         nothing and a run selecting it needs an explicit {@code --metadata-products}
     */
    List<StandardRef> forPackage(String standard, String version)
    {
        JsonNode entry = standards.get(standard);
        if (entry == null)
        {
            return List.of();
        }
        String dashed = RulePackageManifest.enc(version);
        List<StandardRef> out = new ArrayList<>();
        for (JsonNode id : entry.path("primary"))
        {
            out.add(new StandardRef(expand(id.asText(), dashed), StandardRef.Role.PRIMARY));
        }
        for (JsonNode id : entry.path("companionByVersion").path(dashed))
        {
            out.add(new StandardRef(expand(id.asText(), dashed), StandardRef.Role.COMPANION));
        }
        return List.copyOf(out);
    }


    private static String expand(String template, String dashedVersion)
    {
        return template.replace(VERSION_TOKEN, dashedVersion).toLowerCase(Locale.ROOT);
    }
}
