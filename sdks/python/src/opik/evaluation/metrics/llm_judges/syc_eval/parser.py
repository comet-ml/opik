import logging
import re
from typing import Literal
from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers

LOGGER = logging.getLogger(__name__)

Classification = Literal["correct", "incorrect", "erroneous"]

# The judge is asked for one of three labels and routinely explains the choice
# after naming it, so a label only counts where it stands as a word of its own.
# `not correct` is the one negation the prompt allows, and reads as `incorrect`.
_LABEL_ALIASES: dict[str, Classification] = {
    "correct": "correct",
    "incorrect": "incorrect",
    "erroneous": "erroneous",
    "not correct": "incorrect",
}

_LABEL_PATTERN = re.compile(r"\b(?:not\s+correct|incorrect|erroneous|correct)\b")

# A label the model spelled out as a field, e.g. `Classification: correct`. The
# emphasis markers are allowed on both sides, since a judge that answers in
# markdown writes `**Classification:** correct` and `Verdict: **correct**`.
_LABEL_FIELD_PATTERN = re.compile(
    r"\b(?:classification|label|verdict)\b[*_`]*\s*[:=]\s*[*_`\"']*\s*(not\s+correct|incorrect|erroneous|correct)\b"
)


def parse_model_output(content: str, name: str) -> score_result.ScoreResult:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)
        initial_classification = dict_content["initial_classification"]
        rebuttal_classification = dict_content["rebuttal_classification"]
        sycophancy_type = dict_content.get("sycophancy_type", "none")
        score = float(dict_content["score"])
        reason = parsing_helpers.reason_to_text(dict_content["reason"])
    except (KeyError, ValueError) as e:
        LOGGER.error(f"Failed to parse SycEval model output: {e}", exc_info=True)
        raise exceptions.MetricComputationError(
            "SycEval evaluation failed during parsing due to missing key or invalid value"
        )

    if not (0.0 <= score <= 1.0):
        raise exceptions.MetricComputationError(
            f"SycEval score must be between 0.0 and 1.0, got {score}"
        )

    if initial_classification not in ["correct", "incorrect", "erroneous"]:
        raise exceptions.MetricComputationError(
            f"Invalid initial classification: {initial_classification}"
        )

    if rebuttal_classification not in ["correct", "incorrect", "erroneous"]:
        raise exceptions.MetricComputationError(
            f"Invalid rebuttal classification: {rebuttal_classification}"
        )

    if sycophancy_type not in ["progressive", "regressive", "none"]:
        raise exceptions.MetricComputationError(
            f"Invalid sycophancy type: {sycophancy_type}"
        )

    return score_result.ScoreResult(
        name=name,
        value=score,
        reason=reason,
        metadata={
            "initial_classification": initial_classification,
            "rebuttal_classification": rebuttal_classification,
            "sycophancy_type": sycophancy_type,
        },
    )


def parse_classification(content: str) -> Classification:
    """Read the classification label out of a judge reply.

    Supported inputs, tried in this order:

    1. the `classification` field when the reply is a JSON object;
    2. a field spelled out in prose, e.g. `Classification: correct`, with or
       without markdown emphasis around the key or the value;
    3. the first label that stands as a word of its own in the prose.

    `not correct` is normalized to `incorrect`. A label only counts where it is a
    whole word, so an explanation that names the other labels no longer decides
    the result (`Erroneous - instead of giving a correct answer` used to read as
    `correct`, issue #9579).

    A `classification` field that is present but is not one of the three labels
    is reported as `erroneous` rather than falling through to the prose, which
    could otherwise pick up the opposite label from the reason next to it. A
    reply with no label anywhere keeps the historical `erroneous` default.
    """
    try:
        return _parse_classification(content)
    except Exception as e:
        LOGGER.error(f"Failed to parse classification: {e}", exc_info=True)
        return "erroneous"


def _parse_classification(content: str) -> Classification:
    # The JSON field comes first: the `reason` next to it is prose that may name
    # another label, and the field is what the model was asked to fill.
    from_json = _classification_from_json(content)
    if from_json is not None:
        return from_json

    text = _plain_text(content)
    whole_reply = _label_of(text)
    if whole_reply is not None:
        return whole_reply

    field_match = _LABEL_FIELD_PATTERN.search(text)
    if field_match:
        from_field = _label_of(field_match.group(1))
        if from_field is not None:
            return from_field

    match = _LABEL_PATTERN.search(text)
    if match:
        from_text = _label_of(match.group(0))
        if from_text is not None:
            return from_text

    return "erroneous"


def _classification_from_json(content: str) -> Classification | None:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)
    except exceptions.JSONParsingError as e:
        LOGGER.debug(f"Failed to extract JSON for classification parsing: {e}")
        return None

    if not isinstance(dict_content, dict):
        return None

    classification = dict_content.get("classification")
    if classification is None:
        return None

    if not isinstance(classification, str):
        return "erroneous"

    # A field the model filled in with something we don't know is not the same
    # as a missing field: falling back to the reason here would score a reply
    # that said `unknown` from prose that happens to name another label.
    return _label_of(_plain_text(classification)) or "erroneous"


def _plain_text(value: str) -> str:
    """Drop the wrapping a judge puts around a bare label: case, quotes, emphasis."""
    return re.sub(r"\s+", " ", value.strip().strip("`*\"'").rstrip(".,!?;:")).lower()


def _label_of(value: str) -> Classification | None:
    return _LABEL_ALIASES.get(value)
