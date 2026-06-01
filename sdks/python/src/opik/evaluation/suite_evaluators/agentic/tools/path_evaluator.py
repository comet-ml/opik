"""Pure-Python path/predicate evaluator for the `scan` tool.

Implements a deliberately small subset of jq-shaped syntax — see design
doc §5.3 for the canonical grammar. The supported forms are:

    .                       root
    .foo / .foo.bar         dotted field access
    .foo[3] / .foo[-1]      array index (negative allowed)
    .foo[0:5]               array slice (Python-style, bounds optional)
    .foo[]                  iterate array → multi-result
    .foo[].bar              iterate then field-access each element
    ..                      recursive descent over every descendant
    ..|strings              recursive descent filtered to strings
    ..|select(<predicate>)  recursive descent filtered by predicate

Predicates inside `select(...)`:

    <path>                  truthy test
    <path>?                 key present (regardless of value)
    <path> == <literal>     equality
    <path> != <literal>     inequality
    not <pred>
    <pred> and <pred>
    <pred> or <pred>
    ( <pred> )

Anything outside this grammar produces a `PathError` with `reason` set;
callers (the `scan` tool) translate that into a structured error
response so the model can retry within the prompt-taught grammar.

Two cheap runtime guards keep the evaluator bounded:
- max recursion depth = 200 (catches pathological `..` on deeply nested data)
- max result count    = 10 000 (catches unbounded generators)
Both raise `PathLimitError` with a descriptive message.
"""

import dataclasses
import re
from typing import Any, Iterable, Iterator, List, Optional, Union

DEFAULT_MAX_DEPTH = 200
DEFAULT_MAX_RESULTS = 10_000


# ---------------------------------------------------------------------------
# Input normalization
# ---------------------------------------------------------------------------


def normalize_expression(expression: str) -> str:
    """Auto-prepend a leading `.` when the caller omits it.

    Every valid expression in the SDK's constrained jq dialect begins
    with `.` (root, field access) or `..` (recursive descent). Models
    sometimes drop the leading dot — `dataset_item` instead of
    `.dataset_item` — typically when they pasted a field name from a
    `read` payload. Silently rewriting to `.<name>` avoids the
    "Unsupported expression" round-trip without weakening the parser,
    since no valid expression can begin with an identifier character.

    Shared by `scan` (for its `expression` argument) and `search` (for
    its optional `path` argument); any future tool that accepts a path
    expression should route it through this helper before parsing.
    """
    stripped = expression.lstrip()
    if not stripped:
        return expression
    first = stripped[0]
    if first.isalpha() or first == "_":
        return "." + expression
    return expression


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class PathError(Exception):
    """Raised for parse-time problems in the expression."""


class PathLimitError(Exception):
    """Raised when a runtime guard (depth / result count) fires."""


# ---------------------------------------------------------------------------
# AST
# ---------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Root:
    pass


@dataclasses.dataclass(frozen=True)
class Field:
    name: str


@dataclasses.dataclass(frozen=True)
class Index:
    idx: int


@dataclasses.dataclass(frozen=True)
class Slice:
    start: Optional[int]
    stop: Optional[int]


@dataclasses.dataclass(frozen=True)
class Iterate:
    pass


@dataclasses.dataclass(frozen=True)
class StringsFilter:
    pass


@dataclasses.dataclass(frozen=True)
class SelectFilter:
    predicate: "Predicate"


@dataclasses.dataclass(frozen=True)
class RecursiveDescent:
    # None → emit every descendant including the root.
    # StringsFilter → keep only string-typed descendants.
    # SelectFilter → keep descendants where predicate is truthy.
    filt: Optional[Union[StringsFilter, SelectFilter]]


Step = Union[Root, Field, Index, Slice, Iterate, RecursiveDescent]


@dataclasses.dataclass(frozen=True)
class PathExpr:
    steps: List[Step]


# Predicate AST ---------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Truthy:
    path: PathExpr


@dataclasses.dataclass(frozen=True)
class KeyPresent:
    # `.foo?` — true when the parent dict has key `foo`. Encoded as the
    # parent-relative path plus the trailing field name.
    path: PathExpr
    field_name: str


@dataclasses.dataclass(frozen=True)
class Equal:
    path: PathExpr
    literal: Any


@dataclasses.dataclass(frozen=True)
class NotEqual:
    path: PathExpr
    literal: Any


@dataclasses.dataclass(frozen=True)
class Not:
    inner: "Predicate"


