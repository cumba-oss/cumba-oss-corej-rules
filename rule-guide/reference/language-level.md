# Changing the rule language

<!-- Grounded: RuleLanguage.java (LEVEL, surface(), language-surface.txt), RuleLanguageSurfaceTest
     (cumba-corej-core), rules-src/LANGUAGE-LEVEL + RuleLanguageLevelTest (cumba-corej-rules). -->

The rule language has a **level**: one integer that says which language a corpus was written for.
An authoring tool compares it to the engine it runs on and can then say *"this branch was gated
against engine language level N; this instance runs level M"* — which is the only honest sentence
available, because the engine and the corpus both carry the same development version and their
artifact versions say nothing.

The level is **declared and surface-guarded**, not measured per rule. It is not a feature flag:
a rule does not say which level it needs, and the engine does not read the level at run time.

## Where it lives

| side | what | pinned by |
|---|---|---|
| engine (`cumba-corej-core`) | `RuleLanguage.LEVEL` and the golden `language-surface.txt`: the level, then a canonical listing of the **language surface** — every registered function with its parameters, the bound keys of the rule model, the authored enum values | `RuleLanguageSurfaceTest` — the header must equal `LEVEL` **and** the listing must equal the live surface |
| corpus (`cumba-corej-rules`) | `rules-src/LANGUAGE-LEVEL` | `RuleLanguageLevelTest` — must equal the engine's `LEVEL` |

## Why not just compare two numbers

An equality test alone catches bumping one side only. The likely failure is the other one: a
function or a key is added and **neither** side is bumped — the numbers still agree, and the
corpus silently claims a language it was never gated against. So the engine ties its level to the
measured surface: a surface change makes the golden comparison red, and the regenerator
**refuses** to rewrite a changed listing unless the level in code is above the level in the file.
"Regenerate the golden" cannot be the way to make a surface change green.

## When you change the language

1. Change the engine (a function, a parameter, a bound key, an authored enum value).
2. Raise `RuleLanguage.LEVEL` by one.
3. Regenerate the golden: `-Dcorej.languageSurface.regenerate=true` on `RuleLanguageSurfaceTest`.
   The regenerator writes the file only because the level went up; review the diff of the listing.
4. Raise `rules-src/LANGUAGE-LEVEL` to the same number — in **both** corpus twins — in the same
   round, together with the rules that use the new feature.

A rename or a removal is a surface change too. A change that does not move the listing — a bug fix
inside a function, a message, a lint — is not, and needs no bump.

---

**Next:** the [function reference](functions.md) is the part of the surface authors meet most.
