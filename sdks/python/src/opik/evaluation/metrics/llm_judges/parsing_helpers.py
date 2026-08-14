from typing import Any, Dict, List, Tuple
import json
import math
import re
import opik.exceptions as exceptions

# Sentinel distinguishing "no value resolved yet" from a legitimately falsy
# JSON value such as ``{}`` or ``[]``.
_UNSET = object()

# Characters that matter when scanning outside a JSON string literal: the
# structural openers/closers and the quote that starts a string.
_STRUCTURAL = re.compile(r'[\[\]{}"]')
# Inside a string literal only the closing quote and the escape character are
# significant; everything else (including braces) is opaque text.
_STRING_TOKEN = re.compile(r'["\\]')
# Which closer legitimately matches each opener.
_CLOSER_FOR = {"{": "}", "[": "]"}


def _reject_non_finite(token: str) -> Any:
    """Reject JSON's non-standard non-finite constants.

    ``json`` accepts ``NaN``, ``Infinity`` and ``-Infinity`` by default,
    routing each through ``parse_constant``. Standard JSON has no such tokens,
    and a non-finite number has no deterministic identity (``nan != nan``), so a
    judge verdict carrying one is ambiguous and must fail closed.

    Wiring this callback into BOTH decode entry points — the whole-string
    ``json.loads`` fast path in ``extract_json_content_or_raise`` and the
    per-span ``raw_decode`` inside the scanner — makes the rejection uniform.
    Without it the fast path silently returned a lone clean ``{"v": NaN}`` as a
    float, bypassing the ``_canonical_key`` non-finite check that only ran on
    the scanning path; acceptance then depended on whether surrounding prose or
    duplication happened to force the slow path (issue #7848). The raised
    ``ValueError`` is NOT a ``json.JSONDecodeError``, so on the fast path it
    skips the scanning fallback and is converted straight to
    ``JSONParsingError`` (fail closed, no second chance); in the scanner it is
    caught alongside ``JSONDecodeError`` and flags the span ``malformed``.
    """
    raise ValueError(f"non-finite JSON constant is not allowed: {token!r}")


def _reject_duplicate_object_keys(pairs: List[Tuple[str, Any]]) -> Dict[str, Any]:
    """Reject any object that repeats a member name.

    ``json`` builds objects with last-key-wins semantics by default, so
    ``{"score": 1, "score": 2}`` silently collapses to ``{"score": 2}`` before
    ``_canonical_key`` or the resolver ever sees it — the earlier value, and the
    fact that two conflicting values were emitted at all, are lost. A duplicate
    member name is exactly the kind of ambiguity this helper must fail closed on:
    a verdict-shaped object with two ``score`` keys has no single defensible
    reading.

    This ``object_pairs_hook`` therefore rejects EVERY repeat conservatively —
    it raises on the second occurrence of a key regardless of whether the
    repeated value is identical (``{"a": 1, "a": 1}``) or different
    (``{"a": 1, "a": 2}``). A well-formed judge never emits duplicate keys, so
    there is no legitimate output to preserve, and refusing the identical case
    too keeps the rule simple and its failure mode uniform. The hook runs on
    every object at every nesting depth, so a duplicate key nested inside an
    otherwise valid verdict is refused as well.

    The raised ``ValueError`` is NOT a ``json.JSONDecodeError``. Because
    ``_DECODER`` is shared by both decode entry points, that makes the rejection
    uniform: on the whole-string fast path in ``extract_json_content_or_raise``
    it skips the scanning fallback and converts straight to ``JSONParsingError``
    (fail closed, no second chance); in the per-span scanner it is caught
    alongside ``JSONDecodeError`` and flags the span ``malformed``. Either way
    the output fails closed identically.
    """
    result: Dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate object key is not allowed: {key!r}")
        result[key] = value
    return result


# A single shared decoder wired with the non-finite guard AND the duplicate-key
# guard, reused by BOTH decode paths (the whole-string fast path below and the
# per-span scanner). Sharing one module-level instance mirrors the stdlib's own
# cached ``_default_decoder`` and avoids constructing a decoder per call —
# passing ``parse_constant``/``object_pairs_hook`` to ``json.loads`` directly
# would rebuild one on every invocation. The decoder holds only configuration
# (no per-call state), so it is safe to reuse across calls and threads exactly
# as ``json.loads`` reuses its default decoder. Wiring both guards into the one
# shared decoder is what makes duplicate keys and non-finite constants fail
# closed the same way on the fast path and the scanner.
_DECODER = json.JSONDecoder(
    parse_constant=_reject_non_finite,
    object_pairs_hook=_reject_duplicate_object_keys,
)


