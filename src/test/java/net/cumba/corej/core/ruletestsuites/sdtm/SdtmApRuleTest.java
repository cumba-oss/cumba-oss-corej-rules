package net.cumba.corej.core.ruletestsuites.sdtm;

/**
 * Tests for SDTM-IG 3.4 conformance rules — AP domain/category.
 *
 * <p>
 * All AP rule-test scenarios have been migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/core/CORE-*\/} and were executed by
 * {@code RuleTestSuitesCoreFactoryTest} (⚑ both deleted with the CORE family in 2026-09; since
 * PLAN-dead-code-followups a capture from this suite writes into the captured rule's own family
 * directory, {@code cdisc/}, {@code fda/}, {@code pmda/} or {@code draft/}). This class is retained
 * as an anchor: the capture hook in {@link AbstractSdtmRuleTest} regenerates the scenarios when the
 * deleted {@code @Test} methods are restored and the suite is run with
 * {@code -Dgenerate.scenarios=true}.
 * </p>
 */
class SdtmApRuleTest extends AbstractSdtmRuleTest
{
}
