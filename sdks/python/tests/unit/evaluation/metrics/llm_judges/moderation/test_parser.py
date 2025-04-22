from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.moderation import parser
import pytest
from opik.evaluation.metrics.llm_judges.moderation.metric import Moderation


def test_moderation_score_out_of_range():
    metric = Moderation()
    invalid_model_output = '{"moderation_score": -0.2, "reason": "Score below valid range."}'  # Score < 0.0

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.MODERATION_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)
