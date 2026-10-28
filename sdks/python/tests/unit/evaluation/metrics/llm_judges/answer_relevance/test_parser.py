from opik import logging_messages, exceptions
from opik.evaluation.metrics.llm_judges.answer_relevance import parser
import pytest
from opik.evaluation.metrics.llm_judges.answer_relevance.metric import AnswerRelevance


def test_answer_relevance_score_out_of_range():
    metric = AnswerRelevance()
    invalid_model_output = '{"answer_relevance_score": -0.5, "reason": "Score below valid range."}'  # Score < 0.0

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)


def test_answer_relevance_score_returned_as_a_numeric_string_is_scored():
    metric = AnswerRelevance()
    model_output = '{"answer_relevance_score": "0.8", "reason": "The answer addresses the question."}'

    result = parser.parse_model_output(content=model_output, name=metric.name)

    assert result.value == pytest.approx(0.8)


def test_answer_relevance_whole_number_score_is_reported_as_a_float():
    metric = AnswerRelevance()
    model_output = (
        '{"answer_relevance_score": 1, "reason": "The answer is fully relevant."}'
    )

    result = parser.parse_model_output(content=model_output, name=metric.name)

    assert result.value == 1.0
    assert isinstance(result.value, float)


def test_answer_relevance_non_numeric_score_still_fails():
    metric = AnswerRelevance()
    model_output = (
        '{"answer_relevance_score": "highly relevant", "reason": "Not a number."}'
    )

    with pytest.raises(
        exceptions.MetricComputationError,
        match=logging_messages.ANSWER_RELEVANCE_SCORE_CALC_FAILED,
    ):
        parser.parse_model_output(content=model_output, name=metric.name)
