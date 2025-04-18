from opik import logging_messages
import pytest
from opik.evaluation.metrics.llm_judges.context_recall.metric import ContextRecall
from opik.exceptions import MetricComputationError


def test_context_recall_score_out_of_range():
    metric = ContextRecall()
    invalid_model_output = '{"context_recall_score": -0.1, "reason": "Score below valid range."}'  # Score < 0.0

    with pytest.raises(
        MetricComputationError, match=logging_messages.CONTEXT_RECALL_SCORE_CALC_FAILED
    ):
        metric._parse_model_output(invalid_model_output)
