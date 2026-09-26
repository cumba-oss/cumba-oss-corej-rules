#!/usr/bin/env python3
"""External oracle for the Define-XML CT and library rules run over the REAL metadata store.

Written for `PLAN-define-ct-evaluation` §6 rows N3 / N3L (`CtRulesRealCtTest`,
`LibraryRulesRealStoreTest`). Those tests pin the findings the engine produces
when a Define-XML document is bound to the pickle-seeded unified store. A pin
copied from a run would only prove the engine agrees with itself — the
self-confirming-fixture trap. This script is the other side: it reads the
CDISC pickles DIRECTLY (never the store, never the engine) and

1. recomputes, from the rule definitions, every finding each rule should raise
   on the document; and
2. adjudicates the findings the engine actually raised (the TSV the tests write
   to `target/define-real-store/<case>.tsv`): each one is TRUE when the pickle
   evidence confirms it, FALSE-POSITIVE when it does not, and every finding the
   oracle expects that the engine did not raise is listed as MISSED.

A FALSE-POSITIVE or a MISSED row is a STOP for that rule kind: do not pin it,
report it. The tests' expected sets were written only after this script
returned all-TRUE, zero-MISSED tables.

⛔ NOT a build-gate tool: nothing in `pom.xml` calls it. The pickle corpus is an
input this repository does not ship; point `--ct-cache` (or
`$CDISC_PICKLE_CACHE_DIR`) at it.

Usage
-----

    # CT rules (N3): the CT ids in the order the run bound them does not
    # matter — the D-2 precedence order is applied here, independently.
    python3 scripts/define-real-store-oracle.py ct \
        --define src/test/resources/fixtures/define-v21-msg-sdtm.xml --version 2.1 \
        --ct sdtmct-2020-12-18 define-xmlct-2020-12-18 \
        [--engine target/define-real-store/msg-ct-21.tsv]

    # Library rules (N3L)
    python3 scripts/define-real-store-oracle.py library \
        --define src/test/resources/fixtures/define-v21-msg-sdtm.xml --version 2.1 \
        [--replace 'Name="STDTMIG"->Name="SDTMIG"'] [--engine target/define-real-store/msg-library-21.tsv]

Output: one TSV-ish table on stdout — `verdict  rule  xpath  value  evidence`.
Exit status 0 when every engine finding is TRUE and nothing is MISSED (or when
no `--engine` file is given), 1 otherwise.

Semantics are re-derived from each rule's `Check:` block and plain text
(`rules-define-src/checks/{CDISC,PMDA}/*.yaml`), with the engine's documented
conventions: a CT codelist is resolved through the enclosing CodeList's own
`Alias[@Context='nci:ExtCodeID']`; the bound packages merge with every
`define-xmlct` package first, then the other roots in first-appearance order,
newest first, first wins per c-code (D-2); a codelist with no conceptId or no
extensible flag is unknown to CT (D-6); IG names fold hyphens and case, and an
IG variant maps to its own product (`SDTMIG-MD 1.1` -> `standards/sdtmig/md-1-1`,
D-11).
"""

from __future__ import annotations

import argparse
import os
import pickle
import sys
from pathlib import Path

from lxml import etree

ODM = "http://www.cdisc.org/ns/odm/v1.3"
NCI = "nci:ExtCodeID"
XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"


# --------------------------------------------------------------------------
# Document helpers (local names only; the engine keys attributes by local name)
# --------------------------------------------------------------------------


def local(el) -> str:
    return etree.QName(el).localname


def attr(el, name: str) -> str | None:
    """Attribute by local name, namespace-blind (def:StandardOID -> StandardOID)."""
    for key, value in el.attrib.items():
        if etree.QName(key).localname == name and key != XML_LANG:
            return value
    return None


def present(value: str | None) -> str | None:
    return value if value is not None and value.strip() else None


def children(el, name: str):
    return [c for c in el if isinstance(c.tag, str) and local(c) == name]


def xpath(el) -> str:
    """The engine's ElementNode.xpath(): OID-qualified, else a 1-based index among >1 siblings."""
    parts = []
    node = el
    while node is not None:
        seg = "/" + local(node)
        oid = node.get("OID")
        parent = node.getparent()
        if oid is not None:
            seg += "[@OID='" + oid + "']"
        elif parent is not None:
            same = children(parent, local(node))
            if len(same) > 1:
                seg += "[" + str(same.index(node) + 1) + "]"
        parts.append(seg)
        node = parent
    return "".join(reversed(parts))