def extract_json_content_or_raise(content: str) -> Any:
    """Extract the single JSON value an LLM judge intended, or fail closed.

    Caller-facing contract (observable behavior):

    * Clean JSON — ``content`` that is exactly one JSON value — is decoded
      directly and returned.
    * Otherwise the surrounding prose is scanned for complete top-level
      structured values (objects ``{...}`` and arrays ``[...]``; bare scalars in
      prose are not candidates). A single unique value is returned. Semantically
      identical duplicates are accepted and collapse to that one value (reasoning
      models under ``response_format`` sometimes echo their answer twice); ``1``,
      ``1.0`` and ``1.00`` are the same JSON number, while ``true``/``1`` and
      ``false``/``0`` are distinct.
    * The call raises ``opik.exceptions.JSONParsingError`` when the output is
      ambiguous or broken: two or more conflicting values (never resolved by
      position), a malformed bracket-balanced span (e.g. ``{not json}``), a
      structural break (unterminated string, unclosed or mismatched bracket), an
      object with duplicate member names (``{"score": 1, "score": 2}``), a
      non-finite number (``NaN``/``Infinity``/``-Infinity``), or no complete
      value at all. A malformed or structural-break span makes the call raise
      even when a valid value also appears alongside it.
    """
    try:
        # ``_DECODER`` rejects NaN/Infinity/-Infinity here so a lone clean
        # non-finite value fails closed on the fast path exactly as it does on
        # the scanning path — it must not get a second chance via the fallback.
        # ``decode`` matches ``json.loads`` semantics (whole-string, extra-data
        # check) with no per-call decoder construction.
        return _DECODER.decode(content)
    except json.decoder.JSONDecodeError:
        return _resolve_unique_json_value_or_raise(content)
    except Exception as e:
        # Includes the ValueError raised by ``_reject_non_finite`` or
        # ``_reject_duplicate_object_keys`` (neither is a JSONDecodeError), so a
        # non-finite or duplicate-key fast-path value fails closed here rather
        # than falling through to the scanning path.
        raise exceptions.JSONParsingError(
            f"Failed to parse response to JSON dictionary: {str(e)}"
        )


