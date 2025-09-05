import pytest
import json
from opik import exceptions, logging_messages
from opik.evaluation.metrics.llm_judges.structure_output_compliance import parser
from opik.evaluation.metrics import score_result


def test_parse_valid_output_true():
    """Test parsing valid output with score=true"""
    content = json.dumps(
        {"score": True, "reason": ["Valid reason 1", "Valid reason 2"]}
    )
    result = parser.parse_model_output(content, "test_metric")

    assert isinstance(result, score_result.ScoreResult)
    assert result.value == 1.0
    assert result.reason == "Valid reason 1\nValid reason 2"
    assert result.name == "test_metric"


def test_parse_valid_output_false():
    """Test parsing valid output with score=false"""
    content = json.dumps({"score": False, "reason": ["Only one reason"]})
    result = parser.parse_model_output(content, "test_metric")

    assert result.value == 0.0
    assert result.reason == "Only one reason"


def test_parse_invalid_json():
    """Test parsing invalid JSON format"""
    content = '{"score": true, "reason": ["Missing closing brace"'

    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parser.parse_model_output(content, "test_metric")

    assert str(exc_info.value) == logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED


def test_parse_non_boolean_score():
    """Test handling non-boolean score value"""
    content = json.dumps(
        {
            "score": "true",  # String instead of boolean
            "reason": ["Should be boolean"],
        }
    )

    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parser.parse_model_output(content, "test_metric")

    assert str(exc_info.value) == logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED


def test_parse_invalid_reason_type():
    """Test handling non-list reason"""
    content = json.dumps(
        {
            "score": True,
            "reason": "Not a list",  # Should be list
        }
    )

    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parser.parse_model_output(content, "test_metric")

    assert str(exc_info.value) == logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED


def test_parse_non_string_reasons():
    """Test handling reason list with non-string elements"""
    content = json.dumps(
        {
            "score": False,
            "reason": ["Valid", 123, True],  # Contains non-strings
        }
    )

    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parser.parse_model_output(content, "test_metric")

    assert str(exc_info.value) == logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED


def test_parse_missing_score_field():
    """Test handling missing score field"""
    content = json.dumps({"reason": ["Missing score field"]})

    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parser.parse_model_output(content, "test_metric")

    assert str(exc_info.value) == logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED


def test_parse_missing_reason_field():
    """Test handling missing reason field"""
    content = json.dumps({"score": True})

    with pytest.raises(exceptions.MetricComputationError) as exc_info:
        parser.parse_model_output(content, "test_metric")
    assert str(exc_info.value) == logging_messages.STRUCTURED_OUTPUT_COMPLIANCE_FAILED
