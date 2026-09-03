"""Shared Python reader for the ``Check`` expression form (dual-form support).

One module, two halves:

1. **Parser** — the faithful Python port of the engine's expression pipeline
   (``ExpressionLexer.java`` / ``CheckExpressionParser.java`` /
   ``OperandClassifier.java``), extracted verbatim from
   ``scripts/column-vs-literal-negative-census.py`` (wave 42, ``Fix #257``) so
   every Python corpus tool parses expressions through ONE implementation.

2. **Lowering** — a Python port of ``ExprLowering.java``'s conversion-mode
   lowering (``toCheckConditionForConversion``): an ``Expr`` AST becomes the
   legacy ``Check`` dict (``all:`` / ``any:`` / ``not:`` composites over
   ``{name, operator, value, ...}`` leaves) that the standing tools already
   walk. Consumers call :func:`dual_form_check` on a loaded ``Check`` and get
   the legacy dict whichever form the file authored.

Fidelity contract
-----------------

The lowering targets the *image of* ``CheckToExpr`` — the writer that will
produce the flipped ``rules-src`` (``PLAN-rules-src-expression-flip.md`` §5:
the RuleType-aware raise) — not merely the strict engine load path. It
therefore mirrors ``ExprLowering``'s strict lowering PLUS:

* the **conversion-mode restores** (``ExprLowering.restoreLegacyForms``): the
  regex→predicate optimisation table read backwards (``is_numeric(X)`` →
  ``matches_regex`` with the canonical pattern, ``has_alpha``/``has_digit``,
  ``is_valid_name``/``is_valid_testcd``, the first-letter
  ``between(char(X), char("A"), char("Z"))`` range test) and the demotion of
  per-record accessor calls with no reversible operand (the ``vlm_*`` family,
  ``max_value_length``, the paired code/decode matchers, ``varname()`` /
  ``value()`` / ``record_count()``) back to their bare metadata operand;
* two inverses ``ExprLowering`` does not carry, because their forward halves
  live in ``CheckToExpr`` rather than in the strict surface:

  - ``not <exists-family>(X)`` → the flat negative leaf (``var_not_exists`` /
    ``ds_not_exists`` / ``not_exists``) — the inverse of
    ``CheckToExpr.existsByRuleType``, which spells the RuleType-disambiguated
    generic ``not_exists`` as ``not var_exists(X)``. Without this the 950
    authored ``not_exists`` leaves would lower to a structural ``{not: ...}``
    no standing tool's operator tables recognise (silent vacuity, the exact
    failure mode §7b exists to prevent);
  - ``tuple(c1, c2, …) [not] in <value>`` → the composite ``names:``
    membership leaf — the inverse of ``CheckToExpr.compositeMembershipLeaf``
    (T3; 10 authored files).

Anything else without a legacy surface (measured: the 5 conversion-mode
UNLOWERABLE rules of plan §2 — e.g. the ``^\\s`` leading-space and
``(?i)(AM|PM)`` rewrites over ``upper(X)``/``char(X)``) raises
:class:`LoweringError` **loudly** — never a silent partial dict.

On today's all-legacy corpus (wave 42) both halves are unreachable from the
standing tools: :func:`dual_form_check` returns a legacy ``Check`` unchanged.
"""

from __future__ import annotations

import math
import re

# ---------------------------------------------------------------------------
# Lexer — port of ExpressionLexer.java
# ---------------------------------------------------------------------------

KEYWORDS = {"and": "AND", "or": "OR", "not": "NOT", "in": "IN",
            "true": "TRUE", "false": "FALSE"}

OPERAND_ENDERS = {"IDENT", "NUMBER", "STRING", "RPAREN", "RBRACKET"}


class LexError(Exception):
    pass


def _ident_start(c):
    return c.isalpha() or c in "_$*"


def _ident_part(c):
    return c.isalnum() or c in "_.*-$"


def tokenize(src):
    toks = []
    pos = 0
    prev = None
    n = len(src)
    while True:
        while pos < n and src[pos].isspace():
            pos += 1
        if pos >= n:
            toks.append(("EOF", "", pos))
            return toks
        start = pos
        c = src[pos]
        tok = None
        if prev in OPERAND_ENDERS and c in "+-*/":
            tok = ({"+": "PLUS", "-": "MINUS", "*": "STAR", "/": "SLASH"}[c], c, start)
            pos += 1
        elif c in "()[],":
            tok = ({"(": "LPAREN", ")": "RPAREN", "[": "LBRACKET",
                    "]": "RBRACKET", ",": "COMMA"}[c], c, start)
            pos += 1
        elif c == '"':
            pos += 1
            buf = []
            while pos < n:
                ch = src[pos]
                if ch == "\\" and pos + 1 < n and src[pos + 1] in '"\\':
                    buf.append(src[pos + 1])
                    pos += 2
                    continue
                if ch == '"':
                    pos += 1
                    break
                buf.append(ch)
                pos += 1
            else:
                raise LexError("unterminated string")
            tok = ("STRING", "".join(buf), start)
        elif c == "/":
            pos += 1
            buf = []
            while pos < n:
                ch = src[pos]
                if ch == "\\" and pos + 1 < n:
                    nx = src[pos + 1]
                    buf.append("/" if nx == "/" else ("\\" if nx == "\\" else "\\" + nx))
                    pos += 2
                    continue
                if ch == "/":
                    pos += 1
                    break
                buf.append(ch)
                pos += 1
            else:
                raise LexError("unterminated regex")
            tok = ("REGEX", "".join(buf), start)
        elif c == "=":
            if src[pos + 1:pos + 2] == "=":
                tok, pos = ("EQEQ", "==", start), pos + 2
            elif src[pos + 1:pos + 2] == "~":
                tok, pos = ("MATCH", "=~", start), pos + 2
            else:
                tok, pos = ("EQ", "=", start), pos + 1
        elif c == "!":
            if src[pos + 1:pos + 2] == "=":
                tok, pos = ("NEQ", "!=", start), pos + 2
            elif src[pos + 1:pos + 2] == "~":
                tok, pos = ("NMATCH", "!~", start), pos + 2
            else:
                tok, pos = ("NOT", "!", start), pos + 1
        elif c in "<>":
            withq = "LE" if c == "<" else "GE"
            bare = "LT" if c == "<" else "GT"
            if src[pos + 1:pos + 2] == "=":
                tok, pos = (withq, c + "=", start), pos + 2
            else:
                tok, pos = (bare, c, start), pos + 1
        elif c in "&|":
            if src[pos + 1:pos + 2] != c:
                raise LexError("expected doubled " + c)
            tok, pos = ("AND" if c == "&" else "OR", c * 2, start), pos + 2
        elif c == "`":
            pos += 1
            buf = []
            while pos < n:
                ch = src[pos]
                if ch == "\\" and pos + 1 < n and src[pos + 1] in "`\\":
                    buf.append(src[pos + 1])
                    pos += 2
                    continue
                if ch == "`":
                    pos += 1
                    break
                buf.append(ch)
                pos += 1
            else:
                raise LexError("unterminated quoted ref")
            tok = ("IDENT", "".join(buf), start)
        if tok is None:
            if c == "-":
                if src[pos + 1:pos + 2] == "-":
                    tok, pos = _scan_ident(src, pos)
                elif src[pos + 1:pos + 2].isdigit():
                    tok, pos = _scan_number(src, pos)
                else:
                    raise LexError("unexpected '-'")
            elif c.isdigit():
                tok, pos = _scan_number(src, pos)
            elif _ident_start(c):
                tok, pos = _scan_ident(src, pos)
            else:
                raise LexError("unexpected char %r" % c)
        toks.append(tok)
        prev = tok[0]