def _canonical_key(value: Any) -> Any:
    """Return a recursive, JSON-semantic identity for a decoded ``value``.

    Two decoded candidates are the *same* verdict exactly when their canonical
    keys are equal (``==``). The key follows JSON's value model rather than
    Python's, so it is built to answer "did the judge emit the same JSON value
    twice?" and not "are these Python objects ``==``?":

    * ``bool`` -> ``("bool", x)``, kept DISTINCT from numbers. Python treats
      ``True == 1`` and ``False == 0``; without the separate tag a boolean
      verdict and a numeric one would collapse and hide a genuine conflict.
      (``bool`` is tested before ``int``/``float`` because ``bool`` subclasses
      ``int``.)
    * ``int`` and ``float`` are both a JSON ``number``. Both are normalised to
      an EXACT integer ratio ``("number", numerator, denominator)`` — an ``int``
      is ``("number", x, 1)`` and a ``float`` uses ``x.as_integer_ratio()`` —
      so ``1``, ``1.0`` and ``1.00`` produce the *same* key and collapse. A
      judge that echoes the same number once as an int and once as a float is
      emitting one verdict, not two.

      An arbitrary-precision ``int`` is kept EXACT and is NEVER passed through
      ``float()``. ``float()`` would (a) lose large-integer identity —
      ``2**53`` and ``2**53 + 1`` both round to the same ``float`` and would be
      wrongly deduped into one verdict — and (b) raise a raw ``OverflowError``
      for a value beyond ``float`` range (e.g. ``10**400``), which is a valid
      JSON integer and must be canonicalised, not rejected. Using the exact
      ratio makes ``2**53`` and ``2**53 + 1`` distinct keys (a real conflict)
      while ``10**400`` is represented exactly with no overflow.
    * a NON-FINITE ``float`` (``NaN`` / ``Infinity`` / ``-Infinity``) has no
      JSON-standard meaning and no deterministic identity (``nan != nan``), so
      it RAISES ``ValueError``. This is now defence-in-depth: both decode entry
      points install ``_reject_non_finite`` as ``parse_constant``, so a
      non-finite constant is refused at decode time (``json.loads`` on the fast
      path, ``raw_decode`` in the scanner) and never reaches here. Should a
      non-finite value ever arrive anyway, this check still raises ``ValueError``
      and the scanner turns it into a fail-closed ``malformed`` span rather than
      letting the value compare unequal to itself by accident.
      (``float.as_integer_ratio()`` would itself raise on a non-finite value;
      the explicit ``math.isfinite`` check raises the domain ``ValueError`` the
      scanner already expects instead of leaking that lower-level error.)
    * ``str`` -> ``("str", x)``; ``None`` -> ``("null",)``.
    * ``dict`` -> ``("dict", tuple(sorted((k, _canonical_key(v)) ...)))`` so
      object key ORDER is irrelevant (``{"a":1,"b":2}`` and ``{"b":2,"a":1}``
      share a key; object keys are unique, so the sort only ever compares the
      key strings).
    * ``list`` -> ``("list", tuple(_canonical_key(i) for i in x))`` so array
      order stays SIGNIFICANT (``[1,2]`` != ``[2,1]``).

    The rules recurse, so number-equality and the bool/number/string
    distinctions hold at any nesting depth. Only the representative candidate's
    key is retained, so this preserves the scan's O(1)-in-candidate-count
    retention.
    """
    if isinstance(value, bool):
        return ("bool", value)
    if isinstance(value, int):
        # Arbitrary-precision int kept EXACT — never float(), which loses
        # large-integer identity (2**53 == 2**53 + 1 as floats) and raises
        # OverflowError beyond float range (e.g. 10**400).
        return ("number", value, 1)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite JSON number has no canonical identity")
        numerator, denominator = value.as_integer_ratio()
        return ("number", numerator, denominator)
    if isinstance(value, str):
        return ("str", value)
    if value is None:
        return ("null",)
    if isinstance(value, dict):
        return (
            "dict",
            tuple(sorted((k, _canonical_key(v)) for k, v in value.items())),
        )
    if isinstance(value, list):
        return ("list", tuple(_canonical_key(item) for item in value))
    # json.loads never produces other types; refuse rather than guess.
    raise ValueError(f"unsupported JSON value type: {type(value)!r}")


def _resolve_unique_json_value_or_raise(content: str) -> Any:
    """Return the single JSON value an LLM judge intended, or fail closed.

    When ``json.loads`` cannot parse the whole string (prose around the JSON,
    or several JSON values glued together) we scan the text for complete
    top-level structured JSON values — objects ``{...}`` and arrays ``[...]``.
    Bare scalars in the surrounding prose are ignored: only object/array values
    are verdict-shaped candidates.

    Conflict resolution deliberately lives here, in one place, so no metric
    parser can reintroduce position-based selection (issue #7848). The policy
    fails closed:

    * the scan saw a *structural break* — an unterminated, unmatched, or
      mismatched delimiter (a truncated verdict, an unclosed wrapper, a stray
      ``}``/``]``) -> ``JSONParsingError``. Refusing here is what stops an
      attacker-influenced fragment (e.g. content echoed into a judge prompt)
      from being harvested as the verdict when the judge's real answer is
      truncated or malformed.
    * the scan saw a *balanced-but-invalid* top-level span — bracket-matched
      but not decodable as JSON (e.g. set-like prose ``{a, b}`` or unquoted
      keys ``{not json}``) -> ``JSONParsingError``. This span is refused rather
      than silently skipped: a valid verdict must never be harvested from
      alongside malformed structured-looking content, because that content may
      be attacker-influenced (echoed into a judge prompt) and its presence
      means the output is ambiguous. **Compatibility trade (issue #7848):** a
      valid verdict sitting next to a balanced-invalid structured-looking span
      in the judge's prose (e.g. a ``{example}`` in the reasoning) is now
      rejected too. This is deliberate — the scanner has no schema and cannot
      tell an example apart from a tampered verdict, so it fails closed.
    * zero complete values          -> ``JSONParsingError``
    * one distinct value            -> return it
    * duplicates of one value       -> return it (reasoning models sometimes
      echo their answer; semantically identical repeats are not a conflict).
      Sameness is decided by a recursive JSON-semantic canonical key
      (``_canonical_key``), not Python ``==``: ``true``/``1`` and
      ``false``/``0`` are *different* (bool is distinct from number), but
      ``1``/``1.0``/``1.00`` are the *same* JSON number and collapse. A
      non-finite number (``NaN``/``Infinity``) has no canonical identity and is
      treated as malformed (fail closed).
    * two or more *different* values -> ``JSONParsingError`` (ambiguous;
      refuse to pick a verdict by position)

    A malformed or structural-break flag makes the resolver raise **even when
    one or more valid candidates were also found**: a nested value inside a
    malformed outer container is never returned standalone.

    The scan is incremental: it retains only a single *representative*
    candidate plus its canonical key, so the retained candidate COUNT is O(1)
    and does not grow even when a judge echoes thousands of candidates. (The
    input string, the one representative value, and the bracket-nesting stack
    are the remaining, non-candidate-count terms.)
    """
    (
        representative,
        has_value,
        conflict,
        structural_break,
        malformed,
    ) = _scan_top_level_json_values(content)

    if structural_break:
        raise exceptions.JSONParsingError(
            "Ambiguous or incomplete LLM output: the response contains an "
            "unterminated, unmatched, or mismatched JSON structure; refusing "
            "to guess a verdict"
        )

    if malformed:
        raise exceptions.JSONParsingError(
            "Ambiguous LLM output: the response contains a bracket-balanced "
            "but invalid JSON structure; refusing to guess a verdict even if a "
            "valid value also appears"
        )

    if not has_value:
        raise exceptions.JSONParsingError(
            "Failed to extract presumably JSON value: no complete JSON object "
            "or array found in content"
        )

    if conflict:
        raise exceptions.JSONParsingError(
            "Ambiguous LLM output: multiple conflicting JSON values were "
            "found; refusing to select one by position"
        )

    return representative


