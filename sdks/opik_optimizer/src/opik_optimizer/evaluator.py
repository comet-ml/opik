"""
Evaluator - Pure evaluation service for optimization.

This module provides a clean interface for evaluating prompts against datasets.
The Evaluator is injected into the OptimizationContext and used by BaseOptimizer.evaluate().
"""

from __future__ import annotations

import logging
import random
from typing import Any, TYPE_CHECKING, cast

from opik import opik_context
from opik.evaluation.evaluation_result import EvaluationResult

from . import task_evaluator
from .agents import LiteLLMAgent, OptimizableAgent
from .api_objects import chat_prompt
from .api_objects.types import MetricFunction
from .utils.candidate_selection import select_candidate

if TYPE_CHECKING:
    from opik import Dataset

logger = logging.getLogger(__name__)


class Evaluator:
    """
    Pure evaluation service for prompt optimization.

    This class encapsulates all evaluation logic, providing a clean interface
    for scoring prompts against datasets. It handles:
    - Agent invocation (with pass@k selection support)
    - Metric computation
    - Trace tagging for optimization tracking

    The Evaluator is stateless and can be reused across evaluations.
    """

    def __init__(
        self,
        dataset: "Dataset",
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        n_threads: int = 12,
        n_samples: int | None = None,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        seed: int | None = None,
    ) -> None:
        """
        Initialize the Evaluator.

        Args:
            dataset: The dataset to evaluate against.
            metric: The metric function for scoring.
            agent: The agent to use for prompt execution.
                   If None, a default LiteLLMAgent is created.
            n_threads: Number of threads for parallel evaluation.
            n_samples: Optional limit on dataset samples to evaluate.
            project_name: Project name for Opik tracking.
            optimization_id: Optional optimization ID for trace tagging.
            seed: Random seed for reproducibility.
        """
        self.dataset = dataset
        self.metric = metric
        self.agent = agent or LiteLLMAgent(project_name=project_name)
        self.n_threads = n_threads
        self.n_samples = n_samples
        self.project_name = project_name
        self.optimization_id = optimization_id
        self.seed = seed

        # Reference to the optimizer for cost tracking (set externally)
        self._optimizer_ref: Any = None

    def evaluate(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        experiment_config: dict[str, Any] | None = None,
        verbose: int = 0,
        return_evaluation_result: bool = False,
    ) -> float | EvaluationResult:
        """
        Evaluate a prompt against the dataset and return its score.

        This is the core evaluation method that:
        1. Creates an LLM task that invokes the agent with the prompt
        2. Handles pass@k selection if configured
        3. Runs the evaluation through task_evaluator
        4. Returns the aggregate score

        Args:
            prompt: The prompt(s) to evaluate. Can be a single ChatPrompt
                   or a dict of named prompts for multi-prompt optimization.
            experiment_config: Optional experiment configuration dict.
            verbose: Verbosity level (0 = silent, 1 = normal).
            return_evaluation_result: If True, return full EvaluationResult.

        Returns:
            float: The aggregate score (average across dataset items).
            EvaluationResult: If return_evaluation_result=True.
        """
        if self.seed is not None:
            random.seed(self.seed)

        agent = self.agent

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, Any]:
            # Attach optimizer reference for cost tracking if available
            if self._optimizer_ref is not None:
                self._attach_agent_owner(agent)

            # Wrap single prompt in dict for invoke_agent
            prompts_dict: dict[str, chat_prompt.ChatPrompt]
            if isinstance(prompt, dict):
                prompts_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
            else:
                prompts_dict = {prompt.name: prompt}

            # Get model kwargs for pass@k configuration
            prompt_config = (
                list(prompts_dict.values())[0].model_kwargs
                if len(prompts_dict) == 1
                else {}
            )
            requested_n = int(prompt_config.get("n", 1) or 1)
            selection_policy = (
                prompt_config.get("selection_policy", "best_by_metric")
                if isinstance(prompt_config, dict)
                else "best_by_metric"
            )
            selection_policy = str(selection_policy or "best_by_metric").lower()

            # Handle pass@k evaluation
            if requested_n > 1 and hasattr(agent, "invoke_agent_candidates"):
                candidates = agent.invoke_agent_candidates(
                    prompts=prompts_dict, dataset_item=dataset_item
                )
                if not candidates:
                    raw_model_output = agent.invoke_agent(
                        prompts=prompts_dict, dataset_item=dataset_item
                    )
                    cleaned_model_output = raw_model_output.strip()
                else:
                    selection_result = select_candidate(
                        candidates=candidates,
                        policy=selection_policy,
                        metric=self.metric,
                        dataset_item=dataset_item,
                        candidate_logprobs=getattr(
                            agent, "_last_candidate_logprobs", None
                        ),
                        rng=random,
                    )
                    cleaned_model_output = selection_result.output.strip()
                    if logger.isEnabledFor(logging.DEBUG):
                        logger.debug(
                            "Pass@k selection: n=%s policy=%s candidates=%d chosen=%s scores=%s logprobs=%s",
                            requested_n,
                            selection_result.policy,
                            len(candidates),
                            selection_result.chosen_index,
                            selection_result.candidate_scores,
                            selection_result.candidate_logprobs,
                        )

                    try:
                        opik_context.update_current_trace(
                            metadata={
                                "opik_optimizer": {
                                    "selection_policy": selection_result.policy,
                                    "n_requested": requested_n,
                                    "candidates_scored": len(candidates),
                                    "candidate_scores": selection_result.candidate_scores,
                                    "candidate_logprobs": selection_result.candidate_logprobs,
                                    "chosen_index": selection_result.chosen_index,
                                }
                            }
                        )
                    except Exception:
                        pass
            else:
                raw_model_output = agent.invoke_agent(
                    prompts=prompts_dict, dataset_item=dataset_item
                )
                cleaned_model_output = raw_model_output.strip()

            # Add tags to trace for optimization tracking
            if self.optimization_id:
                opik_context.update_current_trace(
                    tags=[self.optimization_id, "Evaluation"]
                )

            return {"llm_output": cleaned_model_output}

        # Run evaluation through task_evaluator
        result = task_evaluator.evaluate(
            dataset=self.dataset,
            evaluated_task=llm_task,
            metric=self.metric,
            num_threads=self.n_threads,
            dataset_item_ids=None,
            project_name=self.project_name,
            n_samples=self.n_samples,
            experiment_config=experiment_config,
            optimization_id=self.optimization_id,
            verbose=verbose,
            return_evaluation_result=return_evaluation_result,  # type: ignore[call-overload]
        )
        return result

    def _attach_agent_owner(self, agent: OptimizableAgent) -> None:
        """Attach optimizer reference to agent for cost tracking."""
        if self._optimizer_ref is not None and hasattr(agent, "_set_owner"):
            agent._set_owner(self._optimizer_ref)

    def set_optimizer_ref(self, optimizer: Any) -> None:
        """
        Set a reference to the optimizer for cost tracking.

        Args:
            optimizer: The optimizer instance to track costs for.
        """
        self._optimizer_ref = optimizer