@dataclasses.dataclass(frozen=True)
class And:
    left: "Predicate"
    right: "Predicate"


@dataclasses.dataclass(frozen=True)
class Or:
    left: "Predicate"
    right: "Predicate"


Predicate = Union[Truthy, KeyPresent, Equal, NotEqual, Not, And, Or]


# ---------------------------------------------------------------------------
# Lexer
# ---------------------------------------------------------------------------


_TOKEN_RE = re.compile(
    r"""
    (?P<DOUBLEDOT>\.\.)            |
    (?P<DOT>\.)                    |
    (?P<LBRACKET>\[)               |
    (?P<RBRACKET>])               |
    (?P<COLON>:)                   |
    (?P<PIPE>\|)                   |
    (?P<LPAREN>\()                 |
    (?P<RPAREN>\))                 |
    (?P<QUESTION>\?)               |
    (?P<EQ>==)                     |
    (?P<NEQ>!=)                    |
    (?P<INTEGER>-?\d+)             |
    (?P<STRING>"(?:[^"\\]|\\.)*")  |
    (?P<IDENT>[A-Za-z_][A-Za-z0-9_]*) |
    (?P<WS>\s+)
    """,
    re.VERBOSE,
)

_KEYWORDS = {"and", "or", "not", "select", "strings", "true", "false", "null"}


@dataclasses.dataclass
class Token:
    kind: str
    value: str
    pos: int


def _tokenize(expr: str) -> List[Token]:
    tokens: List[Token] = []
    pos = 0
    while pos < len(expr):
        match = _TOKEN_RE.match(expr, pos)
        if match is None:
            raise PathError(f"Unexpected character at position {pos}: {expr[pos]!r}")
        kind = match.lastgroup or ""
        text = match.group()
        pos = match.end()
        if kind == "WS":
            continue
        if kind == "IDENT" and text in _KEYWORDS:
            kind = f"KW_{text.upper()}"
        tokens.append(Token(kind=kind, value=text, pos=match.start()))
    tokens.append(Token(kind="EOF", value="", pos=len(expr)))
    return tokens


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------


