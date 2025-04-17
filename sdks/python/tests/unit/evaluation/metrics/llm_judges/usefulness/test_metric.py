from opik import logging_messages
import pytest
from opik.evaluation.metrics.llm_judges.usefulness.metric import Usefulness
from opik.exceptions import MetricComputationError

def test_usefulness_score_out_of_range():
    metric = Usefulness()
    invalid_model_output = '{"usefulness_score": 1.5, "reason": "Score exceeds valid range."}'  # Score > 1.0

    with pytest.raises(MetricComputationError, match=logging_messages.USEFULNESS_SCORE_CALC_FAILED):
        metric._parse_model_output(invalid_model_output)