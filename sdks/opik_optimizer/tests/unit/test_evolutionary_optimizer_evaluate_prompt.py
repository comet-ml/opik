from unittest.mock import Mock, patch
from typing import Any

import opik
from opik_optimizer import EvolutionaryOptimizer
import opik_optimizer
from opik_optimizer.optimizable_agent import OptimizableAgent
from opik_optimizer.algorithms.evolutionary_optimizer.ops import evaluation_ops


def test_evaluate_prompt_uses_evolved_prompt_in_experiment_config() -> None:
    """Test that _evaluate_prompt uses evolved prompt in experiment_config, not original."""

    # Test data setup
    original_prompt_messages = [{"role": "user", "content": "Original prompt content"}]
    evolved_prompt_messages = [
        {"role": "user", "content": "Evolved optimized prompt content"}
    ]

    # Mock dependencies
    mock_dataset = Mock(spec=opik.Dataset)
    mock_dataset.name = "test_dataset"
    mock_dataset.get_items.return_value = [{"id": "item_1"}]

    def mock_metric(dataset_item: dict[str, Any], llm_output: str) -> float:
        return 0.85

    # Create original prompt
    original_prompt = opik_optimizer.ChatPrompt(messages=original_prompt_messages)
    original_prompt.model = "test_model"
    original_prompt.model_kwargs = {"temperature": 0.5}

    # Mock agent class
    mock_agent_class = Mock(spec=OptimizableAgent)
    mock_agent_class.project_name = "test_project"
    mock_agent_class.__name__ = "TestAgent"
    mock_agent_class.return_value = Mock()

    # Initialize optimizer
    optimizer = EvolutionaryOptimizer(model="test_model", verbose=0)
    optimizer.agent_class = mock_agent_class

    # Capture experiment_config
    captured_experiment_config = None

    def mock_task_evaluator_evaluate(**kwargs: Any) -> float:
        nonlocal captured_experiment_config
        captured_experiment_config = kwargs.get("experiment_config", {})
        return 0.85

    # Execute the method under test
    with patch(
        "opik_optimizer.task_evaluator.evaluate",
        side_effect=mock_task_evaluator_evaluate,
    ):
        result = evaluation_ops.evaluate_prompt(
            optimizer=optimizer,
            prompt=original_prompt,
            messages=evolved_prompt_messages,
            dataset=mock_dataset,
            metric=mock_metric,
            n_samples=1,
            experiment_config={},
            optimization_id="opt_123",
            verbose=0,
        )

    # Assertions - focus on the core functionality being tested
    assert result == 0.85
    assert captured_experiment_config is not None

    # Main assertion: experiment_config should contain evolved prompt, not original
    assert (
        captured_experiment_config["agent_config"]["messages"]
        == evolved_prompt_messages
    )
    assert (
        captured_experiment_config["configuration"]["prompt"] == evolved_prompt_messages
    )

    # Verify original prompt is NOT in the experiment_config
    assert (
        captured_experiment_config["agent_config"]["messages"]
        != original_prompt_messages
    )
    assert (
        captured_experiment_config["configuration"]["prompt"]
        != original_prompt_messages
    )
