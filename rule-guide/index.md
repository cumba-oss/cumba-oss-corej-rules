# The coreJ Rule Authoring Guide

This guide is for people who write **conformance rules** — the checks that read a study's
datasets and report where the data does not conform to a standard.

It assumes you know SDTM, SEND or ADaM. It assumes nothing about the engine that runs your
rule. Where engine behaviour is visible to you as an author, this guide explains it; where it
is not, it stays out of your way.

## What a rule is

A rule is a structured document. It says four things:

| | |
|---|---|
| **Who it is** | an identifier, a description, and which standards it belongs to |
| **Where it applies** | which domains, which data structures, which variables must exist |
| **What it checks** | one expression, evaluated against the data |
| **What it reports** | a message, and the columns whose values go into the finding |

The expression is the heart of it. Everything else exists to put that expression in front of
the right rows.

A rule that finds nothing is silent. A rule whose expression is **true** for a row has found a
violation, and that row becomes a finding.

> **The polarity catches everyone once.** You write the condition that describes the *problem*,
> not the condition that describes correct data. A rule named "AGEMAX must be an ISO duration"
> is authored as *"TSPARMCD is AGEMAX **and** TSVAL is not an ISO duration"*.

## How to read this guide

**If you have never written a rule**, start with
[Your first rule](getting-started/your-first-rule.md). It walks one complete rule from a
sentence of guidance to a passing test, and every later chapter assumes you have seen it.

**If you want the whole picture before the detail**, read
[Anatomy of a rule](getting-started/anatomy-of-a-rule.md). It lists every block a rule file can
contain, grouped by what the block is for — identity, applicability, execution, result,
provenance, release. It is the page to come back to when you know *what* you need but not
*where* it goes.

**If you have a rule and want to see it run**, [Running a rule](getting-started/running-a-rule.md)
is the edit-and-check loop, how to read what comes back, and the five reasons a rule sometimes
does not run at all.

**If you are writing a rule now**, the [Authoring](authoring/identity.md) chapters follow the
order in which you fill a rule in.

**If a rule is behaving in a way you did not expect**, it is almost always one of four things,
and they have a chapter each:

- a value you thought was there is [missing](semantics/values-and-missing.md)
- a [comparison](semantics/comparison.md) behaves differently for the type it is comparing —
  dates most of all
- a [type](semantics/types.md) is not what you assumed
- a column is [absent](semantics/absent-columns.md) from the dataset entirely
- the dataset has [no rows](semantics/zero-rows.md)

**If you need to look something up** — every operator, every function, every field — that is
[Reference](reference/expression-syntax.md). Those pages are generated from the engine itself, so they
cannot describe a function the engine does not have.

## Conventions in this guide

Rules are shown as **YAML** throughout. The shipped packages store the same structure as JSON;
they are the same document, and the editor round-trips both. YAML is used here because
expressions and regular expressions read naturally without escaping.

> **Warnings look like this.** They mark something that is silent when you get it wrong — no
> error, no finding, just a rule that does not do what you meant.

---

**Next:** [Your first rule](getting-started/your-first-rule.md)
