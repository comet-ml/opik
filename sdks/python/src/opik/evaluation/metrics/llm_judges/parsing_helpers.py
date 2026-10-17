from typing import Any, List, Set
import json
import opik.exceptions as exceptions


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

    # Fallback: reasoning models occasionally repeat their answer object
    # (``{...}\n{...}``), which is fine. Two different top-level objects are
    # ambiguous: the first may be text the judge quoted from the evaluated
    # answer (#7848), so refuse to pick one by position.
    decoder = json.JSONDecoder()
    found: List[Any] = []
    seen: Set[str] = set()
    index = first_paren
    while index != -1:
        try:
            obj, end = decoder.raw_decode(content, index)
        except json.JSONDecodeError:
            index = content.find("{", index + 1)
            continue
        key = json.dumps(obj, sort_keys=True)
        if isinstance(obj, dict) and key not in seen:
            seen.add(key)
            found.append(obj)
            if len(found) > 1:
                raise exceptions.JSONParsingError(
                    "Ambiguous LLM output: found several different JSON objects; "
                    "refusing to pick one by position"
                )
        index = content.find("{", end)
    if not found:
        raise exceptions.JSONParsingError(
            "Failed to extract presumably JSON dictionary: no JSON object found in content"
        )
    return found[0]
