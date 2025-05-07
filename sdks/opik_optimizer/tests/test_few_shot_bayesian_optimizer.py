import pytest
from unittest.mock import MagicMock, patch
from opik_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.optimization_config.configs import MetricConfig, TaskConfig, OptimizationConfig
from opik_optimizer.optimization_config.mappers import Mapper
from opik.evaluation.metrics import BaseMetric
import opik
import os
from opik_optimizer import optimization_result
from opik_optimizer import task_evaluator


@pytest.fixture
def mock_metric():
    """Create a mock metric with name and evaluate method."""
    metric = MagicMock(spec=BaseMetric)
    metric.name = "test_metric"
    metric.evaluate = MagicMock(return_value=0.8)
    return metric


@pytest.fixture
def mock_metric_config(mock_metric):
    """Create a mock metric config."""
    return MetricConfig(
        metric=mock_metric,
        inputs={
            "input": Mapper(name="input"),
            "output": Mapper(name="output")
        }
    )


@pytest.fixture
def mock_dataset():
    """Create a mock dataset."""
    dataset = MagicMock()
    dataset.name = "test_dataset"
    return dataset


@pytest.fixture
def mock_openai_client():
    """Create a mock OpenAI client."""
    client = MagicMock()
    client.chat.completions.create.return_value = MagicMock(
        choices=[MagicMock(message=MagicMock(content="Test response"))]
    )
    return client


class TestFewShotBayesianOptimizer:
    """Test cases for the FewShotBayesianOptimizer class."""

    @pytest.fixture(autouse=True)
    def setup(self):
        # Set up environment variables for tests
        os.environ["OPENAI_API_KEY"] = "test-api-key"
        yield
        # Clean up
        if "OPENAI_API_KEY" in os.environ:
            del os.environ["OPENAI_API_KEY"]

    def test_initialize_with_default_parameters(self):
        optimizer = FewShotBayesianOptimizer(model="gpt-3.5-turbo")
        assert optimizer.model == "gpt-3.5-turbo"
        assert optimizer.project_name is None
        assert optimizer.min_examples == 2
        assert optimizer.max_examples == 8
        assert optimizer.seed == 42
        assert optimizer.n_threads == 8
        assert optimizer.n_initial_prompts == 5
        assert optimizer.n_iterations == 10

    def test_initialize_with_different_parameters(self):
        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project",
            min_examples=3,
            max_examples=10,
            seed=123,
            n_threads=4,
            n_initial_prompts=7,
            n_iterations=15
        )
        assert optimizer.model == "gpt-3.5-turbo"
        assert optimizer.project_name == "test_project"
        assert optimizer.min_examples == 3
        assert optimizer.max_examples == 10
        assert optimizer.seed == 123
        assert optimizer.n_threads == 4
        assert optimizer.n_initial_prompts == 7
        assert optimizer.n_iterations == 15

    @patch('opik.evaluate')
    def test_optimize_prompt(self, mock_evaluate, mock_metric, mock_dataset, mock_openai_client):
        """Test prompt optimization."""
        mock_evaluate.return_value.test_results = [
            MagicMock(score_results=[MagicMock(value=0.8)])
        ]
        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project"
        )
    
        # Mock dataset with items
        mock_dataset.get_items.return_value = [
            {"id": "1", "input": "test input 1", "output": "test output 1"},
            {"id": "2", "input": "test input 2", "output": "test output 2"},
            {"id": "3", "input": "test input 3", "output": "test output 3"}
        ]
    
        metric_config = MetricConfig(
            metric=mock_metric,
            inputs={
                "input": Mapper(name="input"),
                "output": Mapper(name="output")
            }
        )
    
        result = optimizer.optimize_prompt(
            config=MagicMock(
                dataset=mock_dataset,
                objective=metric_config,
                task=MagicMock(
                    instruction_prompt="Test prompt",
                    input_dataset_fields=["input"],
                    output_dataset_field="output"
                )
            )
        )
    
        assert result.score == 0.8
        assert "n_examples" in result.metadata
        assert "example_indices" in result.metadata
        assert "prompt_template" in result.metadata

    @patch('opik.evaluate')
    def test_evaluate_prompt(self, mock_evaluate, mock_metric, mock_dataset, mock_openai_client):
        """Test prompt evaluation."""
        mock_evaluate.return_value.test_results = [
            MagicMock(score_results=[MagicMock(value=0.8)])
        ]
        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project"
        )

        metric_config = MetricConfig(
            metric=mock_metric,
            inputs={
                "input": Mapper(name="input"),
                "output": Mapper(name="output")
            }
        )

        # Ensure prompt is correctly formatted
        prompt = [{"role": "system", "content": "Test prompt"}]

        score = optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=mock_dataset,
            metric_config=metric_config
        )

        assert score == 0.8

    @patch('opik.evaluate')
    def test_evaluate_prompt_with_dataset_item_ids(self, mock_evaluate, mock_metric, mock_dataset, mock_openai_client):
        """Test prompt evaluation with dataset item IDs."""
        mock_evaluate.return_value.test_results = [
            MagicMock(score_results=[MagicMock(value=0.8)])
        ]
        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project"
        )

        metric_config = MetricConfig(
            metric=mock_metric,
            inputs={
                "input": Mapper(name="input"),
                "output": Mapper(name="output")
            }
        )

        dataset_item_ids = ["id1", "id2", "id3"]

        # Ensure prompt is correctly formatted
        prompt = [{"role": "system", "content": "Test prompt"}]

        score = optimizer.evaluate_prompt(
            prompt=prompt,
            dataset=mock_dataset,
            metric_config=metric_config,
            dataset_item_ids=dataset_item_ids
        )

        assert score == 0.8

    def test_split_dataset(self):
        dataset = [
            {"id": "1", "input": "test1"},
            {"id": "2", "input": "test2"},
            {"id": "3", "input": "test3"},
            {"id": "4", "input": "test4"}
        ]

        # Create an instance to access the protected method
        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project"
        )
        
        # Since _split_dataset is not implemented, we'll test the expected behavior
        train_set, validation_set = optimizer._split_dataset(
            dataset, train_ratio=0.5
        )

        assert len(train_set) == 2
        assert len(validation_set) == 2
        assert all(item in dataset for item in train_set)
        assert all(item in dataset for item in validation_set)
        assert not any(item in train_set for item in validation_set)

    def test_split_dataset_with_empty_dataset(self):
        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project"
        )
        
        train_set, validation_set = optimizer._split_dataset([], train_ratio=0.5)
        
        assert len(train_set) == 0
        assert len(validation_set) == 0

    def test_split_dataset_with_custom_ratio(self):
        dataset = [
            {"id": "1", "input": "test1"},
            {"id": "2", "input": "test2"},
            {"id": "3", "input": "test3"},
            {"id": "4", "input": "test4"},
            {"id": "5", "input": "test5"}
        ]

        optimizer = FewShotBayesianOptimizer(
            model="gpt-3.5-turbo",
            project_name="test_project"
        )
        
        train_set, validation_set = optimizer._split_dataset(
            dataset, train_ratio=0.6
        )

        assert len(train_set) == 3  # 5 * 0.6 = 3
        assert len(validation_set) == 2  # 5 - 3 = 2 