from opik.evaluation.metrics.arguments_helpers import create_scoring_inputs
from ...testlib.assert_helpers import assert_dicts_equal


def test_create_scoring_inputs_no_mapping():
    """Test when scoring_key_mapping is None"""
    dataset_item = {"input": "hello", "expected": "world"}
    task_output = {"output": "hello, world"}

    result = create_scoring_inputs(
        dataset_item=dataset_item, task_output=task_output, scoring_key_mapping=None
    )

    expected = {"input": "hello", "expected": "world", "output": "hello, world"}
    assert result == expected


def test_create_scoring_inputs_string_mapping():
    """Test when scoring_key_mapping contains string mappings"""
    dataset_item = {"input": "hello", "ground_truth": "world"}
    task_output = {"model_output": "hello, world"}

    mapping = {"expected": "ground_truth", "output": "model_output"}

    result = create_scoring_inputs(
        dataset_item=dataset_item, task_output=task_output, scoring_key_mapping=mapping
    )

    expected = {
        "input": "hello",
        "ground_truth": "world",
        "model_output": "hello, world",
        "expected": "world",
        "output": "hello, world",
    }
    assert_dicts_equal(result, expected)


def test_create_scoring_inputs_callable_mapping():
    """Test when scoring_key_mapping contains callable mappings for nested dictionaries"""
    dataset_item = {
        "input": {"message": "hello"},
        "expected_output": {"message": "world"},
    }
    task_output = {"result": "hello world"}

    mapping = {"reference": lambda x: x["expected_output"]["message"]}

    result = create_scoring_inputs(
        dataset_item=dataset_item, task_output=task_output, scoring_key_mapping=mapping
    )

    expected = {
        "input": {"message": "hello"},
        "expected_output": {"message": "world"},
        "result": "hello world",
        "reference": "world",
    }
    assert_dicts_equal(result, expected)


def test_create_scoring_inputs_missing_mapping_key():
    """Test when a mapped key doesn't exist in the inputs"""
    dataset_item = {"input": "hello"}
    task_output = {"output": "world"}

    mapping = {
        "expected": "ground_truth"  # This key doesn't exist in inputs
    }

    result = create_scoring_inputs(
        dataset_item=dataset_item, task_output=task_output, scoring_key_mapping=mapping
    )

    expected = {"input": "hello", "output": "world"}
    assert_dicts_equal(result, expected)


def test_create_scoring_inputs_empty_mapping():
    """Test when scoring_key_mapping is an empty dictionary"""
    dataset_item = {"input": "hello"}
    task_output = {"output": "world"}

    result = create_scoring_inputs(
        dataset_item=dataset_item, task_output=task_output, scoring_key_mapping={}
    )

    expected = {"input": "hello", "output": "world"}
    assert_dicts_equal(result, expected)