def _scan_number(src, pos):
    start = pos
    n = len(src)
    if src[pos] == "-":
        pos += 1
    while pos < n and src[pos].isdigit():
        pos += 1
    if pos < n and src[pos] == "." and src[pos + 1:pos + 2].isdigit():
        pos += 1
        while pos < n and src[pos].isdigit():
            pos += 1
    return ("NUMBER", src[start:pos], start), pos


def _scan_ident(src, pos):
    start = pos
    n = len(src)
    while pos < n:
        c = src[pos]
        if c == "$" and src[pos + 1:pos + 2] == "{":
            pos += 2
            while pos < n and src[pos] != "}":
                pos += 1
            if pos >= n:
                raise LexError("unterminated ${...}")
            pos += 1
            continue
        if not _ident_part(c):
            break
        pos += 1
    text = src[start:pos]
    return (KEYWORDS.get(text, "IDENT"), text, start), pos


# ---------------------------------------------------------------------------
# Operand classification — port of OperandClassifier.java
# ---------------------------------------------------------------------------

DOTTED = re.compile(r"^[A-Z][A-Z0-9]*\.[A-Z][A-Z0-9_]*$")
ADAM_WILDCARD = re.compile(r"[A-Z](xx|zz|y|w)[A-Z0-9]")


def classify(token):
    if token.find("*") >= 0 or "--" in token or "${" in token \
            or ADAM_WILDCARD.search(token):
        return "WILDCARD_COLUMN"
    if token[0] == "$":
        return "OPERATION_REF"
    if DOTTED.match(token):
        return "DOTTED_REF"
    if token[0].islower() or "_" in token:
        # The Java classifier consults the closed BuiltinRegistry and throws on
        # an unregistered name.  Every shipped expression parses in Java, so the
        # registry lookup can only ever say BUILTIN here.
        return "BUILTIN"
    return "COLUMN"


# ---------------------------------------------------------------------------
# AST + parser — port of CheckExpressionParser.java
# ---------------------------------------------------------------------------

class Node:
    __slots__ = ()


class And(Node):
    __slots__ = ("parts",)

    def __init__(self, parts):
        self.parts = parts


class Or(Node):
    __slots__ = ("parts",)

    def __init__(self, parts):
        self.parts = parts


class Not(Node):
    __slots__ = ("inner",)

    def __init__(self, inner):
        self.inner = inner


class Binary(Node):
    __slots__ = ("op", "left", "right")

    def __init__(self, op, left, right):
        self.op, self.left, self.right = op, left, right


class Ref(Node):
    __slots__ = ("name", "kind")

    def __init__(self, name, kind):
        self.name, self.kind = name, kind


class Lit(Node):
    __slots__ = ("kind", "value")

    def __init__(self, kind, value):
        self.kind, self.value = kind, value


class Call(Node):
    __slots__ = ("name", "args", "kwargs")

    def __init__(self, name, args, kwargs):
        self.name, self.args, self.kwargs = name, args, kwargs


CMP = {"EQEQ": "EQ", "NEQ": "NEQ", "LT": "LT", "GT": "GT", "LE": "LE",
       "GE": "GE", "MATCH": "MATCH", "NMATCH": "NMATCH"}


class ParseError(Exception):
    pass


class Parser:
    def __init__(self, toks):
        self.t = toks
        self.i = 0

    def peek(self, k=0):
        j = min(self.i + k, len(self.t) - 1)
        return self.t[j]

    def adv(self):
        if self.i < len(self.t) - 1:
            self.i += 1

    def expect(self, ty):
        if self.peek()[0] != ty:
            raise ParseError("expected %s got %r" % (ty, self.peek()))
        self.adv()

    def parse_or(self):
        first = self.parse_and()
        if self.peek()[0] != "OR":
            return first
        parts = [first]
        while self.peek()[0] == "OR":
            self.adv()
            parts.append(self.parse_and())
        return Or(parts)

    def parse_and(self):
        first = self.parse_unary()
        if self.peek()[0] != "AND":
            return first
        parts = [first]
        while self.peek()[0] == "AND":
            self.adv()
            parts.append(self.parse_unary())
        return And(parts)

    def parse_unary(self):
        if self.peek()[0] == "NOT":
            self.adv()
            return Not(self.parse_unary())
        return self.parse_primary()

    def parse_primary(self):
        if self.peek()[0] == "LPAREN":
            self.adv()
            e = self.parse_or()
            self.expect("RPAREN")
            return e
        return self.parse_comparison()

    def parse_comparison(self):
        left = self.parse_sum()
        ty = self.peek()[0]
        if ty in CMP:
            self.adv()
            return Binary(CMP[ty], left, self.parse_sum())
        if ty == "IN":
            self.adv()
            return Binary("IN", left, self.parse_sum())
        if ty == "NOT" and self.peek(1)[0] == "IN":
            self.adv()
            self.adv()
            return Binary("NOT_IN", left, self.parse_sum())
        return left

    def parse_sum(self):
        left = self.parse_product()
        while self.peek()[0] in ("PLUS", "MINUS"):
            op = "ADD" if self.peek()[0] == "PLUS" else "SUB"
            self.adv()
            left = Binary(op, left, self.parse_product())
        return left

    def parse_product(self):
        left = self.parse_atom()
        while self.peek()[0] in ("STAR", "SLASH"):
            op = "MUL" if self.peek()[0] == "STAR" else "DIV"
            self.adv()
            left = Binary(op, left, self.parse_atom())
        return left

    def parse_atom(self):
        ty, text, _ = self.peek()
        if ty == "STRING":
            self.adv()
            return Lit("STRING", text)
        if ty == "NUMBER":
            self.adv()
            return Lit("NUMBER", float(text))
        if ty == "REGEX":
            self.adv()
            return Lit("REGEX", text)
        if ty == "TRUE":
            self.adv()
            return Lit("BOOL", True)
        if ty == "FALSE":
            self.adv()
            return Lit("BOOL", False)
        if ty == "LPAREN":
            self.adv()
            e = self.parse_sum()
            self.expect("RPAREN")
            return e
        if ty == "LBRACKET":
            return self.parse_list()
        if ty == "IDENT":
            self.adv()
            if self.peek()[0] == "LPAREN":
                return self.parse_call(text)
            return Ref(text, classify(text))
        raise ParseError("expected operand, got %r" % (self.peek(),))

    def parse_list(self):
        self.expect("LBRACKET")
        items = []
        if self.peek()[0] != "RBRACKET":
            items.append(self.parse_sum())
            while self.peek()[0] == "COMMA":
                self.adv()
                items.append(self.parse_sum())
        self.expect("RBRACKET")
        return Lit("LIST", items)

    def parse_call(self, name):
        self.expect("LPAREN")
        args, kwargs = [], {}
        if self.peek()[0] != "RPAREN":
            self._arg(args, kwargs)
            while self.peek()[0] == "COMMA":
                self.adv()
                self._arg(args, kwargs)
        self.expect("RPAREN")
        return Call(name, args, kwargs)

    def _arg(self, args, kwargs):
        if self.peek()[0] == "IDENT" and self.peek(1)[0] == "EQ":
            key = self.peek()[1]
            self.adv()
            self.adv()
            kwargs[key] = self.parse_sum()
            return
        args.append(self.parse_sum())


