import logging
import json
import math
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
        score_token_position = 3
        if len(entries) <= score_token_position:
            return _extract_score_from_text_content(choice_dict, name=name)

        entry_dict = _to_dict(entries[score_token_position])
        top_logprobs = entry_dict.get("top_logprobs") or []
        token_candidate = str(entry_dict.get("token", ""))

        linear_probs_sum = 0.0
        weighted_score_sum = 0.0

        for candidate in top_logprobs:
            token_info = _to_dict(candidate)
            token_str = str(token_info.get("token", ""))
            if not token_str.isdecimal():
                continue

            score = int(token_str)
            if not 0 <= score <= 10:
                continue

            log_prob = token_info.get("logprob")
            if log_prob is None:
                continue

            linear_prob = math.exp(float(log_prob))
            linear_probs_sum += linear_prob
            weighted_score_sum += linear_prob * score

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
