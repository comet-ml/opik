from opik import logging_messages
import pytest
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision
from opik.exceptions import MetricComputationError


def test_context_precision_score_out_of_range():
    metric = ContextPrecision()
    invalid_model_output = '{"context_precision_score": 1.2, "reason": "Score exceeds valid range."}'  # Score > 1.0

    with pytest.raises(
        MetricComputationError,
        match=logging_messages.CONTEXT_PRECISION_SCORE_CALC_FAILED,
    ):
        metric._parse_model_output(invalid_model_output)
