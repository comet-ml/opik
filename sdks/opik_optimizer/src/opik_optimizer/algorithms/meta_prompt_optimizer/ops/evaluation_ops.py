"""
Evaluation operations for the Meta-Prompt Optimizer.

This module contains functions for evaluating prompts and managing dataset subsets.
"""

from typing import Any
from collections.abc import Callable
import logging

import opik
from opik import opik_context

from ....api_objects import chat_prompt
from .... import task_evaluator, helpers
from ....mcp_utils.mcp_workflow import MCPExecutionConfig
from ....utils import rng as rng_utils

logger = logging.getLogger(__name__)


def evaluate_prompt(
    optimizer: Any,
    prompt: chat_prompt.ChatPrompt,
    dataset: opik.Dataset,
    metric: Callable,
    n_samples: int | None = None,
    dataset_item_ids: list[str] | None = None,
    experiment_config: dict | None = None,
    use_full_dataset: bool = True,
    optimization_id: str | None = None,
    mcp_config: MCPExecutionConfig | None = None,
    **kwargs: Any,
) -> float:
    """
    Evaluate a prompt on a dataset using a metric.

    Args:
        optimizer: Reference to the optimizer instance
        prompt: Prompt to evaluate
        dataset: Opik Dataset to evaluate the prompt on
        metric: Metric function
        use_full_dataset: Whether to use the full dataset or a subset
        experiment_config: Optional configuration for the experiment
        n_samples: Optional number of items to test in the dataset
        optimization_id: Optional ID of the optimization
        mcp_config: Optional MCP configuration
        **kwargs: Additional arguments

    Returns:
        float: The evaluation score
    """
    phase = "final" if use_full_dataset else "trial"
    default_cap = None
    total_items = None
    try:
        total_items = len(dataset.get_items())
    except Exception:
        total_items = None

    if not use_full_dataset and n_samples is None and total_items is not None:
        default_cap = min(total_items, max(100, int(total_items * 0.2)))

    sampling_plan = optimizer._prepare_sampling_plan(
        dataset=dataset,
        n_samples=n_samples if not use_full_dataset else "full",
        dataset_item_ids=dataset_item_ids,
        phase=phase,
        default_cap=default_cap,
    )
    subset_size = (
        None if sampling_plan.dataset_item_ids is not None else sampling_plan.nb_samples
    )
    dataset_item_ids = sampling_plan.dataset_item_ids
    if subset_size is None and sampling_plan.nb_samples is None:
        logger.debug("Using full dataset for evaluation")
    else:
        logger.debug(
            "Using %s items for evaluation (mode=%s)",
            subset_size if subset_size is not None else len(dataset_item_ids or []),
            sampling_plan.mode,
        )

    configuration_updates = helpers.drop_none(
        {
            "n_samples": subset_size,
            "use_full_dataset": use_full_dataset,
        }
    )
    meta_metadata = helpers.drop_none(
        {
            "optimization_id": optimization_id,
            "stage": "trial_evaluation" if not use_full_dataset else "final_eval",
        }
    )
    experiment_config = optimizer._prepare_experiment_config(
        prompt=prompt,
        dataset=dataset,
        metric=metric,
        experiment_config=experiment_config,
        configuration_updates=configuration_updates,
        additional_metadata={"meta_prompt": meta_metadata} if meta_metadata else None,
    )

    def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
        new_prompt = prompt.copy()
        messages = new_prompt.get_messages(dataset_item)
        new_prompt.set_messages(messages)
        agent = optimizer._instantiate_agent(new_prompt)

        if mcp_config is not None:
            coordinator = mcp_config.coordinator
            coordinator.reset()
            try:
                logger.debug(
                    "Calling MCP-enabled LLM with tool access; prompt length=%s",
                    sum(len(msg["content"]) for msg in messages),
                )
                raw_model_output = agent.llm_invoke(
                    messages=messages,
                    seed=optimizer.seed,
                    allow_tool_use=True,
                )
            except Exception as exc:
                logger.error("Error during MCP first pass: %s", exc)
                raise

            second_pass_messages = coordinator.build_second_pass_messages(
                base_messages=messages,
                dataset_item=dataset_item,
            )

            if second_pass_messages is None and mcp_config.fallback_invoker:
                fallback_args = mcp_config.fallback_arguments(dataset_item)
                if fallback_args:
                    logger.debug(
                        "MCP fallback triggered for tool %s with args=%s",
                        mcp_config.tool_name,
                        fallback_args,
                    )
                    summary_override = mcp_config.fallback_invoker(fallback_args)
                    second_pass_messages = coordinator.build_second_pass_messages(
                        base_messages=messages,
                        dataset_item=dataset_item,
                        summary_override=summary_override,
                    )

            if second_pass_messages is not None:
                logger.debug(
                    "Executing MCP second pass with %d messages",
                    len(second_pass_messages),
                )
                final_response = agent.llm_invoke(
                    messages=second_pass_messages,
                    seed=optimizer.seed,
                    allow_tool_use=mcp_config.allow_tool_use_on_second_pass,
                )
            else:
                final_response = raw_model_output

            cleaned_model_output = final_response.strip()
        else:
            try:
                logger.debug(
                    f"Calling LLM with prompt length: {sum(len(msg['content']) for msg in messages)}"
                )
                raw_model_output = agent.invoke(messages)
                logger.debug(f"LLM raw response length: {len(raw_model_output)}")
                logger.debug(f"LLM raw output: {raw_model_output}")
            except Exception as e:
                logger.error(f"Error calling model with prompt: {e}")
                logger.error(f"Failed prompt: {messages}")
                logger.error(
                    f"Prompt length: {sum(len(msg['content']) for msg in messages)}"
                )
                raise

            cleaned_model_output = raw_model_output.strip()

        # Add tags to trace for optimization tracking
        if optimizer.current_optimization_id:
            opik_context.update_current_trace(
                tags=[
                    optimizer.current_optimization_id,
                    "Evaluation",
                ]
            )

        result = {
            "llm_output": cleaned_model_output,
        }
        return result

    # Use dataset's get_items with limit for sampling
    logger.debug(
        f"Starting evaluation with {subset_size if subset_size else 'all'} samples for metric: {getattr(metric, '__name__', str(metric))}"
    )
    score = task_evaluator.evaluate(
        dataset=dataset,
        metric=metric,
        evaluated_task=llm_task,
        dataset_item_ids=dataset_item_ids,
        num_threads=optimizer.n_threads,
        project_name=optimizer.project_name,
        n_samples=subset_size,  # Use subset_size for trials, None for full dataset
        experiment_config=experiment_config,
        optimization_id=optimization_id,
        verbose=optimizer.verbose,
        # FIXME(opik-sdk): when opik.evaluate_on_dict_items is available end-to-end,
        # add a switch here for trial/minibatch scoring to avoid experiment setup.
    )
    logger.debug(f"Evaluation score: {score:.4f}")
    return score


def get_evaluation_subset(
    dataset: opik.Dataset,
    min_size: int = 20,
    max_size: int = 100,
    seed: int | None = None,
) -> list[dict[str, Any]]:
    """
    Get a random subset of the dataset for evaluation.

    Args:
        dataset: The dataset to sample from
        min_size: Minimum subset size
        max_size: Maximum subset size

    Returns:
        List of dataset items to evaluate against
    """
    try:
        # Get all items from the dataset
        all_items = dataset.get_items()
        if not all_items:
            return all_items

        # Calculate subset size
        total_size = len(all_items)
        subset_size = min(max(min_size, int(total_size * 0.2)), max_size)

        # Get random subset of items
        rng = rng_utils.make_rng(seed, "evaluation_subset")
        return rng.sample(all_items, subset_size)

    except Exception as e:
        logger.warning(f"Could not create evaluation subset: {e}")
        return all_items