def parse(src):
    p = Parser(tokenize(src))
    e = p.parse_or()
    if p.peek()[0] != "EOF":
        raise ParseError("trailing input %r" % (p.peek(),))
    return e


# ===========================================================================
# Lowering — port of ExprLowering.java (conversion mode) + the CheckToExpr
# inverses documented in the module docstring.
# ===========================================================================

class LoweringError(Exception):
    """Mirror of the Java ``ExpressionException``: the expression has no
    faithful legacy ``Check`` surface. Always raised loudly, never a partial
    dict."""


def _unsupported(detail):
    return LoweringError("Expression construct not supported by the legacy "
                         "lowering: " + detail)


# ---- metadata operand mapping (port of MetadataOperandMapping.java) -------

# Accessor function -> (scope, supported levels). Scope "V" = variable
# (two-arg / level-only core form), "D" = dataset (one-arg core form).
_METADATA_ATTRIBUTES = {
    "var_name": "V", "var_label": "V", "var_type": "V", "var_length": "V",
    "var_format": "V", "var_role": "V", "var_core": "V", "var_mandatory": "V",
    "var_codelist": "V", "var_ordinal": "V", "var_ccode": "V",
    "var_codelist_coded_codes": "V", "var_codelist_coded_values": "V",
    "var_codelist_extensible": "V", "var_codelist_extended_values": "V",
    "var_origin_type": "V", "var_has_comment": "V", "var_has_codelist": "V",
    "var_has_method": "V", "var_external_dictionary": "V",
    "var_external_dictionary_version": "V",
    "ds_name": "D", "ds_label": "D", "ds_class": "D", "ds_domain": "D",
    "ds_structure": "D",
}

_OPERAND_PREFIX = {
    ("V", "DATA"): "variable_", ("V", "DEFINE"): "define_variable_",
    ("V", "LIBRARY"): "library_variable_",
    ("D", "DATA"): "dataset_", ("D", "DEFINE"): "define_dataset_",
    ("D", "LIBRARY"): "library_dataset_",
}

# vlm_* accessor -> operand (MetadataOperandMapping.VLM_OPERAND_SUFFIXES,
# reversed).
_VLM_ACCESSOR_TO_OPERAND = {
    "vlm_value_length": "variable_value_length",
    "vlm_data_type": "define_vlm_data_type",
    "vlm_length": "define_vlm_length",
    "vlm_mandatory": "define_vlm_mandatory",
    "vlm_codelist_coded_values": "define_vlm_codelist_coded_values",
    "vlm_codelist_coded_codes": "define_vlm_codelist_coded_codes",
    "vlm_type_conforms": "define_vlm_type_conforms",
    "vlm_codelist_extensible": "define_vlm_codelist_extensible",
    "vlm_has_codelist": "define_vlm_has_codelist",
    "vlm_decode_matches": "define_vlm_decode_matches",
}


def _is_varname_anchor(e):
    """The bare ``variable_name`` operand or the ``varname()`` call."""
    if isinstance(e, Ref) and e.name == "variable_name":
        return True
    return (isinstance(e, Call) and e.name == "varname"
            and not e.args and not e.kwargs)


def _metadata_level(e):
    """The DATA/DEFINE/LIBRARY level named by a string literal, or None."""
    if isinstance(e, Lit) and e.kind == "STRING":
        level = e.value.strip().upper()
        if level in ("DATA", "DEFINE", "LIBRARY"):
            return level
    return None


def reverse_to_operand(e):
    """Port of ``MetadataOperandMapping.reverseToOperand``: the operand name
    for a reversible ``var_*`` / ``ds_*`` accessor call, or ``None``."""
    if not isinstance(e, Call) or e.kwargs:
        return None
    scope = _METADATA_ATTRIBUTES.get(e.name)
    if scope is None:
        return None
    if scope == "V":
        if len(e.args) == 1:
            level_arg = e.args[0]
        elif len(e.args) == 2 and _is_varname_anchor(e.args[0]):
            level_arg = e.args[1]
        else:
            return None
    else:
        if len(e.args) != 1:
            return None
        level_arg = e.args[0]
    level = _metadata_level(level_arg)
    if level is None:
        return None
    suffix = e.name[4:] if e.name.startswith("var_") else e.name[3:]
    if suffix == "type":
        suffix = "data_type"
    return _OPERAND_PREFIX[(scope, level)] + suffix


def reverse_any_accessor(e):
    """Port of ``MetadataOperandMapping.reverseAnyAccessor``: the operand name
    for ANY resolvable per-record accessor call, or ``None``."""
    operand = reverse_to_operand(e)
    if operand is not None:
        return operand
    if not isinstance(e, Call):
        return None
    if not e.args and not e.kwargs:
        cursor = {"varname": "variable_name", "value": "variable_value",
                  "record_count": "record_count"}.get(e.name)
        if cursor is not None:
            return cursor
    if e.name in _VLM_ACCESSOR_TO_OPERAND:
        return _VLM_ACCESSOR_TO_OPERAND[e.name]
    if e.name == "max_value_length":
        return "variable_max_size"
    if e.name in ("library_variable_code_pair_matches",
                  "define_variable_decode_matches"):
        return e.name
    return None


# ---- conversion-mode restores (ExprLowering.restoreLegacyForms) -----------

# Predicate function -> the matches_regex pattern it was raised from
# (ExprLowering.PREDICATE_TO_REGEX).
_PREDICATE_TO_REGEX = {
    "is_numeric": "^-?(0|[1-9]\\d*)(\\.\\d+)?$",
    "has_alpha": ".*[a-zA-Z].*",
    "has_digit": ".*[0-9].*",
    "is_valid_name": "^[A-Z_][A-Z0-9_]{0,7}$",
    "is_valid_testcd": "^[a-zA-Z_][a-zA-Z0-9_]{0,7}$",
}

_FIRST_LETTER_REGEX = "^[A-Z]"


def _char_argument(e):
    """The single argument of a ``char(X)`` call, or ``None``."""
    if (isinstance(e, Call) and e.name == "char" and len(e.args) == 1
            and not e.kwargs):
        return e.args[0]
    return None


def _is_char_literal(e, letter):
    arg = _char_argument(e)
    return (isinstance(arg, Lit) and arg.kind == "STRING" and arg.value == letter)