def _scan_top_level_json_values(
    content: str,
) -> Tuple[Any, bool, bool, bool, bool]:
    """Resolve complete top-level object/array values in ``content`` incrementally.

    Single left-to-right pass. The scanner is string/escape aware and
    bracket-type aware: it keeps a stack of the openers it is currently inside,
    so braces inside a string literal never affect nesting and a ``]`` can only
    close a ``[`` (never a ``{``). A top-level ``{``/``[`` opens a candidate
    span; the matching close that empties the stack ends it, and ``json`` is
    invoked *only on that completed, well-nested span*. A value nested inside a
    larger container is therefore never decoded on its own, so an object echoed
    inside a malformed or unclosed wrapper can never be returned as the verdict.

    Returns ``(representative, has_value, conflict, structural_break, malformed)``:

    * ``representative`` is the first complete top-level value decoded (or
      ``_UNSET`` if none), and ``has_value`` says whether one was found. Only
      this single value and its canonical key are retained, so the retained
      candidate COUNT is O(1) — a judge that echoes thousands of identical (or
      conflicting) objects does not grow a per-candidate list.
    * ``conflict`` is ``True`` when a later complete value has a different
      canonical key (``_canonical_key``) from the representative. Duplicates
      that share the representative's key are dropped; the scan keeps going
      after the first conflict so it can still observe any later
      structural-break / malformed span.
    * ``structural_break`` is ``True`` when the scan detected an unterminated
      string, an opener that is never closed (truncation), a stray closer with
      no matching opener, or a closer of the wrong bracket type.
    * ``malformed`` is ``True`` when a completed, bracket-balanced top-level
      span could not be decoded as JSON — e.g. set-like prose ``{a, b}`` or
      unquoted keys ``{not json}``, or a span nested too deeply for the decoder,
      or an object with duplicate member names (``{"score": 1, "score": 2}``)
      rejected by ``object_pairs_hook`` — or when a decoded span carries a
      non-finite number (``NaN``/``Infinity``) with no canonical identity. Such
      a span is **not** silently
      skipped: recording it lets the resolver
      fail closed rather than harvest a sibling valid verdict from alongside
      malformed structured-looking content (issue #7848). The compatibility
      cost is that a genuine ``{example}`` in the judge's prose next to a real
      verdict now also trips this flag; without a schema the scanner cannot
      tell the two apart, so it refuses.

    All flags are set without any extra scan of the input — they piggyback on
    the same single pass — so enumeration stays O(n) time and O(1) retained
    candidates. Callers fail closed on any of ``structural_break``,
    ``malformed``, or ``conflict``.

    ``json`` is called once per completed top-level span, so the number of
    decode calls equals the number of genuinely complete top-level values, not
    the input length; input that is only unbalanced openers triggers zero
    decode calls. The pass jumps between significant characters (``{ } [ ] "``)
    with a compiled regex, so prose and string interiors are skipped in C.
    Measured behaviour on this machine: work grows linearly with input length.
    A completed span too deeply nested to decode raises ``RecursionError`` /
    ``JSONDecodeError`` from ``json`` and flags ``malformed``; the scan never
    recurses.
    """
    # Reuse the shared non-finite-guarded decoder: same policy as the fast path.
    # A span carrying NaN/Infinity/-Infinity makes ``raw_decode`` raise
    # ``ValueError`` (via ``parse_constant``) before ``_canonical_key`` is
    # reached, so acceptance never depends on surrounding prose or duplication.
    # ``_canonical_key`` still rejects post-decode as belt-and-suspenders
    # (issue #7848).
    decoder = _DECODER
    representative: Any = _UNSET
    representative_key: Any = None
    has_value = False
    conflict = False
    stack: List[str] = []
    span_start = -1
    length = len(content)
    index = 0
    structural_break = False
    malformed = False
    find_structural = _STRUCTURAL.search
    find_string_token = _STRING_TOKEN.search

    while index < length:
        match = find_structural(content, index)
        if match is None:
            break
        index = match.start()
        char = content[index]

        if char == '"':
            # Skip the whole string literal in C, honouring backslash escapes,
            # so any braces inside it never affect nesting depth.
            cursor = index + 1
            while True:
                token = find_string_token(content, cursor)
                if token is None:
                    # Unterminated string: the structure is truncated.
                    return representative, has_value, conflict, True, malformed
                if content[token.start()] == "\\":
                    cursor = token.start() + 2
                    continue
                index = token.start() + 1
                break
            continue

        if char == "{" or char == "[":
            if not stack:
                span_start = index
            stack.append(char)
            index += 1
            continue

        # char is "}" or "]"
        index += 1
        if not stack:
            # Stray closer with no matching opener.
            structural_break = True
            continue
        opener = stack.pop()
        if _CLOSER_FOR[opener] != char:
            # Wrong bracket type (e.g. "]" closing "{").
            structural_break = True
            continue
        if not stack:
            span = content[span_start:index]
            try:
                value, end = decoder.raw_decode(span)
            except (json.JSONDecodeError, RecursionError, ValueError):
                # Balanced but not valid JSON (e.g. set-like prose or unquoted
                # keys), or nesting deep enough to exhaust the decoder, or a
                # non-finite constant (NaN/Infinity) rejected by
                # ``parse_constant``, or an object with duplicate member names
                # rejected by ``object_pairs_hook`` (both raised as
                # ``ValueError``; JSONDecodeError is itself a ValueError
                # subclass, so this catch also covers the ordinary decode
                # failures). This top-level span is malformed; flag it rather
                # than dropping it silently so the resolver fails closed.
                malformed = True
            else:
                if end == len(span):
                    # Fold the candidate into the running resolution instead of
                    # appending to a list, so only one representative is
                    # retained regardless of how many candidates appear.
                    try:
                        key = _canonical_key(value)
                    except (ValueError, RecursionError, OverflowError):
                        # A non-finite number (NaN/Infinity) has no canonical
                        # identity, and pathological nesting can exhaust the
                        # recursion; either way there is no deterministic way to
                        # compare this span, so treat it as malformed and fail
                        # closed rather than let it compare unequal to itself.
                        # ``OverflowError`` is defence-in-depth: the current
                        # canonicalisation keeps ints exact and never calls
                        # ``float()`` on them, so a valid huge int (e.g.
                        # ``10**400``) is canonicalised, not flagged; this catch
                        # only converts an unexpected overflow into the existing
                        # fail-closed path instead of leaking a raw builtin.
                        malformed = True
                    else:
                        if not has_value:
                            representative = value
                            representative_key = key
                            has_value = True
                        elif key != representative_key:
                            conflict = True
                        # else: identical duplicate — drop it (O(1) retained).
                else:
                    # Decoded a valid prefix but trailing junk remained inside
                    # the balanced span; treat the whole span as malformed.
                    malformed = True
            span_start = -1

    if stack:
        # One or more openers were never closed: the structure is truncated.
        structural_break = True

    return representative, has_value, conflict, structural_break, malformed
