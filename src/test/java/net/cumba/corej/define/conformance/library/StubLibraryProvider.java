package net.cumba.corej.define.conformance.library;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic in-memory {@link LibraryProvider} for the library-gated rule tests: an SDTMIG 3.4
 * slice with two datasets (DM "Demographics", AE "Adverse Events"), four variables (DM.AGE, DM.SEX,
 * AE.AESEV, AE.AEACN — the latter three carrying CT codelist c-codes), Core designations (DM.SEX
 * "Req", DM.AGE "Exp"), two Event/Intervention qualifier fragments (OCCUR "Occurrence", STAT
 * "Completion Status"), and the published SDTMIG/SENDIG version lists. Everything else is unknown,
 * so tests can prove the conservative no-finding path for library-unknown material.
 */
public final class StubLibraryProvider implements LibraryProvider
{

    private static final Map<String, String> DATASET_LABELS = Map.of(//
            "DM", "Demographics", //
            "AE", "Adverse Events");

    private static final Map<String, String> VARIABLE_LABELS = Map.of(//
            "DM/AGE", "Age", //
            "DM/SEX", "Sex", //
            "AE/AESEV", "Severity/Intensity", //
            "AE/AEACN", "Action Taken with Study Treatment");

    private static final Map<String, String> VARIABLE_C_CODES = Map.of(//
            "DM/SEX", "C66731", //
            "AE/AESEV", "C66769", //
            "AE/AEACN", "C66767");

    private static final Map<String, String> QUALIFIER_LABELS = Map.of(//
            "OCCUR", "Occurrence", //
            "STAT", "Completion Status");

    private static final Map<String, String> CORE_DESIGNATIONS = Map.of(//
            "DM/SEX", "Req", //
            "DM/AGE", "Exp");

    private static final Map<String, List<String>> PUBLISHED_VERSIONS = Map.of(//
            "SDTMIG", List.of("3.2", "3.3", "3.4"), //
            "SENDIG", List.of("3.0", "3.1", "3.1.1"));

    @Override
    public Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
            String aDatasetName)
    {
        return known(aStandardName, aStandardVersion)
                ? Optional.ofNullable(DATASET_LABELS.get(aDatasetName))
                : Optional.empty();
    }


    @Override
    public Optional<String> variableLabel(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return known(aStandardName, aStandardVersion)
                ? Optional.ofNullable(VARIABLE_LABELS.get(aDatasetName + "/" + aVariableName))
                : Optional.empty();
    }


    @Override
    public Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return known(aStandardName, aStandardVersion)
                ? Optional.ofNullable(VARIABLE_C_CODES.get(aDatasetName + "/" + aVariableName))
                : Optional.empty();
    }


    @Override
    public Optional<String> qualifierVariableLabel(String aStandardName, String aStandardVersion,
            String aFragment)
    {
        return known(aStandardName, aStandardVersion)
                ? Optional.ofNullable(QUALIFIER_LABELS.get(aFragment))
                : Optional.empty();
    }


    @Override
    public Optional<String> variableCoreDesignation(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return known(aStandardName, aStandardVersion)
                ? Optional.ofNullable(CORE_DESIGNATIONS.get(aDatasetName + "/" + aVariableName))
                : Optional.empty();
    }


    @Override
    public List<String> publishedStandardVersions(String aStandardName)
    {
        // Hyphen-insensitive per the SPI contract: 2.0 documents spell it "SDTM-IG".
        return PUBLISHED_VERSIONS.getOrDefault(aStandardName.replace("-", ""), List.of());
    }


    private static boolean known(String aStandardName, String aStandardVersion)
    {
        // Hyphen-insensitive per the SPI contract: 2.0 documents spell it "SDTM-IG".
        return "SDTMIG".equals(aStandardName.replace("-", "")) && "3.4".equals(aStandardVersion);
    }

}
