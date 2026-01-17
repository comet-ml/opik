# mypy: disable-error-code=no-untyped-def

"""
Shared test utilities and helpers for opik_optimizer tests.

This module provides reusable test fixtures, mock builders, and helper functions
to reduce duplication across test files.
"""

from typing import Any, Callable
from unittest.mock import MagicMock

from opik import Dataset
from opik_optimizer import ChatPrompt
from opik_optimizer.base_optimizer import OptimizationContext


# ============================================================
# Mock Builders
# ============================================================


def make_mock_dataset(
    items: list[dict[str, Any]] | None = None,
    *,
    name: str = "test-dataset",
    dataset_id: str = "dataset-123",
) -> MagicMock:
    """
    Create a mock Dataset object for testing.

    Args:
        items: List of dataset items. Defaults to a single item.
        name: Dataset name
        dataset_id: Dataset ID

    Returns:
        Mock Dataset object
    """
    if items is None:
        items = [{"id": "1", "question": "Q1", "answer": "A1"}]

    mock = MagicMock(spec=Dataset)
    mock.name = name
    mock.id = dataset_id

    def get_items_impl(nb_samples: int | None = None):
        if nb_samples is not None:
            return items[:nb_samples]
        return items

    mock.get_items = MagicMock(side_effect=get_items_impl)
    return mock


def make_candidate_agent(
    candidates: list[str] | None = None,
    single_output: str = "bad",
    logprobs: list[float] | None = None,
) -> MagicMock:
    """
    Create a mock agent that returns multiple candidates.

    Args:
        candidates: List of candidate outputs. Defaults to ["bad", "good"]
        single_output: Output for invoke_agent (non-candidate path)
        logprobs: Optional logprobs for max_logprob selection

    Returns:
        Mock agent with invoke_agent_candidates and invoke_agent methods
    """
    if candidates is None:
        candidates = ["bad", "good"]

    class CandidateAgent(MagicMock):
        def invoke_agent_candidates(
            self, prompts, dataset_item, allow_tool_use=False, seed=None
        ):
            _ = allow_tool_use, seed
            if logprobs is not None:
                self._last_candidate_logprobs = logprobs
            return candidates

        def invoke_agent(self, prompts, dataset_item, allow_tool_use=False, seed=None):
            _ = allow_tool_use, seed
            return single_output

    return CandidateAgent()


def make_fake_evaluator(
    expected_output: str | None = None,
    return_score: float = 1.0,
    *,
    assert_output: Callable[[dict[str, Any]], None] | None = None,
) -> Callable[..., float]:
    """
    Create a fake evaluate function for testing.

    Args:
        expected_output: Expected llm_output value to assert
        return_score: Score to return
        assert_output: Optional custom assertion function for the output

    Returns:
        Fake evaluate function
    """
    def fake_evaluate(
        dataset,
        evaluated_task,
        metric,
        num_threads,
        optimization_id=None,
        dataset_item_ids=None,
        project_name=None,
        n_samples=None,
        experiment_config=None,
        verbose=1,
        return_evaluation_result=False,
        **kwargs,
    ):
        _ = (
            dataset,
            metric,
            num_threads,
            optimization_id,
            dataset_item_ids,
            project_name,
            n_samples,
            experiment_config,
            verbose,
            return_evaluation_result,
            kwargs,
        )
        output = evaluated_task({"id": "1", "input": "x"})
        if assert_output:
            assert_output(output)
        elif expected_output is not None:
            assert output["llm_output"] == expected_output
        return return_score

    return fake_evaluate


def make_optimization_context(
    prompt: ChatPrompt | dict[str, ChatPrompt],
    *,
    dataset: MagicMock | None = None,
    evaluation_dataset: MagicMock | None = None,
    validation_dataset: MagicMock | None = None,
    metric: Callable | None = None,
    agent: MagicMock | None = None,
    optimization: MagicMock | None = None,
    optimization_id: str | None = None,
    experiment_config: dict[str, Any] | None = None,
    n_samples: int | None = None,
    max_trials: int = 10,
    project_name: str = "Test",
    baseline_score: float | None = None,
    current_best_score: float | None = None,
    **extra_params,
) -> OptimizationContext:
    """
    Create an OptimizationContext for testing.

    Args:
        prompt: Single ChatPrompt or dict of prompts
        dataset: Training dataset (defaults to mock)
        evaluation_dataset: Evaluation dataset (defaults to dataset)
        validation_dataset: Validation dataset
        metric: Metric function (defaults to mock)
        agent: Agent instance (defaults to mock)
        optimization: Optimization object
        optimization_id: Optimization ID
        experiment_config: Experiment configuration
        n_samples: Number of samples
        max_trials: Maximum trials
        project_name: Project name
        baseline_score: Baseline score
        current_best_score: Current best score
        **extra_params: Additional context parameters

    Returns:
        OptimizationContext instance
    """
    if dataset is None:
        dataset = make_mock_dataset()
    if evaluation_dataset is None:
        evaluation_dataset = dataset
    if metric is None:
        metric = MagicMock(__name__="test_metric")
    if agent is None:
        agent = MagicMock()

    if isinstance(prompt, ChatPrompt):
        prompts = {prompt.name: prompt}
        initial_prompts = {prompt.name: prompt}
        is_single = True
    else:
        prompts = prompt
        initial_prompts = prompt
        is_single = False

    return OptimizationContext(
        prompts=prompts,
        initial_prompts=initial_prompts,
        is_single_prompt_optimization=is_single,
        dataset=dataset,
        evaluation_dataset=evaluation_dataset,
        validation_dataset=validation_dataset,
        metric=metric,
        agent=agent,
        optimization=optimization,
        optimization_id=optimization_id,
        experiment_config=experiment_config,
        n_samples=n_samples,
        max_trials=max_trials,
        project_name=project_name,
        baseline_score=baseline_score,
        current_best_score=current_best_score,
        **extra_params,
    )


def make_mock_response(
    content: str,
    *,
    finish_reason: str = "stop",
    model: str = "gpt-4",
) -> MagicMock:
    """
    Create a mock LLM response object.

    Args:
        content: Response content
        finish_reason: Finish reason (default: "stop")
        model: Model name

    Returns:
        Mock response object
    """
    mock_response = MagicMock()
    mock_response.choices = [MagicMock()]
    mock_response.choices[0].message.content = content
    mock_response.choices[0].finish_reason = finish_reason
    mock_response.model = model
    return mock_response


def make_optimization_result(
    prompt: ChatPrompt | dict[str, ChatPrompt],
    score: float,
    *,
    optimizer: str = "Optimizer",
    metric_name: str = "accuracy",
    initial_prompt: ChatPrompt | dict[str, ChatPrompt] | None = None,
    initial_score: float | None = None,
    **kwargs,
) -> dict[str, Any]:
    """
    Create parameters dict for OptimizationResult construction.

    Args:
        prompt: Optimized prompt(s)
        score: Final score
        optimizer: Optimizer name
        metric_name: Metric name
        initial_prompt: Initial prompt(s)
        initial_score: Initial score
        **kwargs: Additional OptimizationResult fields

    Returns:
        Dict of parameters for OptimizationResult
    """
    result = {
        "optimizer": optimizer,
        "prompt": prompt,
        "score": score,
        "metric_name": metric_name,
        **kwargs,
    }
    if initial_prompt is not None:
        result["initial_prompt"] = initial_prompt
    if initial_score is not None:
        result["initial_score"] = initial_score
    return result