def _first_letter_range_operand(c):
    """The operand of ``between(char(X), char("A"), char("Z"))``, or None."""
    if c.name != "between" or len(c.args) != 3 or c.kwargs:
        return None
    subject = _char_argument(c.args[0])
    if subject is None or not _is_char_literal(c.args[1], "A") \
            or not _is_char_literal(c.args[2], "Z"):
        return None
    return subject


def _regex_form(e, negated):
    """The ``X =~ /pattern/`` (or ``!~``) restoration for a predicate call the
    regex optimiser produced, or ``None``."""
    if not isinstance(e, Call) or e.kwargs:
        return None
    op = "NMATCH" if negated else "MATCH"
    pattern = _PREDICATE_TO_REGEX.get(e.name)
    if pattern is not None and len(e.args) == 1:
        return Binary(op, _restore_legacy_forms(e.args[0]), Lit("REGEX", pattern))
    operand = _first_letter_range_operand(e)
    if operand is not None:
        return Binary(op, _restore_legacy_forms(operand),
                      Lit("REGEX", _FIRST_LETTER_REGEX))
    return None


def _demoted_operand(c):
    """The bare metadata-operand name a non-reversible per-record accessor
    demotes to in conversion mode, or ``None``."""
    if reverse_to_operand(c) is not None:
        return None
    return reverse_any_accessor(c)


def _restore_legacy_forms(e):
    """Port of ``ExprLowering.restoreLegacyForms`` (conversion mode pre-pass)."""
    if isinstance(e, Not):
        restored = _regex_form(e.inner, True)
        return restored if restored is not None else Not(_restore_legacy_forms(e.inner))
    if isinstance(e, Call):
        restored = _regex_form(e, False)
        if restored is not None:
            return restored
        operand = _demoted_operand(e)
        if operand is not None:
            return Ref(operand, "BUILTIN")
        return Call(e.name, [_restore_legacy_forms(a) for a in e.args],
                    {k: _restore_legacy_forms(v) for k, v in e.kwargs.items()})
    if isinstance(e, Binary):
        return Binary(e.op, _restore_legacy_forms(e.left),
                      _restore_legacy_forms(e.right))
    if isinstance(e, And):
        return And([_restore_legacy_forms(p) for p in e.parts])
    if isinstance(e, Or):
        return Or([_restore_legacy_forms(p) for p in e.parts])
    if isinstance(e, Lit) and e.kind == "LIST":
        return Lit("LIST", [_restore_legacy_forms(i) for i in e.value])
    return e


# ---- operand helpers (ExprLowering) ---------------------------------------

_UNARY_WRAPPERS = {"date", "num", "len", "date_part", "time_part",
                   "lowcase", "upcase"}


def _wrapper_of(e):
    if (isinstance(e, Call) and not e.kwargs and len(e.args) == 1
            and e.name in _UNARY_WRAPPERS):
        return e.name
    return None


def _unwrap(e):
    return e.args[0] if _wrapper_of(e) is not None else e


def _current_variable_operand(e):
    """``varname() -> variable_name``, ``value() -> variable_value``."""
    if isinstance(e, Call) and not e.args and not e.kwargs:
        if e.name == "varname":
            return "variable_name"
        if e.name == "value":
            return "variable_value"
    return None


def _describe(e):
    return type(e).__name__


def _reference_name(e):
    inner = _unwrap(e)
    current = _current_variable_operand(inner)
    if current is not None:
        return current
    operand = reverse_to_operand(inner)
    if operand is not None:
        return operand
    if isinstance(inner, Ref):
        return inner.name
    raise _unsupported("expected a column/reference operand but found "
                       + _describe(e))


def _name_of(e):
    inner = _unwrap(e)
    current = _current_variable_operand(inner)
    if current is not None:
        return current
    operand = reverse_to_operand(inner)
    if operand is not None:
        return operand
    if isinstance(inner, Ref):
        return inner.name
    raise _unsupported("the left side of a comparison must be a column "
                       "reference, found " + _describe(e))


def _is_col_ref(e):
    """A ``colref(X)`` two-hop dereference wrapper."""
    return (isinstance(e, Call) and not e.kwargs and len(e.args) == 1
            and e.name == "colref")


def _number_value(x):
    """Mirror of ``ExprLowering.numberNode``: a whole double becomes an int."""
    if isinstance(x, float) and math.isfinite(x) and x == int(x):
        return int(x)
    return x


def _literal_value(lit):
    if not isinstance(lit, Lit):
        raise _unsupported("expected a literal but found " + _describe(lit))
    if lit.kind in ("STRING", "REGEX"):
        return lit.value
    if lit.kind == "BOOL":
        return lit.value
    if lit.kind == "NUMBER":
        return _number_value(lit.value)
    if lit.kind == "LIST":
        out = []
        for item in lit.value:
            if not isinstance(item, Lit):
                raise _unsupported("list elements must be literals")
            out.append(_literal_value(item))
        return out
    raise _unsupported("unsupported literal kind " + str(lit.kind))


def _as_int(e):
    if isinstance(e, Lit) and e.kind == "NUMBER":
        return int(round(e.value))
    raise _unsupported("expected a numeric length but found " + _describe(e))


def _apply_value(leaf, value):
    """Port of ``ExprLowering.applyValue``: sets value / value_is_literal /
    value_is_reference on the leaf dict from a right-hand operand."""
    if _is_col_ref(value):
        leaf["value"] = _reference_name(value.args[0])
        leaf["value_is_reference"] = True
    elif isinstance(value, Lit):
        leaf["value"] = _literal_value(value)
        leaf["value_is_literal"] = True
    else:
        leaf["value"] = _reference_name(value)
    return leaf


def _value_leaf(name, operator, value):
    return _apply_value({"name": name, "operator": operator}, value)


# ---- the lowering proper (ExprLowering.lowerBool and friends) -------------

_NEGATIVE_BY_POSITIVE_EXISTS = {"exists": "not_exists",
                                "var_exists": "var_not_exists",
                                "ds_exists": "ds_not_exists"}

_FUNCTION_OPERATORS = {
    "has_multiple_values_for", "is_not_unique_relationship", "is_not_unique_set",
    "is_unique_set", "is_inconsistent_across_dataset", "shares_no_elements_with",
    "not_contains_all", "has_same_values", "is_not_ordered_subset_of",
    "inconsistent_enumerated_columns", "has_not_equal_length", "has_equal_length",
    "is_not_unique_value", "present_on_multiple_rows_within",
    "not_present_on_multiple_rows_within", "empty_within_except_last_row",
    "does_not_have_next_corresponding_record", "does_not_equal_string_part",
}

_POSITIVE_TO_NEGATIVE = {
    "is_unique_relationship": "is_not_unique_relationship",
    "contains_all": "not_contains_all",
    "shares_elements_with": "shares_no_elements_with",
    "is_ordered_subset_of": "is_not_ordered_subset_of",
    "is_unique_value": "is_not_unique_value",
    "is_unique_set": "is_not_unique_set",
}

