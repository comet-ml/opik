from typing import Any, Iterator
import json
import opik.exceptions as exceptions


def normalize_verdict(verdict: str) -> str:
    """Normalize a judge ``verdict`` for comparison.

    The conversation judges ask for ``yes``/``no`` but their schemas accept any
    string, and models drift: ``"Yes."``, ``" no"`` or ``"YES"`` all reach the
    score computation. Comparing raw strings made the metrics disagree on the
    same drifted value - the ``!= "no"`` metrics counted ``"No."`` as a positive
    verdict while the ``== "yes"`` ones dropped ``"Yes."`` - so every judge now
    reads the verdict through this one normalizer: case-folded, whitespace- and
    trailing-punctuation-trimmed (period, comma, exclamation, question, semicolon,
    colon - the sentence-enders a judge appends), so ``"Yes."``, ``" yes "`` and
    ``"No .``" all read as their bare verdict. Punctuation a verdict carries for
    another reason (parentheses, dashes) is left alone: the normalizer removes
    drift, it does not interpret the verdict.
    """
    return verdict.strip().lower().rstrip(".,!?;:").strip()


def extract_json_content_or_raise(content: str) -> Any:
    try:
        return json.loads(content)
    except json.decoder.JSONDecodeError:
        return _extract_presumably_json_dict_or_raise(content)
    except Exception as e:
        raise exceptions.JSONParsingError(
            f"Failed to parse response to JSON dictionary: {str(e)}"
        )


def reason_to_text(value: Any) -> str:
    """Render a judge ``reason`` field as text.

    ``Hallucination`` and ``SycEval`` declare ``reason: List[str]``, so a
    schema-compliant verdict arrives as a list; ``str()`` on it yields a Python
    literal that is uploaded and displayed verbatim. Lists are joined, and an
    empty list is labelled, the way ``StructuredOutputCompliance`` already does
    both for the same shape.
    """
    if isinstance(value, list):
        if not value:
            return "No reason provided"
        return "\n".join(str(item) for item in value)

    return str(value)


def _json_values_equal(left: Any, right: Any) -> bool:
    """Compare two decoded JSON values with type-aware semantics.

    Plain ``==`` is not enough here: in Python ``True == 1`` and
    ``False == 0``, so ``{"score": true}`` and ``{"score": 1}`` would be
    treated as the same verdict. Booleans must only equal booleans;
    numbers compare by value (``1`` and ``1.0`` are the same score);
    dicts and lists compare recursively.
    """
    if isinstance(left, bool) or isinstance(right, bool):
        return isinstance(left, bool) and isinstance(right, bool) and left == right
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(
            _json_values_equal(left[key], right[key]) for key in left
        )
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(
            _json_values_equal(l_item, r_item) for l_item, r_item in zip(left, right)
        )
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return left == right
    return type(left) is type(right) and left == right


def _iter_top_level_json_objects(content: str) -> Iterator[Any]:
    """Yield every complete top-level JSON object in ``content``, one at a time.

    The scan restarts at each ``{`` so prose between objects cannot hide a
    later one, and it resumes past the end of each decoded object, so a
    ``{`` nested inside a decoded object is never re-scanned as a verdict
    candidate. Objects are yielded as they are decoded instead of being
    accumulated, so a huge response is never fully retained in memory.

    Fail-closed: a ``{`` that starts a top-level object but fails to decode
    raises ``JSONParsingError`` immediately instead of being skipped. A
    malformed outer object could otherwise hide a nested fragment that
    decodes cleanly (``{"score": 10, "note": {"score": 2}, broken``), and
    treating that nested fragment as the judge's verdict would revive the
    planted-verdict override from #7848. Refusing to guess is the safe
    outcome: every judge-metric parser converts the error into a failed
    score rather than scoring an attacker's verdict.
    """
    decoder = json.JSONDecoder()
    pos = 0
    while True:
        start = content.find("{", pos)
        if start == -1:
            return
        try:
            obj, end = decoder.raw_decode(content[start:])
        except json.JSONDecodeError:
            raise exceptions.JSONParsingError(
                "Failed to extract presumably JSON dictionary: "
                "malformed JSON object in content, "
                "refusing to guess which object is the judge's verdict"
            )
        yield obj
        pos = start + end


def _extract_presumably_json_dict_or_raise(content: str) -> Any:
    first_paren = content.find("{")
    last_paren = content.rfind("}")
    if first_paren == -1 or last_paren == -1:
        raise exceptions.JSONParsingError(
            "Failed to extract presumably JSON dictionary: no '{' / '}' found in content"
        )

    # Optimistic path: assume the model emitted exactly one JSON object,
    # possibly wrapped in prose. This is the cheapest case and matches the
    # historical behaviour.
    json_string = content[first_paren : last_paren + 1]
    try:
        return json.loads(json_string)
    except json.JSONDecodeError:
        pass

    # Fallback: under reasoning models with response_format the LLM
    # occasionally emits multiple complete JSON objects glued together
    # (e.g. ``{...}\n{...}``). Decode every complete top-level object and
    # compare each one against the first as it is decoded, so a huge
    # response is never retained in memory: identical duplicates (the known
    # reasoning-model behaviour) still return the verdict, but conflicting
    # objects are ambiguous — trusting the first one lets quoted candidate
    # content override the judge's real verdict (#7848), so fail loud
    # instead of guessing. Duplicates compare with type-aware semantics,
    # so ``{"score": true}`` and ``{"score": 1}`` conflict instead of
    # comparing equal.
    objects = _iter_top_level_json_objects(content[first_paren:])
    first = next(objects, None)
    if first is None:
        raise exceptions.JSONParsingError(
            "Failed to extract presumably JSON dictionary: "
            "no complete JSON object found in content"
        )
    for obj in objects:
        if not _json_values_equal(obj, first):
            raise exceptions.JSONParsingError(
                "Failed to extract presumably JSON dictionary: "
                "multiple conflicting JSON objects found in content, "
                "refusing to guess which one is the judge's verdict"
            )
    return first
