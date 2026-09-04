import logging
import json
import math
import re
from typing import Any, Dict, TYPE_CHECKING
import opik.exceptions as exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers
from opik.logging_messages import GEVAL_SCORE_CALC_FAILED

if TYPE_CHECKING:  # TODO: Daniel check if this is needed
    from litellm.types.utils import ModelResponse as LiteLLMModelResponse

LOGGER = logging.getLogger(__name__)


def parse_model_output_string(
    content: str, metric_name: str
) -> score_result.ScoreResult:
    try:
        dict_content = parsing_helpers.extract_json_content_or_raise(content)

        score_raw = float(dict_content["score"])

        if not 0 <= score_raw <= 10:
            raise ValueError(
                f"LLM returned score outside of [0, 10] range: {score_raw}"
            )

        normalised_score = score_raw / 10

        reason = str(dict_content["reason"])

        return score_result.ScoreResult(
            name=metric_name,
            value=normalised_score,
            reason=reason,
        )
    except Exception as exception:
        LOGGER.error(f"Failed to parse model output: {exception}", exc_info=True)
        raise exceptions.MetricComputationError(GEVAL_SCORE_CALC_FAILED) from exception


def parse_litellm_model_output(
    content: "LiteLLMModelResponse", name: str, log_probs_supported: bool
) -> score_result.ScoreResult:
    """
    This method computes the final score based on the model's response. The model's response is a dictionary
    with a `score` key and a `reason` key. The prompt template also specifies that the score should be an integer
    between 0 and 10.

    In order to make the score computation more robust, we look at the top logprobs of the score token and compute
    a weighted average of the scores. Since we try to enforce the format of the model's response, we can assume that
    the score token is always the fourth token in the response (first token is `{"`, followed by `score` and `":`).
    """
    try:
        choice_dict = _normalise_first_choice(content)

        if not log_probs_supported:
            return _extract_score_from_text_content(choice_dict, name=name)

        log_probs = _to_dict(choice_dict.get("logprobs"))
        entries = log_probs.get("content") or []
        if len(entries) <= 3:
            return _extract_score_from_text_content(choice_dict, name=name)

        # Locate the score token(s) by content instead of assuming a fixed
        # offset: tokenizers differ on where the whitespace after `"score":`
        # lands, and a two-digit score ("10") spans two tokens.
        entry_indices = _locate_score_entries(entries)
        if entry_indices is None:
            LOGGER.debug(
                "g_eval score key not found in the reconstructed response; "
                "falling back to the legacy fixed token offset. Reconstructed "
                "response: %r",
                "".join(str(_to_dict(entry).get("token", "")) for entry in entries),
            )
            entry_indices = [3]

        (
            linear_probs_sum,
            weighted_score_sum,
            token_candidate,
        ) = _weighted_score_sums(entries, entry_indices)

        if linear_probs_sum != 0.0:
            final_score: float = weighted_score_sum / linear_probs_sum / 10
        else:
            if not token_candidate.isdecimal():
                raise exceptions.MetricComputationError(GEVAL_SCORE_CALC_FAILED)
            final_score = int(token_candidate) / 10

        if not (0.0 <= final_score <= 1.0):
            raise ValueError(
                f"Failed to compute final score from log_probs, the value is out of [0, 1] range: {final_score}"
            )

        reason_data = json.loads(_extract_message_content(choice_dict))
        reason = reason_data["reason"]
        return score_result.ScoreResult(name=name, value=final_score, reason=reason)
    except Exception as exception:
        LOGGER.error(f"Failed to parse model output: {exception}", exc_info=True)
        raise exceptions.MetricComputationError(GEVAL_SCORE_CALC_FAILED) from exception


_SCORE_KEY_RE = re.compile(r'"score"\s*:\s*(\d+)')


