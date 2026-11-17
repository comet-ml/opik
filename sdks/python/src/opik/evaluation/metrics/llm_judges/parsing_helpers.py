from typing import Any, List, Optional
import json
import re
import logging

import opik.exceptions as exceptions

LOGGER = logging.getLogger(__name__)


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


# Characters a judge wraps a verdict in: markdown emphasis/code and quotes.
_VERDICT_WRAPPERS = "*_`\"'\u201c\u201d\u2018\u2019 "
# The verdict word must end the word: no letter or digit follows it (any
# script), so "yes1", "no_foo" or "noé" stay unreadable. Underscores right
# after it must close the same number of opening ones ("_no_", "__yes__"), so a
# bare "yes_" or "no__" stays unreadable too.
_LEADING_YES_NO = re.compile(r"(yes|no)(_*)(?!\w)")
# Positions listed in the ignored-verdict warning; the counts are always full.
_MAX_LOGGED_POSITIONS = 20


def read_yes_no(verdict: Any) -> Optional[str]:
    """Read a judge ``verdict`` as ``"yes"``, ``"no"`` or ``None``.

    Folds case and surrounding whitespace like :func:`normalize_verdict`, but
    reads only the leading verdict word instead of trimming trailing
    punctuation, so every verdict that :func:`normalize_verdict` reduces to a
    bare ``yes`` or ``no`` reads the same here. It also reads the word when a
    judge wraps it in markdown or quotes (``"**No**"``, ``"`no`"``,
    ``'"no"'``) or explains it after the word (``"No, the reply ignores the
    request"``). The word must end there: underscores right after it count
    only when they close the same number of opening ones (``"_no_"``,
    ``"__yes__"``), so ``"yes_"``, ``"no__"`` or ``"_no__"`` return ``None``,
    as do glued words such as ``"yes1"`` or ``"no_foo"``. Anything else
    returns ``None``, so a caller can tell an unreadable verdict apart from a
    negative or positive one instead of counting it on either side.
    """
    if not isinstance(verdict, str):
        return None
    folded = verdict.strip().lower()
    text = folded.lstrip(_VERDICT_WRAPPERS)
    match = _LEADING_YES_NO.match(text)
    if match is None:
        return None
    closing = match.group(2)
    opening = folded[: len(folded) - len(text)].count("_")
    if closing and len(closing) != opening:
        return None
    return match.group(1)


def yes_share_of_readable_verdicts(verdicts: List[Any], metric_name: str) -> float:
    """Share of ``"yes"`` among the verdicts that read as ``"yes"`` or ``"no"``.

    Unreadable verdicts are left out of both counts, so they no longer count
    as positive: ``["no", "N/A"]`` scores 0.0, not 0.5. A warning gives their
    count and their 1-based verdict numbers (the first
    ``_MAX_LOGGED_POSITIONS``) only, never the verdict text, which is free-form
    judge output and can quote the conversation. When no verdict is readable,
    a ``MetricComputationError`` is raised: an empty read must not turn into a
    perfect or a zero score.
    """
    readings = [read_yes_no(verdict) for verdict in verdicts]
    readable = [reading for reading in readings if reading is not None]
    ignored = [
        number for number, reading in enumerate(readings, start=1) if reading is None
    ]
    if ignored:
        not_listed = len(ignored) - _MAX_LOGGED_POSITIONS
        LOGGER.warning(
            "%s: ignored %d of %d judge verdict(s) that are neither yes nor no "
            "(verdict numbers, 1-based: %s%s)",
            metric_name,
            len(ignored),
            len(readings),
            ignored[:_MAX_LOGGED_POSITIONS],
            f" and {not_listed} more" if not_listed > 0 else "",
        )
    if not readable:
        raise exceptions.MetricComputationError(
            f"{metric_name}: none of the {len(readings)} judge verdict(s) "
            "reads as yes or no"
        )
    return readable.count("yes") / len(readable)


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