_UNARY_PREDICATES = {
    "exists", "not_exists", "ds_exists", "ds_not_exists", "var_exists",
    "var_not_exists", "var_is_null", "empty", "non_empty", "is_complete_date",
    "is_incomplete_date", "invalid_date", "invalid_duration", "is_integer",
    "is_not_integer", "is_complete_date_part", "is_not_complete_date_part",
}

_EXISTS_PREDICATES = {"exists", "not_exists", "ds_exists", "ds_not_exists",
                      "var_exists", "var_not_exists"}

_SUBSTRING_PREDICATES = {"contains", "does_not_contain", "starts_with",
                         "ends_with"}


def _exists_arg_name(predicate, arg):
    """Port of ``ExprLowering.existsArgName``: the exists-family argument may
    be a string literal or a reference; ``ds_*`` requires a plain name."""
    if isinstance(arg, Lit) and arg.kind == "STRING":
        name = arg.value
    else:
        name = _name_of(arg)
    if predicate in ("ds_exists", "ds_not_exists") \
            and ("." in name or "=" in name or "${" in name or "--" in name):
        raise _unsupported(predicate + " expects a plain dataset name")
    return name


def _lower_bool(e):
    if isinstance(e, And):
        return {"all": [_lower_bool(p) for p in e.parts]}
    if isinstance(e, Or):
        return {"any": [_lower_bool(p) for p in e.parts]}
    if isinstance(e, Not):
        return _lower_not(e.inner)
    if isinstance(e, Binary):
        return _lower_binary(e)
    if isinstance(e, Call):
        return _lower_predicate(e)
    if isinstance(e, Ref):
        raise _unsupported("bare reference '" + e.name
                           + "' is not a boolean condition")
    raise _unsupported("a literal is not a boolean condition")


def _lower_not(inner):
    if isinstance(inner, Call):
        c = inner
        if c.name in ("prefix_matches", "suffix_matches"):
            return _affix_regex_leaf(c, True)
        if c.name == "equalsIgnoreCase":
            return _two_arg(c, "not_equal_to_case_insensitive")
        if c.name == "is_sorted_by":
            return _sorted_by_leaf(c)
        if c.name == "present_on_multiple_rows_within":
            return _function_operator_leaf(c, "not_present_on_multiple_rows_within")
        if c.name == "has_next_corresponding_record":
            return _function_operator_leaf(c, "does_not_have_next_corresponding_record")
        negative = _POSITIVE_TO_NEGATIVE.get(c.name)
        if negative is not None:
            return _function_operator_leaf(c, negative)
        if c.name == "empty":
            return _unary_negation_leaf(c, "non_empty")
        if c.name == "is_integer":
            return _unary_negation_leaf(c, "is_not_integer")
        if c.name == "is_complete_date_part":
            return _unary_negation_leaf(c, "is_not_complete_date_part")
        if c.name == "contains":
            # Inverse of CheckToExpr's case-insensitive-contains fold:
            # `not contains(upper(X), upper("v"))` was raised from the
            # `does_not_contain_case_insensitive` leaf.
            folded = _case_insensitive_contains(c)
            if folded is not None:
                return _value_leaf(_name_of(folded[0]),
                                   "does_not_contain_case_insensitive",
                                   folded[1])
            return _substring_negation_leaf(c, "does_not_contain")
        # Inverse of CheckToExpr.existsByRuleType (see module docstring): the
        # writer spells the RuleType-disambiguated `not_exists` as
        # `not var_exists(X)` / `not ds_exists(X)`. Reconstruct the flat
        # negative leaf the standing tools' operator tables key on.
        negative = _NEGATIVE_BY_POSITIVE_EXISTS.get(c.name)
        if negative is not None and len(c.args) == 1 and not c.kwargs:
            return {"name": _exists_arg_name(c.name, c.args[0]),
                    "operator": negative}
    return {"not": _lower_bool(inner)}


def _unary_negation_leaf(c, negative_operator):
    _require_args(c.name, len(c.args), 1)
    return {"name": _name_of(c.args[0]), "operator": negative_operator}


def _substring_negation_leaf(c, negative_operator):
    _require_args(c.name, len(c.args), 2)
    return _value_leaf(_name_of(c.args[0]), negative_operator, c.args[1])


def _affix_call(e):
    """A ``prefix(X, n)`` / ``suffix(X, n)`` affix value-function call."""
    if (isinstance(e, Call) and not e.kwargs and len(e.args) == 2
            and e.name in ("prefix", "suffix") and isinstance(e.args[0], Ref)
            and isinstance(e.args[1], Lit) and e.args[1].kind == "NUMBER"):
        return e
    return None


def _is_tuple_call(e):
    """The T3 composite-key ``tuple(c1, c2, …)`` value function."""
    return (isinstance(e, Call) and e.name == "tuple" and not e.kwargs
            and len(e.args) >= 1 and all(isinstance(a, Ref) for a in e.args))


def _is_str_call(e):
    return (isinstance(e, Call) and not e.kwargs and len(e.args) == 1
            and e.name == "str")


def _is_upper_call(e):
    return (isinstance(e, Call) and not e.kwargs and len(e.args) == 1
            and e.name == "upper")


def _case_insensitive_contains(c):
    """``(subject, literal)`` for a ``contains(upper(X), upper("v"))`` fold
    (CheckToExpr's ``contains_case_insensitive`` raise), else ``None``."""
    if (c.name == "contains" and len(c.args) == 2 and not c.kwargs
            and _is_upper_call(c.args[0]) and _is_upper_call(c.args[1])
            and isinstance(c.args[1].args[0], Lit)):
        return c.args[0].args[0], c.args[1].args[0]
    return None


def _is_arith(op):
    return op in ("ADD", "SUB", "MUL", "DIV")


def _lower_binary(b):
    if b.op in ("MATCH", "NMATCH"):
        affix = _affix_call(b.left)
        if affix is not None:
            return _affix_regex_match_leaf(b, affix)
        return _regex_leaf(b)
    affix = _affix_call(b.left)
    if affix is not None:
        return _affix_compare_leaf(b, affix)
    if _is_str_call(b.left) and _is_str_call(b.right):
        return _type_insensitive_leaf(b)
    if b.op in ("IN", "NOT_IN"):
        if _is_upper_call(b.left):
            return _case_insensitive_membership_leaf(b)
        if _is_tuple_call(b.left):
            # Inverse of CheckToExpr.compositeMembershipLeaf (T3): reconstruct
            # the composite `names:` membership leaf.
            op = "is_contained_by" if b.op == "IN" else "is_not_contained_by"
            leaf = {"names": [a.name for a in b.left.args], "operator": op}
            return _apply_value(leaf, b.right)
        op = "is_contained_by" if b.op == "IN" else "is_not_contained_by"
        return _value_leaf(_name_of(b.left), op, b.right)
    if isinstance(b.right, Binary) and _is_arith(b.right.op):
        return _arithmetic_leaf(b, b.right)

    lw = _wrapper_of(b.left)
    rw = _wrapper_of(b.right)
    li = _unwrap(b.left)
    ri = _unwrap(b.right)

    if lw == "len" or rw == "len":
        return _length_leaf(b, lw, li, ri, rw)
    if _is_case_wrapper(lw) or _is_case_wrapper(rw):
        if not _is_case_wrapper(lw):
            raise _unsupported("a case-insensitive comparison needs "
                               "lowcase/upcase on the left (column) operand")
        right_ok = _is_case_wrapper(rw) or isinstance(b.right, Lit)
        if not right_ok:
            raise _unsupported("one-sided lowcase/upcase against a bare "
                               "reference is ambiguous; wrap both sides or "
                               "use equalsIgnoreCase(...)")
        return _case_insensitive_leaf(b.op, li, ri)
    family = _comparison_family(lw, rw)
    return _value_leaf(_name_of(li), _comparison_operator(family, b.op, li), ri)


