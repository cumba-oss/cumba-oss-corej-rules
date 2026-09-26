package net.cumba.corej.core.ruletestsuites.sdtm;

/**
 * Tests for SDTM-IG 3.4 Domain Presence Check rules.
 *
 * <p>
 * All scenarios migrated to extended-CDT files under
 * {@code src/test/resources/net/cumba/corej/core/ruletestsuites/core/CORE-*\/} and were executed by
 * {@code RuleTestSuitesCoreFactoryTest} (⚑ both deleted with the CORE family in 2026-09; since
 * PLAN-dead-code-followups a capture from this suite writes into the captured rule's own family
 * directory, {@code cdisc/}, {@code fda/}, {@code pmda/} or {@code draft/}). The sibling-aware
 * capture writes minimal stub datasets for every domain-presence leaf the rule references, so the
 * factory's self-contained resolver reports the exact study inventory each test requires.
 * </p>
 */
class SdtmDomainPresenceRuleTest extends AbstractSdtmRuleTest
{
}