class _Parser:
    def __init__(self, tokens: List[Token]) -> None:
        self._tokens = tokens
        self._pos = 0

    # Token helpers -------------------------------------------------------

    def _peek(self, offset: int = 0) -> Token:
        return self._tokens[self._pos + offset]

    def _accept(self, kind: str) -> Optional[Token]:
        if self._peek().kind == kind:
            token = self._peek()
            self._pos += 1
            return token
        return None

    def _expect(self, kind: str) -> Token:
        token = self._accept(kind)
        if token is None:
            actual = self._peek()
            raise PathError(
                f"Expected {kind} but found {actual.kind} ({actual.value!r}) "
                f"at position {actual.pos}"
            )
        return token

    # Top-level expression ------------------------------------------------

    def parse(self) -> PathExpr:
        if self._peek().kind == "DOUBLEDOT":
            expr = self._parse_recursive()
        else:
            expr = self._parse_path()
        if self._peek().kind != "EOF":
            extra = self._peek()
            raise PathError(
                f"Unexpected trailing input at position {extra.pos}: {extra.value!r}"
            )
        return expr

    def _parse_recursive(self) -> PathExpr:
        self._expect("DOUBLEDOT")
        filt: Optional[Union[StringsFilter, SelectFilter]] = None
        if self._accept("PIPE") is not None:
            filt = self._parse_recursive_filter()
        return PathExpr(steps=[RecursiveDescent(filt=filt)])

    def _parse_recursive_filter(self) -> Union[StringsFilter, SelectFilter]:
        if self._accept("KW_STRINGS") is not None:
            return StringsFilter()
        if self._accept("KW_SELECT") is not None:
            self._expect("LPAREN")
            pred = self._parse_predicate()
            self._expect("RPAREN")
            return SelectFilter(predicate=pred)
        tok = self._peek()
        raise PathError(
            f"Expected 'strings' or 'select(...)' after '..|' at position "
            f"{tok.pos}, got {tok.value!r}"
        )

    def _parse_path(self) -> PathExpr:
        # The leading dot is always required. A bare `.` is the root.
        self._expect("DOT")
        steps: List[Step] = [Root()]

        # First step after `.` may immediately be EOF (the root expression)
        # or a field / `[…]`.
        ident_tok = self._accept("IDENT")
        if ident_tok is not None:
            steps.append(Field(name=ident_tok.value))

        while True:
            if self._accept("DOT") is not None:
                # `.<ident>` chained access
                ident_tok = self._expect("IDENT")
                steps.append(Field(name=ident_tok.value))
                continue
            if self._accept("LBRACKET") is not None:
                steps.append(self._parse_bracket_body())
                continue
            break
        return PathExpr(steps=steps)

    def _parse_bracket_body(self) -> Step:
        # `[]` — iterate.
        if self._accept("RBRACKET") is not None:
            return Iterate()
        # `[ INTEGER ]` or `[ INTEGER? : INTEGER? ]`.
        start: Optional[int] = None
        stop: Optional[int] = None
        first_int = self._accept("INTEGER")
        if first_int is not None:
            start = int(first_int.value)
        if self._accept("COLON") is not None:
            second_int = self._accept("INTEGER")
            if second_int is not None:
                stop = int(second_int.value)
            self._expect("RBRACKET")
            return Slice(start=start, stop=stop)
        # Plain `[INTEGER]`.
        if first_int is None:
            tok = self._peek()
            raise PathError(
                f"Expected integer, slice, or ']' at position {tok.pos}, "
                f"got {tok.value!r}"
            )
        self._expect("RBRACKET")
        return Index(idx=start or 0)

    # Predicate parser ----------------------------------------------------

    def _parse_predicate(self) -> Predicate:
        return self._parse_or()

    def _parse_or(self) -> Predicate:
        left = self._parse_and()
        while self._accept("KW_OR") is not None:
            right = self._parse_and()
            left = Or(left=left, right=right)
        return left

    def _parse_and(self) -> Predicate:
        left = self._parse_unary()
        while self._accept("KW_AND") is not None:
            right = self._parse_unary()
            left = And(left=left, right=right)
        return left

    def _parse_unary(self) -> Predicate:
        if self._accept("KW_NOT") is not None:
            return Not(inner=self._parse_unary())
        return self._parse_atom()

    def _parse_atom(self) -> Predicate:
        if self._accept("LPAREN") is not None:
            inner = self._parse_predicate()
            self._expect("RPAREN")
            return inner
        # All other atoms start with a path: `.foo` / `.foo?` / `.foo == X`.
        path = self._parse_path()
        # Key-presence test: `.foo?` requires a trailing Field step we can
        # peel off so the evaluator tests the parent dict for membership.
        if self._accept("QUESTION") is not None:
            if not path.steps or not isinstance(path.steps[-1], Field):
                raise PathError(
                    "Key-presence test '?' must follow a field access (e.g. '.foo?')"
                )
            last_field = path.steps[-1]
            assert isinstance(last_field, Field)
            parent_path = PathExpr(steps=path.steps[:-1])
            return KeyPresent(path=parent_path, field_name=last_field.name)
        if self._accept("EQ") is not None:
            literal = self._parse_literal()
            return Equal(path=path, literal=literal)
        if self._accept("NEQ") is not None:
            literal = self._parse_literal()
            return NotEqual(path=path, literal=literal)
        return Truthy(path=path)

    def _parse_literal(self) -> Any:
        tok = self._peek()
        if tok.kind == "STRING":
            self._pos += 1
            return _unquote(tok.value)
        if tok.kind == "INTEGER":
            self._pos += 1
            return int(tok.value)
        if tok.kind == "KW_TRUE":
            self._pos += 1
            return True
        if tok.kind == "KW_FALSE":
            self._pos += 1
            return False
        if tok.kind == "KW_NULL":
            self._pos += 1
            return None
        raise PathError(
            f"Expected literal (string, integer, true/false/null) at position "
            f"{tok.pos}, got {tok.value!r}"
        )


def _unquote(quoted: str) -> str:
    # Strip enclosing quotes, then unescape `\"` and `\\`.
    body = quoted[1:-1]
    return body.encode("utf-8").decode("unicode_escape")


def parse(expression: str) -> PathExpr:
    """Parse `expression` to an AST or raise `PathError`."""
    return _Parser(_tokenize(expression)).parse()


# ---------------------------------------------------------------------------
# Evaluator
# ---------------------------------------------------------------------------


_MISSING = object()


