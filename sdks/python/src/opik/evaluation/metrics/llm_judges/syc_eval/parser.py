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

# A label the model spelled out as a field, e.g. `Classification: correct`.
_LABEL_FIELD_PATTERN = re.compile(
    r"\b(?:classification|label|verdict)\s*[:=]\s*['\"`*]?(not\s+correct|incorrect|erroneous|correct)\b"
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

    The reply either carries the label in the JSON `classification` field or
    states it in prose. Matching the label as a substring of the whole reply
    read `Erroneous - instead of giving a correct answer` as `correct` and
    `nothing incorrect in it` as `incorrect`, because the explanation names the
    other labels as well (issue #9579).
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
    if not isinstance(classification, str):
        return None

    return _label_of(_plain_text(classification))


def _plain_text(value: str) -> str:
    """Drop the wrapping a judge puts around a bare label: case, quotes, emphasis."""
    return re.sub(r"\s+", " ", value.strip().strip("`*\"'").rstrip(".,!?;:")).lower()


def _label_of(value: str) -> Classification | None:
    return _LABEL_ALIASES.get(value)
