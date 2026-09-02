# Dummy external-dictionary bundle

Stub dictionaries used by `.cdt` scenarios that declare `#dictionaries dummy`.

These are **not** the licensed vendor distributions (MedDRA, WHODrug, SNOMED, …).
They are small hand-made stubs carrying just enough entries for the rule-test
scenarios to execute rather than SKIP on an absent dictionary, so that a
scenario's assertion is about the *rule*, not about dictionary availability.

Vendored into this module on 2026-09-02. They previously lived beside the engine
in the monorepo and were reached across the reactor; the engine is now published
separately, so the path no longer resolves and the copy lives here instead.

To run against real dictionaries, install them and point the engine's dictionary
directory at that location — see the engine's own dictionary documentation.
