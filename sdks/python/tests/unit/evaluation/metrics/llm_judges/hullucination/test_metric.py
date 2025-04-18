from opik import logging_messages
import pytest
from opik.evaluation.metrics.llm_judges.hallucination.metric import Hallucination
from opik.exceptions import MetricComputationError


def test_hallucination_score_out_of_range():
    metric = Hallucination()
    invalid_model_output = (
        '{"score": 1.2, "reason": "Score exceeds valid range."}'  # Score > 1.0
    )

    with pytest.raises(
        MetricComputationError, match=logging_messages.HALLUCINATION_DETECTION_FAILED
    ):
        metric._parse_model_output(invalid_model_output)
