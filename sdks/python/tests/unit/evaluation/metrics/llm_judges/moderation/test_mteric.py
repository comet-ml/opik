from opik import logging_messages
import pytest
from opik.evaluation.metrics.llm_judges.moderation.metric import Moderation
from opik.exceptions import MetricComputationError


def test_moderation_score_out_of_range():
    metric = Moderation()
    invalid_model_output = '{"moderation_score": -0.2, "reason": "Score below valid range."}'  # Score < 0.0

    with pytest.raises(
        MetricComputationError, match=logging_messages.MODERATION_SCORE_CALC_FAILED
    ):
        metric._parse_model_output(invalid_model_output)
