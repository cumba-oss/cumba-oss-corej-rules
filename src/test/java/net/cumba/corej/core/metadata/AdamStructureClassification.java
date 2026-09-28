package net.cumba.corej.core.metadata;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import org.jspecify.annotations.Nullable;

/**
 * The engine's own classification of a published ADaM data structure, for corpus lints that must
 * see the library exactly as {@link MetadataLibraryProvider} does
 * ({@code UnqualifiedJoinedReadLintTest}, {@code PLAN-unqualified-joined-column-gate}).
 *
 * <p>
 * ⭐ <b>Calls the engine, copies nothing.</b> The structure token and the subclass are decided by
 * three product-keyed maps in {@link MetadataLibraryProvider} ({@code ADAM_CLASS_ALIASES},
 * {@code STRUCTURE_CLASS_OVERRIDES}, {@code SUBCLASS_OVERRIDES}). A copy of them would drift the
 * moment an entry is added there — which is how a first version of the lint came to copy two of the
 * three and miss {@code adam-adae-1-0}'s {@code ADVERSE EVENT} subclass. So this class lives in the
 * engine's package: {@link MetadataLibraryProvider#structureTokenOf} is package-private and is
 * called directly; {@code subClassOf} is private and is called reflectively, which fails loudly
 * ({@link NoSuchMethodException}) if it is ever renamed, rather than silently answering the
 * published value. No engine API is widened for a test.
 * </p>
 */
public final class AdamStructureClassification
{

    private static final Method SUB_CLASS_OF = subClassOf();

    private AdamStructureClassification()
    {
    }


    /**
     * The canonical structure token the engine maps {@code aStructure} onto, or {@code null} when
     * it maps to none ({@code MetadataLibraryProvider.structureTokenOf}).
     *
     * @param aCacheKey
     *            the publishing product's {@code standards/...} key
     * @param aStructure
     *            the published structure
     * @return the token, or {@code null}
     */
    public static @Nullable String structureToken(String aCacheKey, StoredDataStructure aStructure)
    {
        return MetadataLibraryProvider.structureTokenOf(aCacheKey, aStructure);
    }


    /**
     * The canonical subclass the engine reads for {@code aStructure} — its override when the
     * product publishes none ({@code MetadataLibraryProvider.subClassOf}), else the published value
     * — or {@code null} for a base structure.
     *
     * @param aCacheKey
     *            the publishing product's {@code standards/...} key
     * @param aStructure
     *            the published structure
     * @return the subclass, or {@code null}
     */
    public static @Nullable String subClass(String aCacheKey, StoredDataStructure aStructure)
    {
        try
        {
            return (String) SUB_CLASS_OF.invoke(null, aCacheKey, aStructure);
        }
        catch (IllegalAccessException | InvocationTargetException e)
        {
            throw new IllegalStateException("MetadataLibraryProvider.subClassOf failed", e);
        }
    }


    private static Method subClassOf()
    {
        try
        {
            Method method = MetadataLibraryProvider.class.getDeclaredMethod("subClassOf",
                    String.class, StoredDataStructure.class);
            method.setAccessible(true);
            return method;
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException("MetadataLibraryProvider.subClassOf(String,"
                    + " StoredDataStructure) is gone — the lint reads ADaM subclasses through it;"
                    + " follow the rename here", e);
        }
    }
}
