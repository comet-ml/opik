from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.context_precision import parser
import pytest
from opik.evaluation.metrics.llm_judges.context_precision.metric import ContextPrecision


def test_context_precision_score_out_of_range():
    metric = ContextPrecision()
    invalid_model_output = '{"context_precision_score": 1.2, "reason": "Score exceeds valid range."}'  # Score > 1.0

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.CONTEXT_PRECISION_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)
