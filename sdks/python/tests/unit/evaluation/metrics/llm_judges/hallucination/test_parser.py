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


def test_a_schema_compliant_list_reason_is_reported_as_text():
    """``HallucinationResponseFormat`` declares ``reason: List[str]`` and the
    prompt asks for ``["reason 1", "reason 2"]``, so the normal verdict arrives
    as a list. It must reach the user as the prose the metric docstring shows,
    not as a Python list literal."""
    metric_output = (
        '{"score": 0.8, "reason": ["contradicts the supplied context",'
        ' "invents a population figure"]}'
    )

    result = parser.parse_model_output(content=metric_output, name="m")

    assert (
        result.reason == "contradicts the supplied context\ninvents a population figure"
    )


def test_a_single_item_list_reason_has_no_list_syntax():
    result = parser.parse_model_output(
        content='{"score": 0.4, "reason": ["one unsupported claim"]}', name="m"
    )

    assert result.reason == "one unsupported claim"


def test_a_non_string_reason_is_still_rendered_as_text():
    """Models ignore ``response_format`` often enough that the string shape the
    prompt does not ask for stays a supported input."""
    result = parser.parse_model_output(
        content='{"score": 0.4, "reason": "one prose reason"}', name="m"
    )

    assert result.reason == "one prose reason"
