import pytest
from opik_optimizer.optimization_result import OptimizationResult
from unittest.mock import MagicMock
from opik.evaluation.metrics import BaseMetric

class TestOptimizationResult:
    @pytest.fixture
    def mock_metric(self):
        metric = MagicMock(spec=BaseMetric)
        metric.__class__ = BaseMetric
        metric.name = "test_metric"
        metric.score = MagicMock(return_value=0.8)
        return metric

    def test_optimization_result_initialization(self, mock_metric):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.85,
            metric_name="test_metric",
            details={"key": "value"},
            best_prompt="Test prompt",
            best_score=0.85,
            history=[
                {
                    "round_number": 1,
                    "current_prompt": "Initial prompt",
                    "current_score": 0.7,
                    "generated_prompts": [],
                    "best_prompt": "Initial prompt",
                    "best_score": 0.7,
                    "improvement": 0.0
                }
            ],
            metric=mock_metric
        )
        
        assert result.prompt == "Test prompt"
        assert result.score == 0.85
        assert result.metric_name == "test_metric"
        assert result.details == {"key": "value"}
        assert result.best_prompt == "Test prompt"
        assert result.best_score == 0.85
        assert len(result.history) == 1
        assert result.metric == mock_metric

    def test_optimization_result_with_empty_history(self, mock_metric):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.85,
            metric_name="test_metric",
            details={"key": "value"},
            best_prompt="Test prompt",
            best_score=0.85,
            history=[],
            metric=mock_metric
        )
        
        assert result.prompt == "Test prompt"
        assert result.score == 0.85
        assert result.metric_name == "test_metric"
        assert result.details == {"key": "value"}
        assert result.best_prompt == "Test prompt"
        assert result.best_score == 0.85
        assert result.history == []
        assert result.metric == mock_metric

    def test_optimization_result_with_multiple_history_entries(self, mock_metric):
        history = [
            {
                "round_number": 1,
                "current_prompt": "Initial prompt",
                "current_score": 0.7,
                "generated_prompts": [],
                "best_prompt": "Initial prompt",
                "best_score": 0.7,
                "improvement": 0.0
            },
            {
                "round_number": 2,
                "current_prompt": "Improved prompt",
                "current_score": 0.85,
                "generated_prompts": [],
                "best_prompt": "Improved prompt",
                "best_score": 0.85,
                "improvement": 0.15
            }
        ]
        
        result = OptimizationResult(
            prompt="Improved prompt",
            score=0.85,
            metric_name="test_metric",
            details={"key": "value"},
            best_prompt="Improved prompt",
            best_score=0.85,
            history=history,
            metric=mock_metric
        )
        
        assert result.prompt == "Improved prompt"
        assert result.score == 0.85
        assert result.metric_name == "test_metric"
        assert result.details == {"key": "value"}
        assert result.best_prompt == "Improved prompt"
        assert result.best_score == 0.85
        assert len(result.history) == 2
        assert result.history[0]["round_number"] == 1
        assert result.history[1]["round_number"] == 2
        assert result.history[1]["improvement"] == 0.15

    def test_optimization_result_validation(self, mock_metric):
        # Test with invalid best_score
        with pytest.raises(ValueError):
            OptimizationResult(
                prompt="Test prompt",
                score=0.85,
                metric_name="test_metric",
                details={"key": "value"},
                best_prompt="Test prompt",
                best_score=-0.1,  # Invalid score
                history=[],
                metric=mock_metric
            )

        with pytest.raises(ValueError):
            OptimizationResult(
                prompt="Test prompt",
                score=0.85,
                metric_name="test_metric",
                details={"key": "value"},
                best_prompt="Test prompt",
                best_score=1.1,  # Invalid score
                history=[],
                metric=mock_metric
            )

        # Test with valid best_score
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.85,
            metric_name="test_metric",
            details={"key": "value"},
            best_prompt="Test prompt",
            best_score=0.9,  # Valid score
            history=[],
            metric=mock_metric
        )
        assert result.best_score == 0.9

    def test_optimization_result_serialization(self, mock_metric):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.85,
            metric_name="test_metric",
            details={"key": "value"},
            best_prompt="Test prompt",
            best_score=0.85,
            history=[],
            metric=mock_metric
        )
        
        # Test that the result can be converted to dict
        result_dict = result.model_dump()
        assert "prompt" in result_dict
        assert "score" in result_dict
        assert "metric_name" in result_dict
        assert "details" in result_dict
        assert "best_prompt" in result_dict
        assert "best_score" in result_dict
        assert "history" in result_dict
        assert "metric" in result_dict
        assert result_dict["prompt"] == "Test prompt"
        assert result_dict["score"] == 0.85
        assert result_dict["metric_name"] == "test_metric"
        assert result_dict["details"] == {"key": "value"}
        assert result_dict["best_prompt"] == "Test prompt"
        assert result_dict["best_score"] == 0.85
        assert result_dict["history"] == []

    def test_optimization_result_initialization_without_details(self):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric"
        )
        
        assert result.prompt == "Test prompt"
        assert result.score == 0.8
        assert result.metric_name == "test_metric"
        assert result.details == {}

    def test_optimization_result_with_details(self):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric",
            details={"key": "value"}
        )
        
        assert result.details == {"key": "value"}

    def test_optimization_result_with_best_values(self):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric",
            best_prompt="Best prompt",
            best_score=0.9,
            best_metric_name="best_metric",
            best_details={"key": "value"}
        )
        
        assert result.best_prompt == "Best prompt"
        assert result.best_score == 0.9
        assert result.best_metric_name == "best_metric"
        assert result.best_details == {"key": "value"}

    def test_optimization_result_with_history(self):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric",
            history=[{"prompt": "p1", "score": 0.7}, {"prompt": "p2", "score": 0.8}]
        )
        
        assert len(result.history) == 2
        assert result.history[0]["prompt"] == "p1"
        assert result.history[0]["score"] == 0.7

    def test_optimization_result_with_metric(self, mock_metric):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric",
            metric=mock_metric
        )
        
        assert result.metric == mock_metric
        assert result.metric_name == "test_metric"

    def test_optimization_result_validation_without_best_values(self):
        # Test valid score
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric"
        )
        assert result.score == 0.8

        # Test invalid score (negative)
        with pytest.raises(ValueError):
            OptimizationResult(
                prompt="Test prompt",
                score=-0.1,
                metric_name="test_metric"
            )

        # Test invalid score (greater than 1.0)
        with pytest.raises(ValueError):
            OptimizationResult(
                prompt="Test prompt",
                score=1.1,
                metric_name="test_metric"
            )

    def test_optimization_result_str(self):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric"
        )
        
        str_result = str(result)
        assert "Test prompt" in str_result
        assert "0.8" in str_result
        assert "test_metric" in str_result

    def test_optimization_result_str_with_best_values(self):
        result = OptimizationResult(
            prompt="Test prompt",
            score=0.8,
            metric_name="test_metric",
            best_prompt="Best prompt",
            best_score=0.9,
            best_metric_name="best_metric"
        )
        
        str_result = str(result)
        assert "Best prompt" in str_result
        assert "0.9" in str_result
        assert "best_metric" in str_result 