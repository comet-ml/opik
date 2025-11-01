import pytest
from typing import Any, Dict

from opik.evaluation.metrics import score_result
from opik.evaluation.scorers.scorer_function import validate_scorer_function


def test_validate_scorer_function_valid_function():
    """Test that a valid scorer function passes validation"""

    def valid_scorer(
        scoring_inputs: Dict[str, Any], task_outputs: Dict[str, Any]
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(valid_scorer)


def test_validate_scorer_function_valid_with_extra_params():
    """Test that a function with required params plus extras passes validation"""

    def valid_scorer_with_extras(
        scoring_inputs: Dict[str, Any],
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
        match="scorer_function must be a callable function that takes two arguments: *.",
    ):
        validate_scorer_function(not_callable)


def test_validate_scorer_function_no_parameters__raises_error():
    """Test that function with no parameters raises ValueError"""

    def no_params():
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match="scorer_function must take at least two arguments:  *.",
    ):
        validate_scorer_function(no_params)


def test_validate_scorer_function_one_parameter__raises_error():
    """Test that function with only one parameter raises ValueError"""

    def one_param(scoring_inputs: Dict[str, Any]):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match="scorer_function must take at least two arguments:  *.",
    ):
        validate_scorer_function(one_param)


def test_validate_scorer_function_wrong_parameter_names__raises_error():
    """Test that function with wrong parameter names raises ValueError"""

    def wrong_names(input_data: Dict[str, Any], output_data: Dict[str, Any]):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match=r"scorer_function must take at least two arguments: \['scoring_inputs', 'task_outputs'\] - the scoring_inputs is not found in function parameters",
    ):
        validate_scorer_function(wrong_names)


def test_validate_scorer_function_missing_scoring_inputs__raises_error():
    """Test that function missing scoring_inputs parameter raises ValueError"""

    def missing_scoring_inputs(task_outputs: Dict[str, Any], other_param: str):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match=r"scorer_function must take at least two arguments: \['scoring_inputs', 'task_outputs'\] - the scoring_inputs is not found in function parameters",
    ):
        validate_scorer_function(missing_scoring_inputs)


def test_validate_scorer_function_missing_task_outputs__raises_error():
    """Test that function missing task_outputs parameter raises ValueError"""

    def missing_task_outputs(scoring_inputs: Dict[str, Any], other_param: str):
        return score_result.ScoreResult(name="test", value=1.0)

    with pytest.raises(
        ValueError,
        match=r"scorer_function must take at least two arguments: \['scoring_inputs', 'task_outputs'\] - the task_outputs is not found in function parameters",
    ):
        validate_scorer_function(missing_task_outputs)


def test_validate_scorer_function_with_kwargs():
    """Test that function with **kwargs passes validation"""

    def scorer_with_kwargs(
        scoring_inputs: Dict[str, Any], task_outputs: Dict[str, Any], **kwargs
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_kwargs)


def test_validate_scorer_function_with_args_and_kwargs():
    """Test that function with *args and **kwargs passes validation"""

    def scorer_with_args_kwargs(
        scoring_inputs: Dict[str, Any], task_outputs: Dict[str, Any], *args, **kwargs
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test", value=1.0)

    # Should not raise any exception
    validate_scorer_function(scorer_with_args_kwargs)