def evaluate(
    expression: str,
    root: Any,
    *,
    max_depth: int = DEFAULT_MAX_DEPTH,
    max_results: int = DEFAULT_MAX_RESULTS,
) -> List[Any]:
    """Parse `expression` and run it against `root`, returning all results.

    Guards: raises `PathLimitError` if recursion exceeds `max_depth` or if
    the number of yielded results exceeds `max_results`.
    """
    ast = parse(expression)
    results: List[Any] = []
    for value in _run(ast.steps, [root], max_depth):
        results.append(value)
        if len(results) > max_results:
            raise PathLimitError(
                f"Result count exceeded {max_results}; narrow the expression."
            )
    return results


def _run(steps: List[Step], values: List[Any], max_depth: int) -> Iterator[Any]:
    """Apply `steps` sequentially. Materialize intermediates so each step
    sees a definite input set, but stream the final step lazily so the
    outer result-count guard can fire before unbounded generators
    (notably `..` on large structures) finish producing.
    """
    if not steps:
        yield from values
        return
    current: Iterable[Any] = values
    for step in steps[:-1]:
        current = list(_apply(step, current, max_depth))
    yield from _apply(steps[-1], current, max_depth)


def _apply(step: Step, values: Iterable[Any], max_depth: int) -> Iterator[Any]:
    for value in values:
        yield from _apply_one(step, value, max_depth)


def _apply_one(step: Step, value: Any, max_depth: int) -> Iterator[Any]:
    if isinstance(step, Root):
        yield value
        return
    if isinstance(step, Field):
        if isinstance(value, dict) and step.name in value:
            yield value[step.name]
        return
    if isinstance(step, Index):
        if isinstance(value, list):
            idx = step.idx
            if -len(value) <= idx < len(value):
                yield value[idx]
        return
    if isinstance(step, Slice):
        if isinstance(value, list):
            yield value[step.start : step.stop]
        return
    if isinstance(step, Iterate):
        if isinstance(value, list):
            yield from value
        return
    if isinstance(step, RecursiveDescent):
        yield from _descend(value, step.filt, depth=0, max_depth=max_depth)
        return
    raise PathError(f"Unhandled step: {step!r}")


def _descend(
    value: Any,
    filt: Optional[Union[StringsFilter, SelectFilter]],
    depth: int,
    max_depth: int,
) -> Iterator[Any]:
    if depth > max_depth:
        raise PathLimitError(
            f"Recursion depth exceeded {max_depth}; expression is too broad."
        )
    if _filter_matches(value, filt):
        yield value
    if isinstance(value, dict):
        for child in value.values():
            yield from _descend(child, filt, depth + 1, max_depth)
    elif isinstance(value, list):
        for child in value:
            yield from _descend(child, filt, depth + 1, max_depth)


def _filter_matches(
    value: Any, filt: Optional[Union[StringsFilter, SelectFilter]]
) -> bool:
    if filt is None:
        return True
    if isinstance(filt, StringsFilter):
        return isinstance(value, str)
    if isinstance(filt, SelectFilter):
        return _eval_predicate(filt.predicate, value)
    return False


# Predicate evaluation -------------------------------------------------------


def _eval_predicate(pred: Predicate, value: Any) -> bool:
    if isinstance(pred, Truthy):
        return _is_truthy(_first_result(pred.path, value))
    if isinstance(pred, KeyPresent):
        parent = _first_result(pred.path, value)
        return isinstance(parent, dict) and pred.field_name in parent
    if isinstance(pred, Equal):
        return _first_result(pred.path, value) == pred.literal
    if isinstance(pred, NotEqual):
        return _first_result(pred.path, value) != pred.literal
    if isinstance(pred, Not):
        return not _eval_predicate(pred.inner, value)
    if isinstance(pred, And):
        return _eval_predicate(pred.left, value) and _eval_predicate(pred.right, value)
    if isinstance(pred, Or):
        return _eval_predicate(pred.left, value) or _eval_predicate(pred.right, value)
    return False


def _first_result(path: PathExpr, value: Any) -> Any:
    """Run `path` against `value` and return the first result or `_MISSING`.

    Used inside predicates — a predicate only cares about existence /
    equality of one resolved value, so we don't materialize the full list.
    """
    for resolved in _run(path.steps, [value], DEFAULT_MAX_DEPTH):
        return resolved
    return _MISSING


def _is_truthy(value: Any) -> bool:
    if value is _MISSING:
        return False
    if value is None:
        return False
    if value is False:
        return False
    if value == 0:
        return False
    if value == "":
        return False
    if isinstance(value, (list, dict)) and not value:
        return False
    return True
