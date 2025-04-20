from opik import logging_messages
from opik.evaluation.metrics.llm_judges.moderation.parser import parse_model_output
import pytest
from opik.evaluation.metrics.llm_judges.moderation.metric import Moderation
from opik.exceptions import MetricComputationError


def test_moderation_score_out_of_range():
    metric = Moderation()
    invalid_model_output = '{"moderation_score": -0.2, "reason": "Score below valid range."}'  # Score < 0.0

    with pytest.raises(
        MetricComputationError, match=logging_messages.MODERATION_SCORE_CALC_FAILED
    ):
        parse_model_output(content=invalid_model_output, name=metric.name)
