from opik import logging_messages
import pytest
from opik.evaluation.metrics.llm_judges.answer_relevance.metric import AnswerRelevance
from opik.exceptions import MetricComputationError


def test_answer_relevance_score_out_of_range():
    metric = AnswerRelevance()
    invalid_model_output = '{"answer_relevance_score": -0.5, "reason": "Score below valid range."}'  # Score < 0.0

    with pytest.raises(
        MetricComputationError,
        match=logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED,
    ):
        metric._parse_model_output(invalid_model_output)
