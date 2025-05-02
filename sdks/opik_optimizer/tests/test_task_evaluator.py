"""Tests for the TaskEvaluator class."""

import pytest
from unittest.mock import Mock, patch, MagicMock

from opik_optimizer.task_evaluator import evaluate
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.optimization_config.mappers import from_dataset_field, from_llm_response_text

def create_mock_metric_config():
    """Create a mock metric config for testing."""
    mock_metric = MagicMock()
    mock_metric.evaluate.return_value = 0.8
    return MagicMock(
        metric=mock_metric,
        inputs={
            "input": from_dataset_field(name="input"),
            "output": from_llm_response_text(),
        }
    )

def create_mock_evaluation_result(score):
    """Create a mock evaluation result for testing."""
    mock_result = MagicMock()
    mock_result.test_results = [MagicMock(score_results=[MagicMock(value=score)])]
    return mock_result

@patch('opik.evaluate')
def test_task_evaluator_basic(mock_evaluate):
    # Mock the predictor
    predictor = Mock()
    predictor.return_value = {"output": "test response"}
    
    # Mock the dataset
    dataset = Mock()
    dataset.get_items.return_value = [{"input": "test input"}]
    
    # Create mock metric config
    metric_config = create_mock_metric_config()
    
    # Mock the evaluation result
    mock_evaluate.return_value = create_mock_evaluation_result(0.8)
    
    # Call the task evaluator
    result = evaluate(
        dataset=dataset,
        evaluated_task=predictor,
        metric_config=metric_config,
        num_threads=1
    )
    
    # Verify the result
    assert isinstance(result, float)
    assert result == 0.8
    
    # Verify the mocks were called correctly
    dataset.get_items.assert_called_once()
    mock_evaluate.assert_called_once_with(
        task=predictor,
        items=[{"input": "test input"}],
        metric=metric_config.metric,
        num_threads=1,
        project_name=None,
        num_test=None
    )

@patch('opik.evaluate')
def test_task_evaluator_multiple_samples(mock_evaluate):
    # Mock the predictor
    predictor = Mock()
    predictor.side_effect = [
        {"output": "response 1"},
        {"output": "response 2"}
    ]
    
    # Mock the dataset
    dataset = Mock()
    dataset.get_items.return_value = [
        {"input": "test input 1"},
        {"input": "test input 2"}
    ]
    
    # Create mock metric config
    metric_config = create_mock_metric_config()
    
    # Mock the evaluation result
    mock_evaluate.return_value = create_mock_evaluation_result(0.85)
    
    # Call the task evaluator
    result = evaluate(
        dataset=dataset,
        evaluated_task=predictor,
        metric_config=metric_config,
        num_threads=1
    )
    
    # Verify the result
    assert isinstance(result, float)
    assert result == 0.85
    
    # Verify the mocks were called correctly
    assert dataset.get_items.call_count == 1
    mock_evaluate.assert_called_once_with(
        task=predictor,
        items=[{"input": "test input 1"}, {"input": "test input 2"}],
        metric=metric_config.metric,
        num_threads=1,
        project_name=None,
        num_test=None
    )

@patch('opik.evaluate')
def test_task_evaluator_empty_dataset(mock_evaluate):
    # Mock the predictor
    predictor = Mock()
    
    # Mock the dataset
    dataset = Mock()
    dataset.get_items.return_value = []
    
    # Create mock metric config
    metric_config = create_mock_metric_config()
    
    # Call the task evaluator
    result = evaluate(
        dataset=dataset,
        evaluated_task=predictor,
        metric_config=metric_config,
        num_threads=1
    )
    
    # Verify the result
    assert isinstance(result, float)
    assert result == 0.0
    
    # Verify the mocks were called correctly
    dataset.get_items.assert_called_once()
    mock_evaluate.assert_not_called()  # Should not be called for empty dataset

@patch('opik.evaluate')
def test_task_evaluator_error_handling(mock_evaluate):
    # Mock the predictor to raise an exception
    predictor = Mock()
    predictor.side_effect = Exception("Test error")
    
    # Mock the dataset
    dataset = Mock()
    dataset.get_items.return_value = [{"input": "test input"}]
    
    # Create mock metric config
    metric_config = create_mock_metric_config()
    
    # Mock the evaluation result
    mock_evaluate.return_value = create_mock_evaluation_result(0.0)
    
    # Call the task evaluator
    result = evaluate(
        dataset=dataset,
        evaluated_task=predictor,
        metric_config=metric_config,
        num_threads=1
    )
    
    # Verify the result
    assert isinstance(result, float)
    assert result == 0.0
    
    # Verify the mocks were called correctly
    dataset.get_items.assert_called_once()
    mock_evaluate.assert_called_once_with(
        task=predictor,
        items=[{"input": "test input"}],
        metric=metric_config.metric,
        num_threads=1,
        project_name=None,
        num_test=None
    ) 