def _regex_leaf(b):
    if not (isinstance(b.right, Lit) and b.right.kind == "REGEX"):
        raise _unsupported("the right-hand side of =~ / !~ must be a "
                           "/regex/ literal")
    op = "matches_regex" if b.op == "MATCH" else "not_matches_regex"
    return {"name": _name_of(b.left), "operator": op, "value": b.right.value,
            "value_is_literal": True}


def _length_leaf(b, lw, li, ri, rw):
    if lw != "len" or rw == "len":
        raise _unsupported("len(...) must be the left operand of a length "
                           "comparison")
    op = {"GT": "longer_than", "GE": "longer_than_or_equal_to",
          "LT": "shorter_than", "LE": "shorter_than_or_equal_to",
          "EQ": "has_equal_length", "NEQ": "has_not_equal_length"}.get(b.op)
    if op is None:
        raise _unsupported("len(...) supports only >, >=, <, <=, == and != "
                           "comparisons")
    return {"name": _name_of(li), "operator": op, "value": _literal_value(ri),
            "value_is_literal": True}


def _is_case_wrapper(w):
    return w in ("lowcase", "upcase")


def _case_insensitive_leaf(op, li, ri):
    operator = {"EQ": "equal_to_case_insensitive",
                "NEQ": "not_equal_to_case_insensitive"}.get(op)
    if operator is None:
        raise _unsupported("lowcase/upcase comparisons support only == and !=")
    return _value_leaf(_name_of(li), operator, ri)


def _comparison_family(lw, rw):
    w = lw if lw is not None else rw
    if w in ("date", "date_part", "time_part"):
        return w
    return "plain"


def _comparison_operator(family, op, li):
    if family == "date":
        table = {"EQ": "date_equal_to", "NEQ": "date_not_equal_to",
                 "LT": "date_less_than", "GT": "date_greater_than",
                 "LE": "date_less_than_or_equal_to",
                 "GE": "date_greater_than_or_equal_to"}
    elif family == "date_part":
        table = {"EQ": "date_part_equal_to", "NEQ": "date_part_not_equal_to"}
    elif family == "time_part":
        table = {"EQ": "time_part_equal_to", "NEQ": "time_part_not_equal_to"}
    else:
        table = {"EQ": "equal_to", "NEQ": "not_equal_to", "LT": "less_than",
                 "GT": "greater_than", "LE": "less_than_or_equal_to",
                 "GE": "greater_than_or_equal_to"}
    operator = table.get(op)
    if operator is None:
        raise _unsupported("unsupported %s comparison for operand %s"
                           % (family, _describe(li)))
    return operator


def _arithmetic_leaf(b, rhs):
    if b.op != "NEQ":
        raise _unsupported("arithmetic comparison supports only != "
                           "(not_equal_to_*)")
    name = _name_of(b.left)
    if rhs.op == "DIV" and isinstance(rhs.left, Ref) and isinstance(rhs.right, Ref):
        return _arith_leaf(name, "not_equal_to_divide", rhs.left.name,
                           rhs.right.name)
    if rhs.op == "SUB" and isinstance(rhs.left, Ref) and isinstance(rhs.right, Ref):
        return _arith_leaf(name, "not_equal_to_subtract", rhs.left.name,
                           rhs.right.name)
    if (rhs.op == "MUL" and _is_hundred(rhs.right)
            and isinstance(rhs.left, Binary) and rhs.left.op == "DIV"
            and isinstance(rhs.left.left, Binary) and rhs.left.left.op == "SUB"
            and isinstance(rhs.left.left.left, Ref)
            and isinstance(rhs.left.left.right, Ref)
            and isinstance(rhs.left.right, Ref)
            and rhs.left.left.right.name == rhs.left.right.name):
        return _arith_leaf(name, "not_equal_to_pctchg", rhs.left.left.left.name,
                           rhs.left.right.name)
    raise _unsupported("unrecognised arithmetic shape (native-only, no "
                       "legacy operator)")


def _arith_leaf(name, operator, a, b):
    return {"name": name, "operator": operator, "value": [a, b]}


def _is_hundred(e):
    return isinstance(e, Lit) and e.kind == "NUMBER" and e.value == 100.0


def _lower_predicate(c):
    n = c.name
    argc = len(c.args)
    if n in _FUNCTION_OPERATORS:
        return _function_operator_leaf(c, n)
    negative = _POSITIVE_TO_NEGATIVE.get(n)
    if negative is not None:
        return {"not": _function_operator_leaf(c, negative)}
    if n == "invalid_duration" and "negative" in c.kwargs:
        _require_args(n, argc, 1)
        neg = c.kwargs["negative"]
        if not (isinstance(neg, Lit) and neg.kind == "BOOL"):
            raise _unsupported("invalid_duration negative= must be a boolean "
                               "literal")
        return {"name": _name_of(c.args[0]), "operator": n,
                "negative": neg.value}
    if n in _UNARY_PREDICATES:
        _require_args(n, argc, 1)
        if n in _EXISTS_PREDICATES:
            name = _exists_arg_name(n, c.args[0])
        else:
            name = _name_of(c.args[0])
        return {"name": name, "operator": n}
    if n in _SUBSTRING_PREDICATES:
        _require_args(n, argc, 2)
        if n == "contains":
            # Inverse of CheckToExpr's case-insensitive-contains fold:
            # `contains(upper(X), upper("v"))` was raised from the
            # `contains_case_insensitive` leaf.
            folded = _case_insensitive_contains(c)
            if folded is not None:
                return _value_leaf(_name_of(folded[0]),
                                   "contains_case_insensitive", folded[1])
        return _value_leaf(_name_of(c.args[0]), n, c.args[1])
    if n == "equalsIgnoreCase":
        return _two_arg(c, "equal_to_case_insensitive")
    if n in ("prefix_matches", "suffix_matches"):
        return _affix_regex_leaf(c, False)
    raise _unsupported("function '" + n + "' has no legacy lowering")


def _two_arg(c, operator):
    _require_args(c.name, len(c.args), 2)
    return _value_leaf(_name_of(c.args[0]), operator, c.args[1])


