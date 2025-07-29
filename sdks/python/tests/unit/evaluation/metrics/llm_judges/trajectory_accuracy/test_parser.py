import pytest

from opik import exceptions
from opik.evaluation.metrics.llm_judges.trajectory_accuracy import parser
from opik.evaluation.metrics import score_result


def test_parse_evaluation_response_valid_response():
    """Test parsing valid evaluation response using public parser API."""
    content = '{"score": 0.75, "explanation": "Decent trajectory with some issues"}'

    result = parser.parse_evaluation_response(content, "test_metric")

    assert isinstance(result, score_result.ScoreResult)
    assert result.value == 0.75
    assert result.reason == "Decent trajectory with some issues"
    assert result.name == "test_metric"


def test_parse_evaluation_response_score_out_of_range():
    """Test parsing response with score out of valid range using public parser API."""
    content = '{"score": 1.5, "explanation": "Score too high"}'

    with pytest.raises(
        exceptions.MetricComputationError, match="Invalid response format"
    ):
        parser.parse_evaluation_response(content, "test_metric")
