import pytest
from opik_optimizer.optimization_config.configs import (
    MetricConfig, TaskConfig, OptimizationConfig
)
from opik_optimizer.optimization_config.mappers import (
    from_dataset_field, from_llm_response_text
)
from unittest.mock import MagicMock
from opik.evaluation.metrics import BaseMetric
import opik


class TestOptimizationDSL:
    @pytest.fixture
    def exact_match_metric(self):
        metric = MagicMock(spec=BaseMetric)
        metric.__class__ = BaseMetric
        metric.name = "exact_match"
        return metric

    @pytest.fixture
    def bleu_metric(self):
        metric = MagicMock(spec=BaseMetric)
        metric.__class__ = BaseMetric
        metric.name = "bleu"
        return metric

    @pytest.fixture
    def rouge_metric(self):
        metric = MagicMock(spec=BaseMetric)
        metric.__class__ = BaseMetric
        metric.name = "rouge"
        return metric

    @pytest.fixture
    def mock_dataset(self):
        return MagicMock(spec=opik.Dataset)

    def test_optimization_config_initialization(
        self,
        exact_match_metric,
        mock_dataset
    ):
        task_config = TaskConfig(
            instruction_prompt="Test instruction",
            input_dataset_fields=["input"],
            output_dataset_field="output"
        )

        config = OptimizationConfig(
            dataset=mock_dataset,
            objective=MetricConfig(
                metric=exact_match_metric,
                inputs={
                    "input": from_dataset_field(name="input"),
                    "output": from_llm_response_text(),
                }
            ),
            task=task_config
        )
        assert config.dataset == mock_dataset
        assert isinstance(config.objective, MetricConfig)
        assert isinstance(config.task, TaskConfig)

    def test_optimization_config_minimize(
        self,
        exact_match_metric,
        mock_dataset
    ):
        task_config = TaskConfig(
            instruction_prompt="Test instruction",
            input_dataset_fields=["input"],
            output_dataset_field="output"
        )

        config = OptimizationConfig(
            dataset=mock_dataset,
            objective=MetricConfig(
                metric=exact_match_metric,
                inputs={
                    "input": from_dataset_field(name="input"),
                    "output": from_llm_response_text(),
                }
            ),
            task=task_config,
            optimization_direction="minimize"
        )
        assert config.optimization_direction == "minimize"

    def test_optimization_config_serialization(
        self,
        exact_match_metric,
        mock_dataset
    ):
        task_config = TaskConfig(
            instruction_prompt="Test instruction",
            input_dataset_fields=["input"],
            output_dataset_field="output"
        )

        config = OptimizationConfig(
            dataset=mock_dataset,
            objective=MetricConfig(
                metric=exact_match_metric,
                inputs={
                    "input": from_dataset_field(name="input"),
                    "output": from_llm_response_text(),
                }
            ),
            task=task_config
        )
        config_dict = config.model_dump()
        assert "dataset" in config_dict
        assert "objective" in config_dict
        assert "task" in config_dict 
