import pytest
from opik_optimizer.base_optimizer import BaseOptimizer, OptimizationRound
from opik.evaluation import metrics
from unittest.mock import MagicMock
import opik

class TestBaseOptimizer:
    @pytest.fixture
    def base_optimizer(self):
        return BaseOptimizer(model="gpt-3.5-turbo", project_name="test_project")

    @pytest.fixture
    def mock_dataset(self):
        return MagicMock(spec=opik.Dataset)

    @pytest.fixture
    def mock_metric(self):
        return MagicMock(spec=metrics.BaseMetric)

    def test_initialization(self, base_optimizer):
        assert base_optimizer.model == "gpt-3.5-turbo"
        assert base_optimizer.project_name == "test_project"
        assert base_optimizer._history == []
        assert base_optimizer.experiment_config is None

    def test_optimize_prompt(self, base_optimizer, mock_dataset, mock_metric):
        prompt = "Test prompt"
        base_optimizer.optimize_prompt(
            dataset=mock_dataset,
            metric=mock_metric,
            prompt=prompt,
            input_key="input",
            output_key="output"
        )
        
        assert base_optimizer.dataset == mock_dataset
        assert base_optimizer.metric == mock_metric
        assert base_optimizer.prompt == prompt
        assert base_optimizer.input_key == "input"
        assert base_optimizer.output_key == "output"

    def test_evaluate_prompt(self, base_optimizer, mock_dataset, mock_metric):
        prompt = "Test prompt"
        score = base_optimizer.evaluate_prompt(
            dataset=mock_dataset,
            metric=mock_metric,
            prompt=prompt,
            input_key="input",
            output_key="output",
            num_test=5
        )
        
        assert isinstance(score, float)
        assert score == 0.0  # Base implementation returns 0

    def test_history_management(self, base_optimizer):
        # Test empty history
        assert base_optimizer.get_history() == []

        # Test adding to history
        round_data = {
            "round_number": 1,
            "current_prompt": "test prompt",
            "current_score": 0.8,
            "generated_prompts": [],
            "best_prompt": "test prompt",
            "best_score": 0.8,
            "improvement": 0.0
        }
        base_optimizer._add_to_history(round_data)
        history = base_optimizer.get_history()
        assert len(history) == 1
        assert history[0] == round_data

    def test_optimization_round_model(self):
        round_data = {
            "round_number": 1,
            "current_prompt": "test prompt",
            "current_score": 0.8,
            "generated_prompts": [],
            "best_prompt": "test prompt",
            "best_score": 0.8,
            "improvement": 0.0
        }
        round = OptimizationRound(**round_data)
        assert round.round_number == 1
        assert round.current_prompt == "test prompt"
        assert round.current_score == 0.8
        assert round.generated_prompts == []
        assert round.best_prompt == "test prompt"
        assert round.best_score == 0.8
        assert round.improvement == 0.0 