def nci_code(el) -> str | None:
    for alias in children(el, "Alias"):
        if alias.get("Context") == NCI and present(alias.get("Name")):
            return alias.get("Name")
    return None


def enclosing_codelist(el):
    node = el.getparent()
    while node is not None:
        if local(node) == "CodeList":
            return node
        node = node.getparent()
    return None


def english(el, container: str) -> str | None:
    for c in children(el, container):
        for t in children(c, "TranslatedText"):
            lang = (t.get(XML_LANG) or "").lower()
            if lang in ("", "en") or lang.startswith("en-"):
                text = t.text
                if text is not None and text.strip():
                    return text
    return None


def elements(root, name: str):
    return [e for e in root.iter() if isinstance(e.tag, str) and local(e) == name]


def scoped(root, selector: str):
    """`A/B` = element B whose immediate parent is A; a bare name = every element so named."""
    names = selector.split(":")[-1].split("/")
    out = []
    for e in elements(root, names[-1]):
        node, ok = e, True
        for anc in reversed(names[:-1]):
            node = node.getparent()
            if node is None or local(node) != anc:
                ok = False
                break
        if ok:
            out.append(e)
    return out


# --------------------------------------------------------------------------
# Controlled Terminology straight from the pickles
# --------------------------------------------------------------------------


class Codelist:
    def __init__(self, raw: dict, package: str):
        self.c_code = raw["conceptId"]
        self.name = raw.get("name")
        self.extensible = raw["extensible"] in (True, "true", "True")
        self.package = package
        self.terms: dict[str, str] = {}
        for t in raw.get("terms") or []:
            sv, cc = t.get("submissionValue"), t.get("conceptId")
            if sv is not None and cc is not None:
                self.terms.setdefault(sv, cc)

    def describe(self) -> str:
        return (f"{self.package} {self.c_code} '{self.name}' "
                f"extensible={str(self.extensible).lower()} ({len(self.terms)} terms)")


def precedence(ids: list[str]) -> list[str]:
    roots: dict[str, list[str]] = {}
    for i in ids:
        at = i.find("ct-")
        roots.setdefault(i[:at] if at > 0 else i, []).append(i)
    ordered = sorted(roots.pop("define-xml", []), reverse=True)
    for group in roots.values():
        ordered += sorted(group, reverse=True)
    return ordered


class Ct:
    def __init__(self, cache: Path, ids: list[str]):
        self.order = precedence(ids)
        self.by_code: dict[str, Codelist] = {}
        self.by_name: dict[str, Codelist] = {}
        for pkg in self.order:
            with open(cache / f"{pkg}.pkl", "rb") as fh:
                data = pickle.load(fh)
            for raw in data["codelists"]:
                if raw.get("conceptId") is None or raw.get("extensible") is None:
                    continue
                cl = Codelist(raw, pkg)
                self.by_code.setdefault(cl.c_code, cl)
                if cl.name is not None:
                    self.by_name.setdefault(cl.name, cl)

    def enclosing(self, el) -> Codelist | None:
        cl = enclosing_codelist(el)
        code = nci_code(cl) if cl is not None else None
        return self.by_code.get(code) if code else None


# --------------------------------------------------------------------------
# The CT rules
# --------------------------------------------------------------------------


