import logging
import json
import math
from typing import TYPE_CHECKING
from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.metrics.llm_judges import parsing_helpers
from opik.logging_messages import GEVAL_SCORE_CALC_FAILED

if TYPE_CHECKING:  # TODO: Daniel check if this is needed
    from litellm.types.utils import ModelResponse

LOGGER = logging.getLogger(__name__)


def parse_model_output(
    content: "ModelResponse", name: str, log_probs_supported: bool
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
        if not log_probs_supported:
            dict_content = parsing_helpers.extract_json_content_or_raise(
                content.choices[0].message.content
            )

            score = float(dict_content["score"])
            if not 0 <= score <= 10:
                raise ValueError

            reason = str(dict_content["reason"])

            return score_result.ScoreResult(
                name=name,
                value=score / 10,
                reason=reason,
            )

        else:
            # Compute score using top logprobs
            score_token_position = 3
            log_probs_content = content.choices[0].model_extra["logprobs"]["content"][
                score_token_position
            ]

            top_score_logprobs = log_probs_content["top_logprobs"]
            log_probs_token = log_probs_content["token"]

            linear_probs_sum = 0.0
            weighted_score_sum = 0.0

            for token_info in top_score_logprobs:
                # litellm in v1.60.2 (or earlier) started provide logprobes
                # as pydantic model, not just dict
                # we will convert model to dict to provide backward compatability
                if not isinstance(token_info, dict):
                    token_info = token_info.model_dump()

                # if not a number
                if not token_info["token"].isdecimal():
                    continue

                score = int(token_info["token"])

                # if score value not in scale
                if not 0 <= score <= 10:
                    continue

                log_prob = token_info["logprob"]
                linear_prob = math.exp(log_prob)

                linear_probs_sum += linear_prob
                weighted_score_sum += linear_prob * score

            if linear_probs_sum != 0.0:
                final_score: float = weighted_score_sum / linear_probs_sum / 10
            else:
                # Handle cases where we can't find any matching tokens in the top_log_probs
                if not log_probs_token.isdecimal():
                    raise exceptions.MetricComputationError(GEVAL_SCORE_CALC_FAILED)

                final_score = int(log_probs_token) / 10

            if not (0.0 <= final_score <= 1.0):
                raise ValueError

            # Get the reason
            reason = json.loads(content.choices[0].message.content)["reason"]

            # Return the score and the reason
            return score_result.ScoreResult(name=name, value=final_score, reason=reason)
    except Exception as e:
        LOGGER.error(f"Failed to parse model output: {e}", exc_info=True)
        raise exceptions.MetricComputationError(GEVAL_SCORE_CALC_FAILED)
