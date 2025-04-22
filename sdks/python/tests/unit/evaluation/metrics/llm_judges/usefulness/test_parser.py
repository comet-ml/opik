from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.usefulness import parser
import pytest
from opik.evaluation.metrics.llm_judges.usefulness.metric import Usefulness


def test_usefulness_score_out_of_range():
    metric = Usefulness()
    invalid_model_output = '{"usefulness_score": 1.5, "reason": "Score exceeds valid range."}'  # Score > 1.0

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.USEFULNESS_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)
