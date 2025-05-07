import pytest
from opik_optimizer.optimization_config.configs import MetricConfig, TaskConfig, OptimizationConfig
from opik_optimizer.optimization_config.mappers import from_dataset_field, from_llm_response_text
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

    def test_metric_config_initialization(self, exact_match_metric):
        config = MetricConfig(
            metric=exact_match_metric,
            inputs={
                "input": from_dataset_field(name="input"),
                "output": from_llm_response_text(),
            }
        )
        assert config.metric == exact_match_metric
        assert isinstance(config.inputs["input"], str)
        assert isinstance(config.inputs["output"], str)

    def test_metric_config_with_different_metrics(self, exact_match_metric, bleu_metric, rouge_metric):
        for metric in [exact_match_metric, bleu_metric, rouge_metric]:
            config = MetricConfig(
                metric=metric,
                inputs={
                    "input": from_dataset_field(name="input"),
                    "output": from_llm_response_text(),
                }
            )
            assert config.metric == metric

    def test_metric_config_with_different_input_mappings(self, exact_match_metric):
        input_mappings = [
            {"input": "input", "output": "output"},
            {"question": "input", "answer": "output"},
            {"text": "input", "summary": "output"}
        ]

        for inputs in input_mappings:
            config = MetricConfig(
                metric=exact_match_metric,
                inputs={
                    k: from_dataset_field(name=v) for k, v in inputs.items()
                }
            )
            assert all(isinstance(value, str) for value in config.inputs.values())

    def test_metric_config_validation(self, exact_match_metric):
        # Test empty inputs
        with pytest.raises(ValueError):
            MetricConfig(
                metric=exact_match_metric,
                inputs={}
            )

        # Test missing required keys
        with pytest.raises(ValueError):
            MetricConfig(
                metric=exact_match_metric,
                inputs={"input": from_dataset_field(name="input")}  # Missing "output"
            )

    def test_metric_config_serialization(self, exact_match_metric):
        config = MetricConfig(
            metric=exact_match_metric,
            inputs={
                "input": from_dataset_field(name="input"),
                "output": from_llm_response_text(),
            }
        )
        config_dict = config.model_dump()
        assert "metric" in config_dict
        assert "inputs" in config_dict

    def test_optimization_config_initialization(self, exact_match_metric, mock_dataset):
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

    def test_optimization_config_minimize(self, exact_match_metric, mock_dataset):
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

    def test_optimization_config_serialization(self, exact_match_metric, mock_dataset):
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