def ct_rules():
    """rule id -> (versions, selector, evaluator)."""

    def membership(attribute, c_code=None, non_ext_only=False, exempt=False, need_std=False):
        def run(ct: Ct, nodes):
            out = []
            for n in nodes:
                if need_std and present(attr(n.getparent(), "StandardOID")) is None:
                    continue
                value = present(attr(n, attribute))
                if value is None:
                    continue
                cl = ct.by_code.get(c_code) if c_code else ct.enclosing(n)
                if cl is None:
                    continue
                if non_ext_only and cl.extensible:
                    continue
                if exempt and attr(n, "ExtendedValue") == "Yes":
                    continue
                if value not in cl.terms:
                    out.append((n, value, f"'{value}' not a submission value of {cl.describe()}"))
            return out
        return run

    def marking(required, need_std=False):
        def run(ct: Ct, nodes):
            out = []
            for n in nodes:
                if need_std and present(attr(n.getparent(), "StandardOID")) is None:
                    continue
                cl = ct.enclosing(n)
                if cl is None:
                    continue
                marked = attr(n, "ExtendedValue") == "Yes"
                value = present(n.get("CodedValue"))
                if required:
                    if cl.extensible and value is not None and value not in cl.terms and not marked:
                        out.append((n, value, f"'{value}' unmarked, not in extensible {cl.describe()}"))
                elif not cl.extensible and marked:
                    out.append((n, value or "", f"'{value}' marked ExtendedValue=Yes in "
                                f"NON-extensible {cl.describe()}"))
            return out
        return run

    def term_code_match(ct: Ct, nodes):
        out = []
        for n in nodes:
            code, value = nci_code(n), present(n.get("CodedValue"))
            cl = ct.enclosing(n)
            if code is None or value is None or cl is None:
                continue
            svs = [sv for sv, cc in cl.terms.items() if cc == code]
            if svs and value not in svs:
                out.append((n, value, f"{code} is '{'/'.join(svs)}' in {cl.describe()}, not '{value}'"))
        return out

    def alias_required(level):
        def run(ct: Ct, nodes):
            out = []
            for n in nodes:
                if nci_code(n) is not None:
                    continue
                if level == "codelist":
                    name = present(n.get("Name"))
                    if name is not None and name in ct.by_name:
                        out.append((n, name, f"no nci:ExtCodeID alias; Name is the CT name of "
                                    f"{ct.by_name[name].describe()}"))
                else:
                    value, cl = present(n.get("CodedValue")), ct.enclosing(n)
                    if value is not None and cl is not None and value in cl.terms:
                        out.append((n, value, f"no alias; '{value}' is {cl.terms[value]} in "
                                    f"{cl.describe()}"))
            return out
        return run

    def code_known(level):
        def run(ct: Ct, nodes):
            out = []
            for n in nodes:
                if n.get("Context") != NCI or present(n.get("Name")) is None:
                    continue
                code = n.get("Name")
                if level == "codelist":
                    if code not in ct.by_code:
                        out.append((n, code, f"c-code {code} is no codelist of {', '.join(ct.order)}"))
                else:
                    cl = ct.enclosing(n)
                    if cl is not None and code not in cl.terms.values():
                        out.append((n, code, f"{code} is no term of {cl.describe()}"))
            return out
        return run

    both, v21, v20 = ("2.0", "2.1"), ("2.1",), ("2.0",)
    return {
        "DEFINE-XML-0132": (v21, "Class", membership("Name", c_code="C103329")),
        "DEFINE-XML-0179": (v21, "EnumeratedItem",
                            membership("CodedValue", exempt=True, need_std=True)),
        "DEFINE-XML-0186": (v21, "EnumeratedItem", marking(True, need_std=True)),
        "DEFINE-XML-0187": (v21, "EnumeratedItem", marking(False)),
        "DEFINE-XML-0192": (v21, "CodeListItem",
                            membership("CodedValue", exempt=True, need_std=True)),
        "DEFINE-XML-0201": (v21, "CodeListItem", marking(True, need_std=True)),
        "DEFINE-XML-0202": (v21, "CodeListItem", marking(False)),
        "PMDA-DD0021-B": (v21, "Standard", membership("Name", c_code="C170452")),
        "PMDA-DD0024": (both, "EnumeratedItem", membership("CodedValue", non_ext_only=True)),
        "PMDA-DD0024-B": (both, "CodeListItem", membership("CodedValue", non_ext_only=True)),
        "PMDA-DD0028": (both, "EnumeratedItem", term_code_match),
        "PMDA-DD0028-B": (both, "CodeListItem", term_code_match),
        "PMDA-DD0029": (both, "EnumeratedItem", marking(True)),
        "PMDA-DD0029-B": (both, "CodeListItem", marking(True)),
        "PMDA-DD0031": (v20, "CodeList", alias_required("codelist")),
        "PMDA-DD0032": (both, "EnumeratedItem", alias_required("term")),
        "PMDA-DD0032-B": (both, "CodeListItem", alias_required("term")),
        "PMDA-DD0033": (both, "CodeList/Alias", code_known("codelist")),
        "PMDA-DD0034": (both, "EnumeratedItem/Alias", code_known("term")),
        "PMDA-DD0034-B": (both, "CodeListItem/Alias", code_known("term")),
        "PMDA-DD0055-C": (v21, "Class", membership("Name", c_code="C103329")),
        "PMDA-DD0099": (both, "AnalysisResult", membership("AnalysisReason", c_code="C117744")),
        "PMDA-DD0100": (both, "AnalysisResult", membership("AnalysisPurpose", c_code="C117745")),
        "PMDA-DD0132": (v21, "EnumeratedItem", marking(False)),
        "PMDA-DD0132-B": (v21, "CodeListItem", marking(False)),
    }


