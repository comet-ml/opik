import pytest
from typing import Any, Dict, Optional

from opik.evaluation.metrics import score_result
from opik.evaluation.scorers.scorer_function import validate_scorer_function
from opik.message_processing.emulation import models


def test_validate_scorer_function_valid_function():
    """Test that a valid scorer function passes validation"""

    def valid_scorer(
        dataset_item: Dict[str, Any], task_outputs: Dict[str, Any]
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(valid_scorer)


def test_validate_scorer_function_valid_with_extra_params():
    """Test that a function with required params plus extras passes validation"""

    def valid_scorer_with_extras(
        dataset_item: Dict[str, Any],
        task_outputs: Dict[str, Any],
        extra_param: str = "default",
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(valid_scorer_with_extras)


def test_validate_scorer_function_not_callable__raises_error():
    """Test that non-callable objects raise ValueError"""
    not_callable = "not a function"

    with pytest.raises(
        ValueError,
        match="scorer_function must be a callable function",
    ):
        validate_scorer_function(not_callable)


def test_validate_scorer_function_no_parameters__raises_error():
    """Test that function with no parameters raises ValueError"""

    def no_params():
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match="scorer_function must have either both 'dataset_item' and 'task_outputs' parameters or at least one 'task_span' parameter",
    ):
        validate_scorer_function(no_params)


def test_validate_scorer_function_wrong_parameter_names__raises_error():
    """Test that function with wrong parameter names raises ValueError"""

    def wrong_names(input_data: Dict[str, Any], output_data: Dict[str, Any]):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match="scorer_function must have either both 'dataset_item' and 'task_outputs' parameters or at least one 'task_span' parameter",
    ):
        validate_scorer_function(wrong_names)


def test_validate_scorer_function_missing_dataset_item__raises_error():
    """Test that function missing dataset_item parameter raises ValueError"""

    def missing_dataset_item(task_outputs: Dict[str, Any], other_param: str):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match="scorer_function must have either both 'dataset_item' and 'task_outputs' parameters or at least one 'task_span' parameter",
    ):
        validate_scorer_function(missing_dataset_item)


def test_validate_scorer_function_missing_task_outputs__raises_error():
    """Test that function missing task_outputs parameter raises ValueError"""

    def missing_task_outputs(dataset_item: Dict[str, Any], other_param: str):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match="scorer_function must have either both 'dataset_item' and 'task_outputs' parameters or at least one 'task_span' parameter",
    ):
        validate_scorer_function(missing_task_outputs)


def test_validate_scorer_function_with_kwargs():
    """Test that function with **kwargs passes validation"""

    def scorer_with_kwargs(
        dataset_item: Dict[str, Any], task_outputs: Dict[str, Any], **kwargs
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_kwargs)


def test_validate_scorer_function_with_args_and_kwargs():
    """Test that function with *args and **kwargs passes validation"""

    def scorer_with_args_kwargs(
        dataset_item: Dict[str, Any], task_outputs: Dict[str, Any], *args, **kwargs
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_args_kwargs)


def test_validate_scorer_function_with_task_span_only():
    """Test that function with only the task_span parameter passes validation"""

    def scorer_with_task_span_only(
        task_span: Optional[models.SpanModel],
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_task_span_only)


def test_validate_scorer_function_with_task_span_and_other_params():
    """Test that function with task_span and other parameters passes validation"""

    def scorer_with_task_span_and_extras(
        task_span: Optional[models.SpanModel],
        extra_param: str = "default",
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_task_span_and_extras)


def test_validate_scorer_function_with_all_params():
    """Test that function with all parameters (dataset_item, task_outputs, task_span) passes validation"""

    def scorer_with_all_params(
        dataset_item: Dict[str, Any],
        task_outputs: Dict[str, Any],
        task_span: Optional[models.SpanModel] = None,
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_all_params)
