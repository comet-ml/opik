"""
Evaluation operations for the Meta-Prompt Optimizer.

This module contains functions for evaluating prompts and managing dataset subsets.
"""

from typing import Any
from collections.abc import Callable
import logging
import random

import opik
from opik import opik_context

from ....api_objects import chat_prompt
from .... import task_evaluator, helpers
from ....mcp_utils.mcp_workflow import MCPExecutionConfig

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
    # Calculate subset size for trials
    if not use_full_dataset:
        total_items = len(dataset.get_items())
        if n_samples is not None:
            if n_samples > total_items:
                logger.warning(
                    f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
                )
                subset_size = None
            else:
                subset_size = n_samples
                logger.debug(f"Using specified n_samples: {subset_size} items")
        else:
            # FIXME: This is a hack to ensure we don't evaluate on too many items
            # This should be a configuration parameter and centralised
            #
            # Calculate 20% of total, but no more than 100 items and no more than total items
            DEFAULT_EVALUATION_SUBSET_SIZE = min(
                total_items, max(100, int(total_items * 0.2))
            )
            subset_size = DEFAULT_EVALUATION_SUBSET_SIZE
            logger.debug(
                f"Using automatic subset size calculation: {subset_size} items (20% of {total_items} total items)"
            )
    else:
        subset_size = None  # Use all items for final checks
        logger.debug("Using full dataset for evaluation")

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
    )
    logger.debug(f"Evaluation score: {score:.4f}")
    return score


def get_evaluation_subset(
    dataset: opik.Dataset, min_size: int = 20, max_size: int = 100
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
        return random.sample(all_items, subset_size)

    except Exception as e:
        logger.warning(f"Could not create evaluation subset: {e}")
        return all_items