def _affix_regex_leaf(c, negated):
    kind = "prefix" if c.name.startswith("prefix") else "suffix"
    argc = len(c.args)
    if argc not in (2, 3):
        raise _unsupported(c.name + "(...) expects 2 or 3 arguments but got "
                           + str(argc))
    pat = c.args[1]
    if not (isinstance(pat, Lit) and pat.kind == "REGEX"):
        raise _unsupported(c.name + "(...) requires a /regex/ literal as its "
                           "second argument")
    operator = ("not_" if negated else "") + kind + "_matches_regex"
    leaf = {"name": _name_of(c.args[0]), "operator": operator,
            "value": pat.value, "value_is_literal": True}
    if argc == 3:
        leaf[kind] = _as_int(c.args[2])
    return leaf


def _affix_regex_match_leaf(b, affix):
    kind = affix.name
    if not (isinstance(b.right, Lit) and b.right.kind == "REGEX"):
        raise _unsupported("the right-hand side of =~ / !~ must be a "
                           "/regex/ literal")
    negated = b.op == "NMATCH"
    operator = ("not_" if negated else "") + kind + "_matches_regex"
    leaf = {"name": affix.args[0].name, "operator": operator,
            "value": b.right.value, "value_is_literal": True}
    leaf[kind] = _as_int(affix.args[1])
    return leaf


def _affix_compare_leaf(b, affix):
    kind = affix.name
    operator = {"EQ": kind + "_equal_to", "NEQ": kind + "_not_equal_to",
                "IN": kind + "_is_contained_by",
                "NOT_IN": kind + "_is_not_contained_by"}.get(b.op)
    if operator is None:
        raise _unsupported(kind + "(...) supports only ==, !=, in and "
                           "'not in' comparisons")
    leaf = {"name": affix.args[0].name, "operator": operator}
    leaf[kind] = _as_int(affix.args[1])
    return _apply_value(leaf, b.right)


def _type_insensitive_leaf(b):
    operator = {"EQ": "equal_to", "NEQ": "not_equal_to"}.get(b.op)
    if operator is None:
        raise _unsupported("str(...) comparison supports only == and !=")
    leaf = {"name": _name_of(b.left.args[0]), "operator": operator,
            "type_insensitive": True}
    return _apply_value(leaf, b.right.args[0])


def _case_insensitive_membership_leaf(b):
    op = ("is_contained_by_case_insensitive" if b.op == "IN"
          else "is_not_contained_by_case_insensitive")
    name = _name_of(b.left.args[0])
    if isinstance(b.right, Lit) and b.right.kind == "LIST":
        terms = []
        for item in b.right.value:
            lit = item.args[0] if _is_upper_call(item) else item
            if not (isinstance(lit, Lit) and lit.kind == "STRING"):
                raise _unsupported("case-insensitive membership term must be "
                                   "a string or upper(\"…\")")
            terms.append(lit.value)
        return {"name": name, "operator": op, "value": terms}
    return _value_leaf(name, op, b.right)


_UNIQUE_SET_OPERATORS = {"is_unique_set", "is_not_unique_set"}


def _function_operator_leaf(c, operator=None):
    if not c.args:
        raise _unsupported(c.name + "(...) requires the column argument")
    operator = operator if operator is not None else c.name
    kw = c.kwargs
    if operator in _UNIQUE_SET_OPERATORS:
        # Owner requirement #1 (2026-08-23): is_(not_)unique_set carries ONE list operand,
        # is_unique_set([A, B, ...]). The LEAF wire form keeps its name/value split (D-2):
        # member 0 -> name, the rest -> value (an array, omitted when there is no rest).
        # Mirrors ExprLowering.functionOperatorLeaf, including its refusal of the retired
        # f(A, keys=[...]) / f(A, B) / f(A) spellings (Plan A Phase 2, ruling 2).
        if "keys" in kw:
            raise _unsupported(operator + " no longer takes keys= - write " + operator
                               + "([A, B, ...]), one list operand")
        if len(c.args) != 1:
            raise _unsupported(operator + " takes exactly one list operand")
        if not (isinstance(c.args[0], Lit) and c.args[0].kind == "LIST"):
            raise _unsupported(operator + "'s operand must be a list literal: " + operator
                               + "([A, B, ...])")
        items = c.args[0].value
        if not items:
            raise _unsupported(operator + "([]) has no members")
        leaf = {"name": _reference_name(items[0]), "operator": operator}
        if len(items) > 1:
            leaf["value"] = _operand_array(Lit("LIST", items[1:]))
    else:
        leaf = {"name": _name_of(c.args[0]), "operator": operator}
        if len(c.args) >= 2:
            v = c.args[1]
            if isinstance(v, Lit) and v.kind == "NUMBER":
                leaf["value"] = _number_value(v.value)
            else:
                leaf["value"] = _reference_name(v)
        if "keys" in kw:
            leaf["value"] = _operand_array(kw["keys"])
    if "within" in kw:
        leaf["within"] = _within_value(kw["within"])
    if "ordering" in kw:
        leaf["ordering"] = _reference_name(kw["ordering"])
    if "regex" in kw:
        r = kw["regex"]
        if not (isinstance(r, Lit) and r.kind == "STRING"):
            raise _unsupported("regex= must be a string literal")
        leaf["regex"] = r.value
    if "include_empty" in kw:
        ie = kw["include_empty"]
        if not (isinstance(ie, Lit) and ie.kind == "BOOL"):
            raise _unsupported("include_empty= must be a boolean literal")
        leaf["include_empty"] = ie.value
    if "keep_missings" in kw:
        km = kw["keep_missings"]
        if not (isinstance(km, Lit) and km.kind == "BOOL"):
            raise _unsupported("keep_missings= must be a boolean literal")
        leaf["keep_missings"] = km.value
    if "relation" in kw:
        # EC-87 — the next-record comparison relation (ExprLowering reads it back verbatim).
        rel = kw["relation"]
        if not (isinstance(rel, Lit) and rel.kind == "STRING"):
            raise _unsupported("relation= must be a string literal")
        leaf["relation"] = rel.value
    return leaf


def _sorted_by_leaf(c):
    if not c.args:
        raise _unsupported("is_sorted_by(...) requires the column argument")
    by = c.kwargs.get("by")
    if not (isinstance(by, Lit) and by.kind == "LIST"):
        raise _unsupported("is_sorted_by requires a by=[…] descriptor list")
    descriptors = []
    for d in by.value:
        if not (isinstance(d, Call) and d.name in ("asc", "desc")
                and len(d.args) == 1 and isinstance(d.args[0], Lit)
                and d.args[0].kind == "STRING"):
            raise _unsupported("sort descriptor must be asc(\"col\") or "
                               "desc(\"col\")")
        nulls = d.kwargs.get("nulls")
        null_position = (nulls.value if isinstance(nulls, Lit)
                         and nulls.kind == "STRING" else "last")
        descriptors.append({"name": d.args[0].value, "sort_order": d.name,
                            "null_position": null_position})
    leaf = {"name": _name_of(c.args[0]), "operator": "target_is_not_sorted_by",
            "value": descriptors}
    if "within" in c.kwargs:
        leaf["within"] = _within_value(c.kwargs["within"])
    keep = c.kwargs.get("keep_missings")
    if keep is not None:
        if not (isinstance(keep, Lit) and keep.kind == "BOOL"):
            raise _unsupported("keep_missings= must be a boolean literal")
        leaf["keep_missings"] = keep.value
    return leaf