# --------------------------------------------------------------------------
# The CDISC Library straight from standards_details.pkl / standards_models.pkl
# --------------------------------------------------------------------------


class Library:
    def __init__(self, cache: Path):
        with open(cache / "standards_details.pkl", "rb") as fh:
            self.details = pickle.load(fh)
        with open(cache / "standards_models.pkl", "rb") as fh:
            self.models = pickle.load(fh)
        self.asked_missing: set[str] = set()

    @staticmethod
    def family(name: str | None):
        if name is None:
            return None
        folded = name.replace("-", "").replace(" ", "").upper()
        for base in ("SDTMIG", "SENDIG"):
            if folded.startswith(base):
                return base.lower(), folded[len(base):].lower()
        return None

    def ig_key(self, name, version):
        fam = self.family(name)
        if fam is None or not present(version):
            return None
        v = version.strip().replace(".", "-")
        return f"standards/{fam[0]}/{fam[1] + '-' + v if fam[1] else v}"

    def product(self, name, version):
        key = self.ig_key(name, version)
        if key is None:
            return None, None
        prod = self.details.get(key)
        if prod is None:
            self.asked_missing.add(key)
        return key, prod

    @staticmethod
    def dataset(prod, ds_name):
        for c in prod.get("classes") or []:
            for d in c.get("datasets") or []:
                if d.get("name") == ds_name:
                    return d
        for d in prod.get("datasets") or []:
            if d.get("name") == ds_name:
                return d
        return None

    def variable(self, name, version, ds, var):
        key, prod = self.product(name, version)
        if prod is None:
            return key, None
        d = self.dataset(prod, ds)
        if d is None:
            return key, None
        for v in d.get("datasetVariables") or []:
            if v.get("name") == var:
                return key, v
        return key, None

    @staticmethod
    def codelist_ids(var) -> list[str]:
        link = (var.get("_links") or {}).get("codelist")
        if link is None:
            return []
        refs = link if isinstance(link, list) else [link]
        return [r["href"].rstrip("/").rsplit("/", 1)[-1] for r in refs if r.get("href")]

    def published(self, name) -> list[str]:
        fam = self.family(name)
        if fam is None:
            return []
        prefix = f"standards/{fam[0]}/"
        out = []
        for key in self.details:
            if not key.startswith(prefix):
                continue
            tail = key[len(prefix):]
            if not fam[1]:
                if tail and tail[0].isdigit() and "/" not in tail:
                    out.append(tail.replace("-", "."))
            elif tail.startswith(fam[1] + "-"):
                out.append(tail[len(fam[1]) + 1:].replace("-", "."))
        return out

    def qualifier_label(self, name, version, fragment):
        key, prod = self.product(name, version)
        if prod is None:
            return None, None
        href = ((prod.get("_links") or {}).get("model") or {}).get("href")
        if not href or not href.startswith("/mdr/"):
            return None, None
        mkey = "models/" + href[len("/mdr/"):].rstrip("/")
        model = self.models.get(mkey)
        if model is None:
            return mkey, None
        for c in model.get("classes") or []:
            if (c.get("name") or "").lower() not in ("events", "interventions"):
                continue
            for v in c.get("classVariables") or []:
                if v.get("name") == "--" + fragment and v.get("label") is not None:
                    return mkey + " " + c["name"], v["label"]
        return mkey, None


