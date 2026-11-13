from typing import Any
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


def _extract_all_json_objects(content: str) -> list[Any]:
    """Decode every complete top-level JSON object in ``content``.

    Scanning restarts at each ``{`` so prose or a malformed fragment between
    objects cannot hide a later one; a failed decode just moves the scan
    past that brace. A ``{`` inside a decoded object's strings is never
    re-scanned because the scan resumes past the end of each decoded object.
    """
    decoder = json.JSONDecoder()
    objects: list[Any] = []
    pos = 0
    while True:
        start = content.find("{", pos)
        if start == -1:
            break
        try:
            obj, end = decoder.raw_decode(content[start:])
        except json.JSONDecodeError:
            pos = start + 1
            continue
        objects.append(obj)
        pos = start + end
    return objects


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
    # (e.g. ``{...}\n{...}``). Decode every complete object: identical
    # duplicates (the known reasoning-model behaviour) still return the
    # verdict, but conflicting objects are ambiguous — trusting the first
    # one lets quoted candidate content override the judge's real verdict
    # (#7848), so fail loud instead of guessing.
    objects = _extract_all_json_objects(content[first_paren:])
    if not objects:
        raise exceptions.JSONParsingError(
            "Failed to extract presumably JSON dictionary: "
            "no complete JSON object found in content"
        )
    if all(obj == objects[0] for obj in objects):
        return objects[0]
    raise exceptions.JSONParsingError(
        "Failed to extract presumably JSON dictionary: "
        "multiple conflicting JSON objects found in content, "
        "refusing to guess which one is the judge's verdict"
    )