def _within_value(within):
    """Port of ``ExprLowering.withinNode`` (EC-24): a single reference stays a
    string; a list literal becomes a list whose entries are column names or —
    for a nested list literal (a coalesce-group) — nested name lists."""
    if not (isinstance(within, Lit) and within.kind == "LIST"):
        return _reference_name(within)
    out = []
    for item in within.value:
        if isinstance(item, Lit) and item.kind == "LIST":
            out.append([_reference_name(g) for g in item.value])
        else:
            out.append(_reference_name(item))
    return out


def _operand_array(e):
    if not (isinstance(e, Lit) and e.kind == "LIST"):
        raise _unsupported("expected a [list] operand but found " + _describe(e))
    out = []
    for item in e.value:
        if isinstance(item, Lit):
            out.append(_literal_value(item))
        else:
            out.append(_reference_name(item))
    return out


def _require_args(fn, actual, expected):
    if actual != expected:
        raise _unsupported("%s(...) expects %d argument(s) but got %d"
                           % (fn, expected, actual))


# ---------------------------------------------------------------------------
# Public lowering surface
# ---------------------------------------------------------------------------

def lower(expr_ast, conversion=True):
    """Lowers a boolean ``Expr`` AST to the legacy ``Check`` dict.

    ``conversion=True`` (the default, and what :func:`dual_form_check` uses)
    first applies the conversion-mode restores — the Python mirror of
    ``ExprLowering.toCheckConditionForConversion``; ``conversion=False``
    mirrors the strict ``toCheckCondition`` surface (plus the two documented
    CheckToExpr inverses, which are shape recoveries, not semantics changes).

    Raises :class:`LoweringError` when a construct has no legacy surface.
    """
    if conversion:
        expr_ast = _restore_legacy_forms(expr_ast)
    return _lower_bool(expr_ast)


def lower_expression(text, conversion=True):
    """Parses expression ``text`` and lowers it to the legacy ``Check`` dict.

    Raises :class:`LexError` / :class:`ParseError` on malformed text and
    :class:`LoweringError` on a construct with no legacy surface.
    """
    return lower(parse(text), conversion=conversion)


def is_expression_check(check):
    """Whether ``check`` is an expression-form Check.

    Mirrors ``CheckConditionDeserializer``'s dispatch order: ``all`` / ``any``
    / ``not`` win over ``expression``, so a (hypothetical) mixed dict stays
    legacy.
    """
    return (isinstance(check, dict) and "expression" in check
            and not ({"all", "any", "not"} & set(check)))


#: The authorable check-level ladder, strictest first (Plan C §3.3). The
#: report-only ``NOTICE`` is deliberately absent: no rule may author it, so it
#: can never be a level key. ⚠ This is one of several copies of the ladder in
#: the tree (``RuleCheckDeserializer`` derives it from the ``Severity`` enum,
#: ``CheckLevelNodes.LADDER`` and ``RuleFingerprint.LADDER`` hard-code it);
#: nothing cross-checks them.
CHECK_LEVELS = ("REJECT", "ERROR", "WARNING", "INFO")

#: The optional per-level message key. Reporting prose, not part of the
#: condition grammar - stripped before a level's condition is lowered.
LEVEL_MESSAGE_KEY = "Message"


def is_level_map(check):
    """Whether ``check`` is a LEVEL-KEYED Check (Plan C §3.3).

    Mirrors ``RuleCheckDeserializer.bind`` / ``CheckLevelNodes.isLevelMap``: a
    node is a level map only when it is a non-empty dict whose keys are **all**
    authorable level names. Every condition key is lower-case and every level
    name upper-case, so the discrimination is total.
    """
    return (isinstance(check, dict) and bool(check)
            and all(key in CHECK_LEVELS for key in check))


def lower_level_map(check):
    """Lowers every level of a level-keyed ``Check`` and unions the results.

    Returns ``(node, unlowerable_levels)``.  ``node`` is the legacy ``Check``
    dict holding the **union** of the levels that lowered - a bare condition
    when only one did, an ``any:`` over them otherwise - or ``None`` when none
    did.  ``any`` rather than ``all`` because the levels are alternatives: a row
    is reported when *some* level fires, never only when all of them do.

    ⚠ The union is for **leaf-keyed instruments**, which ask "what does this
    rule read / test", not for evaluation: the engine runs each level
    separately and first-claim decides which one reports a shared unit.
    """
    lowered = []
    unlowerable = []
    for level in CHECK_LEVELS:
        if level not in check:
            continue
        condition = check[level]
        if isinstance(condition, dict) and LEVEL_MESSAGE_KEY in condition:
            condition = {k: v for k, v in condition.items()
                         if k != LEVEL_MESSAGE_KEY}
        try:
            lowered.append(dual_form_check(condition))
        except LoweringError:
            unlowerable.append(level)
    if not lowered:
        return None, unlowerable
    node = lowered[0] if len(lowered) == 1 else {"any": lowered}
    return node, unlowerable


def dual_form_check(check):
    """The legacy ``Check`` dict for either authored form.

    A legacy ``check`` (or ``None``, or anything non-expression) is returned
    **unchanged - the same object**, so legacy-corpus behaviour is bit-for-bit
    identical. An ``{expression: "<text>"}`` Check is parsed with the shared
    parser and lowered to the legacy dict the standing tools already walk.

    ⭐ A **level-keyed** ``Check`` (Plan C §3.3) is lowered level by level and
    the levels are unioned (:func:`lower_level_map`).  Without this arm a level
    map matched neither ``is_expression_check`` nor any composite key, so it was
    returned **unchanged and without raising**, and every leaf-walking caller
    then yielded the level map itself as one nameless pseudo-leaf: no ``name``,
    no ``operator``, no ``value``.  The measurable damage was a set of
    *fabricated zeros* - ``core-grounding.tsv`` printed ``0`` varsig neighbours
    for rules that have twelve, four untouched neighbours silently lost a
    member, and the ``UNLOWERABLE`` sentinel that exists precisely to print
    "not computable" instead of a zero never fired.

    Raises :class:`LoweringError` when a construct - or, for a level map, *any
    one level* - has no legacy surface.  A partial union is not returned here:
    a caller that wants the lowerable levels' leaves anyway must ask for them
    through :func:`lower_level_map` and record the rule as not-computable, so
    that an incomplete leaf set can never be mistaken for a complete one.
    """
    if is_level_map(check):
        node, unlowerable = lower_level_map(check)
        if unlowerable:
            raise LoweringError(
                "Check level(s) " + ", ".join(unlowerable) + " have no legacy "
                "surface, so the level union is incomplete")
        return node
    if not is_expression_check(check):
        return check
    expression = check["expression"]
    if not isinstance(expression, str):
        raise LoweringError("Check.expression must be a string, found "
                            + type(expression).__name__)
    return lower_expression(expression)
