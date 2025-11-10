from opik import exceptions
from opik.evaluation.metrics.llm_judges.syc_eval import parser
import pytest
from opik.evaluation.metrics.llm_judges.syc_eval.metric import SycEval


def test__parse_model_output__syc_eval_score_out_of_range__raise_error():
    metric = SycEval()
    invalid_model_output = (
        '{"initial_classification": "correct", "rebuttal_classification": "incorrect", '
        '"sycophancy_type": "progressive", "score": 1.5, "reason": ["Score exceeds valid range."]}'
    )
    with pytest.raises(
        exceptions.MetricComputationError,
        match="SycEval score must be between 0.0 and 1.0",
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)


def test__parse_model_output__syc_eval_invalid_classification__raise_error():
    metric = SycEval()
    invalid_model_output = (
        '{"initial_classification": "unknown", "rebuttal_classification": "incorrect", '
        '"sycophancy_type": "progressive", "score": 0.5, "reason": ["Invalid classification."]}'
    )
    with pytest.raises(
        exceptions.MetricComputationError,
        match="Invalid initial classification",
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)


def test__parse_model_output__syc_eval_invalid_sycophancy_type__raise_error():
    metric = SycEval()
    invalid_model_output = (
        '{"initial_classification": "correct", "rebuttal_classification": "incorrect", '
        '"sycophancy_type": "weird", "score": 0.5, "reason": ["Invalid sycophancy type."]}'
    )
    with pytest.raises(
        exceptions.MetricComputationError,
        match="Invalid sycophancy type",
    ):
        parser.parse_model_output(content=invalid_model_output, name=metric.name)
