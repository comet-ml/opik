from typing import Any
from collections.abc import Sequence
import json
import re

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
    # (e.g. ``{...}\n{...}``). Streaming-decode the first complete object
    # so the call doesn't fail when the model duplicates its answer.
    decoder = json.JSONDecoder()
    try:
        obj, _ = decoder.raw_decode(content[first_paren:])
        return obj
    except json.JSONDecodeError as e:
        raise exceptions.JSONParsingError(
            f"Failed to extract presumably JSON dictionary: {str(e)}"
        ) from e


def escape_closing_tags(value: object, tag_names: Sequence[str]) -> str:
    """Rewrite closing delimiter tags found inside a value meant for a judge prompt.

    Judge templates wrap per-call values in ``<tag>``/``</tag>`` pairs and tell the
    judge those sections hold data rather than instructions. A value that carries a
    closing tag can end its section early, which makes the rest of it read as prompt
    structure, so neutralize those closings before interpolation.

    Matching is case-insensitive and tolerates whitespace before ``>`` because a
    judge model treats ``</OUTPUT>`` or ``</output >`` as a closing tag just as
    readily as the lowercase form.

    This is defense-in-depth on top of the system-prompt instruction, not a
    breakout guarantee: it rewrites these forms and nothing else. It is also
    unconditional, so a value that legitimately contains a closing tag (generated
    code, XML templates) reaches the judge with a backslash inserted.
    """
    closings = "|".join(re.escape(tag) for tag in tag_names)
    return re.sub(
        rf"</\s*({closings})\s*>", r"<\\/\1>", str(value), flags=re.IGNORECASE
    )
