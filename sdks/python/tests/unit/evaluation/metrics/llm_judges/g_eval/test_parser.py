from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.g_eval import parser
import pytest


def test_g_eval__parse_model_output_string__score_out_of_range__MetricComputationErrorRaised():
    invalid_model_output = (
        '{"g_eval_score": 1.8, "reason": "Score exceeds valid range."}'  # Score > 1.0
    )

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.GEVAL_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output_string(
            content=invalid_model_output,
            metric_name="g_eval",
        )
