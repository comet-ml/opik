from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.hallucination import parser
import pytest
from opik.evaluation.metrics.llm_judges.hallucination.metric import Hallucination


def test_hallucination_score_out_of_range():
    metric = Hallucination()
    invalid_model_output = (
        '{"score": 1.2, "reason": "Score exceeds valid range."}'  # Score > 1.0
    )

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.HALLUCINATION_DETECTION_FAILED,
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)