def library_rules():
    root_holder = {}

    def resolve_oid(root, element, oid):
        for e in elements(root, element):
            if e.get("OID") == oid:
                return e
        return None

    def standard_of(el, name_attr="Name", version_attr="Version"):
        n, v = present(el.get(name_attr)), present(el.get(version_attr))
        return (n, v) if n and v else None

    def governing(root, igd):
        oid = attr(igd, "StandardOID")
        if oid:
            std = resolve_oid(root, "Standard", oid)
            if std is not None and standard_of(std):
                return standard_of(std)
        node = igd.getparent()
        while node is not None:
            if local(node) == "MetaDataVersion":
                n, v = attr(node, "StandardName"), attr(node, "StandardVersion")
                return (n, v) if present(n) and present(v) else None
            node = node.getparent()
        return None

    def sdtmig_or_sendig(std):
        s = std[0].replace("-", "")
        return s.startswith("SDTMIG") or s.startswith("SENDIG")

    def sdtm_or_send(std):
        s = std[0].replace("-", "")
        return s.startswith("SDTM") or s.startswith("SEND")

    def binding(root, item_ref):
        igd = item_ref.getparent()
        std = governing(root, igd)
        ds = present(igd.get("Domain")) or present(igd.get("Name"))
        item_def = resolve_oid(root, "ItemDef", item_ref.get("ItemOID") or "")
        var = present(item_def.get("Name")) if item_def is not None else None
        if not (std and ds and item_def is not None and var):
            return None
        return std, ds, var, item_def

    def first_codelist(root, item_def):
        refs = children(item_def, "CodeListRef")
        if not refs or not refs[0].get("CodeListOID"):
            return None
        return resolve_oid(root, "CodeList", refs[0].get("CodeListOID"))

    def dataset_label(lib: Library, root, nodes):
        out = []
        for n in nodes:
            std, name = governing(root, n), present(n.get("Name"))
            if not std or not sdtmig_or_sendig(std) or name is None:
                continue
            key, prod = lib.product(*std)
            d = lib.dataset(prod, name) if prod is not None else None
            doc = english(n, "Description")
            if d is not None and d.get("label") is not None and doc is not None \
                    and doc.strip() != d["label"].strip():
                out.append((n, f"{doc} vs {d['label']}", f"{key} dataset {name} label is "
                            f"'{d['label']}', the document says '{doc}'"))
        return out

    def variable_label(lib: Library, root, nodes):
        out = []
        for n in nodes:
            b = binding(root, n)
            if b is None or not sdtmig_or_sendig(b[0]):
                continue
            key, v = lib.variable(b[0][0], b[0][1], b[1], b[2])
            doc = english(b[3], "Description")
            if v is not None and v.get("label") is not None and doc is not None \
                    and doc.strip() != v["label"].strip():
                out.append((n, f"{b[2]}: {doc} vs {v['label']}", f"{key} {b[1]}.{b[2]} label is "
                            f"'{v['label']}', the document says '{doc}'"))
        return out

    def codelist_ref_required(lib: Library, root, nodes):
        out = []
        for n in nodes:
            b = binding(root, n)
            if b is None:
                continue
            key, v = lib.variable(b[0][0], b[0][1], b[1], b[2])
            ids = lib.codelist_ids(v) if v is not None else []
            if ids and not children(b[3], "CodeListRef"):
                vlm = " (it carries a def:ValueListRef instead)" \
                    if children(b[3], "ValueListRef") else ""
                out.append((n, b[2], f"{key} {b[1]}.{b[2]} has codelist {ids}; ItemDef "
                            f"{b[3].get('OID')} has no CodeListRef{vlm}"))
        return out

    def codelist_ccode(lib: Library, root, nodes):
        out = []
        for n in nodes:
            b = binding(root, n)
            if b is None:
                continue
            key, v = lib.variable(b[0][0], b[0][1], b[1], b[2])
            ids = lib.codelist_ids(v) if v is not None else []
            cl = first_codelist(root, b[3])
            code = nci_code(cl) if cl is not None else None
            if ids and code is not None and code not in ids:
                out.append((n, f"{b[2]}: {code} vs {', '.join(ids)}", f"{key} {b[1]}.{b[2]} "
                            f"codelists {ids}; the document's CodeList {cl.get('OID')} is {code}"))
        return out

    def core_mandatory(lib: Library, root, nodes):
        out = []
        for n in nodes:
            b = binding(root, n)
            if b is None or not sdtm_or_send(b[0]):
                continue
            key, v = lib.variable(b[0][0], b[0][1], b[1], b[2])
            core = v.get("core") if v is not None else None
            mandatory = present(n.get("Mandatory"))
            if core is not None and core.lower() == "req" and mandatory is not None \
                    and mandatory != "Yes":
                out.append((n, b[2], f"{key} {b[1]}.{b[2]} core={core}; Mandatory={mandatory}"))
        return out

    def ct_required(lib: Library, root):
        req = {}
        for n in scoped(root, "ItemGroupDef/ItemRef"):
            b = binding(root, n)
            if b is None:
                continue
            key, v = lib.variable(b[0][0], b[0][1], b[1], b[2])
            if v is None or not lib.codelist_ids(v):
                continue
            cl = first_codelist(root, b[3])
            if cl is not None:
                req.setdefault(cl, f"{key} {b[1]}.{b[2]} -> {lib.codelist_ids(v)}")
        return req

    def ct_alias(level):
        def run(lib: Library, root, nodes):
            req = ct_required(lib, root)
            out = []
            for n in nodes:
                if level == "codelist":
                    if n in req and nci_code(n) is None:
                        out.append((n, n.get("Name") or n.get("OID"),
                                    f"CT required by {req[n]}; CodeList has no nci alias"))
                    continue
                cl = enclosing_codelist(n)
                if cl in req and attr(n, "ExtendedValue") is None and nci_code(n) is None:
                    out.append((n, n.get("CodedValue"), f"CT required by {req[cl]}; item has no "
                                "nci alias and no ExtendedValue"))
            return out
        return run

    def version_known(name_attr, version_attr):
        def run(lib: Library, root, nodes):
            out = []
            for n in nodes:
                name = (attr(n, name_attr) or "").strip()
                version = (attr(n, version_attr) or "").strip()
                if not name or not version:
                    continue
                pub = lib.published(name)
                if pub and version not in pub:
                    out.append((n, f"{name} {version}", f"published {name}: {sorted(pub)}"))
            return out
        return run

    def qualifier_decode(lib: Library, root, nodes):
        std = None
        for e in root.iter():
            if not isinstance(e.tag, str):
                continue
            if local(e) == "Standard":
                s = standard_of(e)
            elif local(e) == "MetaDataVersion":
                n, v = attr(e, "StandardName"), attr(e, "StandardVersion")
                s = (n, v) if present(n) and present(v) else None
            else:
                continue
            if s and sdtmig_or_sendig(s):
                std = s
                break
        if std is None:
            return []
        out, seen = [], set()
        for n in nodes:
            if n.get("Name") != "FATESTCD":
                continue
            cl = first_codelist(root, n)
            if cl is None or cl in seen:
                continue
            seen.add(cl)
            for item in children(cl, "CodeListItem"):
                coded = present(item.get("CodedValue"))
                if coded is None:
                    continue
                where, label = lib.qualifier_label(std[0], std[1], coded)
                decode = english(item, "Decode")
                if label is not None and decode is not None and decode.strip() != label.strip():
                    out.append((item, f"{cl.get('Name', '')} ({coded}: {decode} vs {label})",
                                f"{std[0]} {std[1]} -> {where} --{coded} label '{label}'"))
        return out

    both, v21, v20 = ("2.0", "2.1"), ("2.1",), ("2.0",)
    return {
        "DEFINE-XML-0067": (v21, "ItemGroupDef/ItemRef", core_mandatory),
        "DEFINE-XML-0097": (both, "CodeList", ct_alias("codelist")),
        "DEFINE-XML-0098": (both, "CodeList/EnumeratedItem", ct_alias("item")),
        "DEFINE-XML-0099": (both, "CodeList/CodeListItem", ct_alias("item")),
        "DEFINE-XML-0149": (v21, "ItemGroupDef/ItemRef", codelist_ref_required),
        "DEFINE-XML-0263": (both, "Standard", version_known("Name", "Version")),
        "DEFINE-XML-0263-B": (v20, "MetaDataVersion",
                              version_known("StandardName", "StandardVersion")),
        "DEFINE-XML-0265": (v21, "ItemGroupDef", dataset_label),
        "DEFINE-XML-0266": (v21, "ItemGroupDef/ItemRef", variable_label),
        "PMDA-DD0116": (both, "ItemDef", qualifier_decode),
        "PMDA-DD0118": (both, "ItemGroupDef/ItemRef", codelist_ccode),
        "PMDA-DD0124": (v21, "ItemGroupDef/ItemRef", codelist_ref_required),
        "PMDA-DD0136": (both, "ItemGroupDef", dataset_label),
        "PMDA-DD0137": (v21, "ItemGroupDef/ItemRef", variable_label),
    }