def _locate_score_entries(entries: list) -> list[int] | None:
    """Find the entry indices whose tokens carry the score digits.

    Reconstructs the decoded text from the token stream and locates the
    digits after `"score":`; returns the one or two indices covering them,
    or None when the key cannot be found in the reconstructed text (the
    caller then falls back to the legacy fixed offset).

    Uses the LAST match so duplicate `"score"` keys resolve the same way
    json.loads does — to the final one, which is also what the no-logprob
    text path reads via `dict_content["score"]`.
    """
    token_texts = [str(_to_dict(entry).get("token", "")) for entry in entries]
    full_text = "".join(token_texts)
    matches = list(_SCORE_KEY_RE.finditer(full_text))
    if not matches:
        return None
    match = matches[-1]
    start, end = match.start(1), match.end(1)
    offsets = []
    position = 0
    for text in token_texts:
        offsets.append((position, position + len(text)))
        position += len(text)
    indices = [
        index
        for index, (begin, stop) in enumerate(offsets)
        if stop > start and begin < end
    ]
    if not 1 <= len(indices) <= 2:
        return None
    return indices


def _decimal_candidates(entry) -> list:
    return [
        (str(info.get("token", "")).strip(), math.exp(float(info["logprob"])))
        for info in (_to_dict(cand) for cand in (entry.get("top_logprobs") or []))
        if str(info.get("token", "")).strip().isdecimal()
        and info.get("logprob") is not None
    ]


def _weighted_score_sums(entries: list, entry_indices: list[int]) -> tuple:
    """Weighted [0, 10] score mass over the candidate space of the score token(s).

    A single-entry span averages that entry's decimal candidates (the
    legacy behaviour, plus leading/trailing whitespace tolerance). A
    two-entry span additionally combines the two positions' candidates
    ("1" + "0" -> 10) and counts a first-position candidate only when it
    covers the whole digit span ("10"), so the chosen digits are not
    double-counted.
    """
    token_candidate = "".join(
        str(_to_dict(entries[index]).get("token", "")) for index in entry_indices
    ).strip()

    linear_probs_sum = 0.0
    weighted_score_sum = 0.0

    if len(entry_indices) == 1:
        for token, prob in _decimal_candidates(entries[entry_indices[0]]):
            score = int(token)
            if not 0 <= score <= 10:
                continue
            linear_probs_sum += prob
            weighted_score_sum += prob * score
        return linear_probs_sum, weighted_score_sum, token_candidate

    digits = token_candidate
    first_candidates = _decimal_candidates(entries[entry_indices[0]])
    second_candidates = _decimal_candidates(entries[entry_indices[1]])

    for token_a, prob_a in first_candidates:
        if token_a == digits and 0 <= int(token_a) <= 10:
            # one token covering the whole span (alternative tokenization)
            linear_probs_sum += prob_a
            weighted_score_sum += prob_a * int(token_a)
        for token_b, prob_b in second_candidates:
            combined = token_a + token_b
            if not combined.isdecimal() or not 0 <= int(combined) <= 10:
                continue
            prob = prob_a * prob_b
            linear_probs_sum += prob
            weighted_score_sum += prob * int(combined)

    return linear_probs_sum, weighted_score_sum, token_candidate


def _extract_score_from_text_content(
    choice: Dict[str, Any], name: str
) -> score_result.ScoreResult:
    text_content = _extract_message_content(choice)
    return parse_model_output_string(text_content, name)


def _extract_message_content(choice: Dict[str, Any]) -> str:
    message = choice.get("message")
    if isinstance(message, dict):
        content = message.get("content")
    else:
        content = getattr(message, "content", None)

    if not isinstance(content, str):
        raise ValueError("LLM response is missing textual content")

    return content


def _normalise_choice(choice: Any) -> Dict[str, Any]:
    choice_dict = _to_dict(choice)
    if choice_dict:
        return choice_dict
    return {
        "message": getattr(choice, "message", None),
        "logprobs": getattr(choice, "logprobs", None),
    }


def _normalise_first_choice(response: Any) -> Dict[str, Any]:
    choices = getattr(response, "choices", None)
    if not isinstance(choices, list) or not choices:
        raise exceptions.MetricComputationError(
            "LLM response did not contain any choices to parse."
        )
    return _normalise_choice(choices[0])


def _to_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if hasattr(value, "model_dump") and callable(value.model_dump):
        try:
            return value.model_dump()
        except TypeError:
            pass
    if hasattr(value, "__dict__"):
        return dict(value.__dict__)
    return {}
