package net.cumba.corej.core.expr.convert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Deletes stale {@code rules-*.json} package files after a corpus regeneration. Moved out of
 * {@code RulePackageManifest} by PLAN-engine-rules-decoupling — pruning is a generator-side concern
 * ({@link RuleExprGenerator}, {@code RuleStorageAssembler}); the engine only reads packages.
 */
public final class RulePackagePruner
{

    /**
     * Non-package {@code rules-*.json} files a prune must never delete.
     * {@code rules-templates.json} left this set with Fix #366 — the engine's built-in template
     * file was deleted, and a file of that name appearing in a package directory is now an ordinary
     * stale package.
     */
    private static final Set<String> NON_PACKAGE_FILES = Set.of("rules-placeholders.json");

    private RulePackagePruner()
    {
    }


    /**
     * Deletes every {@code rules-*.json} in {@code dir} that is neither in {@code keep} (the
     * current package file names) nor a known non-package file ({@code rules-placeholders.json}),
     * so a regeneration leaves no orphan from a previous naming scheme or a removed
     * standard/version.
     *
     * @param dir
     *            the package directory ({@code rules-legacy/} or {@code rules/})
     * @param keep
     *            the file names to retain
     * @throws IOException
     *             if the directory cannot be listed or a stale file cannot be deleted
     */
    public static void pruneStalePackages(Path dir, Set<String> keep) throws IOException
    {
        List<Path> stale;
        try (java.util.stream.Stream<Path> files = Files.list(dir))
        {
            stale = files.filter(p ->
            {
                Path fileName = p.getFileName();
                String n = fileName == null ? "" : fileName.toString();
                return n.startsWith("rules-") && n.endsWith(".json") && !keep.contains(n)
                        && !NON_PACKAGE_FILES.contains(n);
            }).toList();
        }
        for (Path p : stale)
        {
            Files.delete(p);
            System.out.println("[prune] removed stale package " + p.getFileName());
        }
    }
}