# --------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------


def load_define(path: Path, replacements: list[str]):
    text = path.read_text(encoding="utf-8")
    for r in replacements:
        old, new = r.split("->", 1)
        text = text.replace(old, new)
    return etree.fromstring(text.encode("utf-8"))


def message_templates() -> dict[str, str]:
    """Rule id -> Message template, from the authored corpus beside this script."""
    import yaml

    checks = Path(__file__).resolve().parent.parent / "rules-define-src" / "checks"
    out = {}
    for f in sorted(checks.glob("*/*.yaml")):
        doc = yaml.safe_load(f.read_text(encoding="utf-8"))
        out[doc["Rule_Id"]] = doc.get("Message") or ""
    return out


def read_engine(path: Path):
    """The tests' TSV: rule, xpath, message (header line first)."""
    rows = []
    with open(path, encoding="utf-8") as fh:
        next(fh)
        for line in fh:
            rule, xp, message = line.rstrip("\n").split("\t", 2)
            rows.append((rule, xp, message))
    return rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("mode", choices=["ct", "library"])
    ap.add_argument("--define", required=True, type=Path)
    ap.add_argument("--version", required=True, choices=["2.0", "2.1"])
    ap.add_argument("--ct", nargs="*", default=[])
    ap.add_argument("--replace", nargs="*", default=[],
                    help="OLD->NEW text substitutions applied to the document first")
    ap.add_argument("--engine", type=Path, help="the engine findings TSV to adjudicate")
    ap.add_argument("--ct-cache", type=Path,
                    default=os.environ.get("CDISC_PICKLE_CACHE_DIR"))
    args = ap.parse_args()
    if args.ct_cache is None or not args.ct_cache.is_dir():
        sys.exit("--ct-cache / $CDISC_PICKLE_CACHE_DIR must name the pickle corpus")

    root = load_define(args.define, args.replace)
    expected = {}  # (rule, xpath) -> (value, evidence)
    if args.mode == "ct":
        ct = Ct(args.ct_cache, args.ct)
        print(f"# CT precedence: {', '.join(ct.order)} "
              f"({len(ct.by_code)} codelists by c-code, {len(ct.by_name)} by name)")
        for rule, (versions, selector, run) in sorted(ct_rules().items()):
            if args.version not in versions:
                continue
            for node, value, evidence in run(ct, scoped(root, selector)):
                expected[(rule, xpath(node))] = (value, evidence)
    else:
        lib = Library(args.ct_cache)
        for rule, (versions, selector, run) in sorted(library_rules().items()):
            if args.version not in versions:
                continue
            for node, value, evidence in run(lib, root, scoped(root, selector)):
                expected[(rule, xpath(node))] = (value, evidence)
        if lib.asked_missing:
            print(f"# library products asked for and not held: {sorted(lib.asked_missing)}")

    ok = True
    if args.engine is None:
        for (rule, xp), (value, evidence) in sorted(expected.items()):
            print(f"EXPECTED\t{rule}\t{xp}\t{value}\t{evidence}")
        print(f"# {len(expected)} expected findings")
        return 0
    seen = set()
    templates = message_templates()
    for rule, xp, message in read_engine(args.engine):
        key = (rule, xp)
        hit = expected.get(key)
        # A Message without ${value} (DEFINE-XML-0132, PMDA-DD0055-C) cannot carry the value;
        # the location then identifies the offending node on its own.
        carries_value = "${value}" in templates.get(rule, "${value}")
        if hit is not None and (hit[0] in message or not carries_value):
            seen.add(key)
            print(f"TRUE\t{rule}\t{xp}\t{hit[0]}\t{hit[1]}")
        else:
            ok = False
            print(f"FALSE-POSITIVE\t{rule}\t{xp}\t{message}\t"
                  + ("oracle value differs: " + hit[0] if hit else "the pickles do not support it"))
    for key, (value, evidence) in sorted(expected.items()):
        if key not in seen:
            ok = False
            print(f"MISSED\t{key[0]}\t{key[1]}\t{value}\t{evidence}")
    print(f"# engine findings adjudicated TRUE: {len(seen)}; "
          f"verdict: {'ALL TRUE, NONE MISSED' if ok else 'STOP - see FALSE-POSITIVE / MISSED'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
