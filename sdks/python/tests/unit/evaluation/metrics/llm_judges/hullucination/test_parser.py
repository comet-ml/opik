from opik import logging_messages
from opik.evaluation.metrics.llm_judges.hallucination.parser import parse_model_output
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
        parse_model_output(content=invalid_model_output, name=metric.name)
