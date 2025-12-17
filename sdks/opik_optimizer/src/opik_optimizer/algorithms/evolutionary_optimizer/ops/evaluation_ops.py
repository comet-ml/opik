from typing import Any, TYPE_CHECKING


from .... import task_evaluator, helpers
from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
import opik
from opik import opik_context  # noqa: F401 - used in llm_task closure

if TYPE_CHECKING:  # pragma: no cover - typing only
    from .. import evolutionary_optimizer  # noqa: F401


def evaluate_bundle(
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    bundle_messages: dict[str, list[dict[str, Any]]],
    prompts_metadata: dict[str, dict[str, Any]],
    dataset: opik.Dataset,
    metric: MetricFunction,
    n_samples: int | None = None,
    dataset_item_ids: list[str] | None = None,
    experiment_config: dict | None = None,
    optimization_id: str | None = None,
    verbose: int = 0,
    **kwargs: Any,
) -> float:
    """
    Evaluate a bundle of prompts (multi-prompt individual) against the dataset.

    Args:
        optimizer: The evolutionary optimizer instance.
        bundle_messages: Dict mapping prompt names to their messages.
        prompts_metadata: Dict mapping prompt names to their metadata (tools, function_map, name).
        dataset: The dataset to evaluate on.
        metric: The metric function to score the output.
        n_samples: Optional number of samples to evaluate.
        dataset_item_ids: Optional list of specific dataset item IDs to evaluate.
        experiment_config: Optional experiment configuration.
        optimization_id: Optional optimization ID for tracking.
        verbose: Verbosity level.
        **kwargs: Additional keyword arguments.

    Returns:
        The evaluation score.
    """
    total_items = len(dataset.get_items())

    # Reconstruct ChatPrompt objects from messages and metadata
    prompts_bundle: dict[str, chat_prompt.ChatPrompt] = {}
    for name, messages in bundle_messages.items():
        metadata = prompts_metadata.get(name, {})
        prompts_bundle[name] = chat_prompt.ChatPrompt(
            messages=messages,
            tools=metadata.get("tools"),
            function_map=metadata.get("function_map"),
            name=metadata.get("name", name),
        )

    configuration_updates = helpers.drop_none(
        {
            "n_samples_for_eval": (
                len(dataset_item_ids) if dataset_item_ids is not None else n_samples
            ),
            "total_dataset_items": total_items,
            "bundle_mode": True,
            "num_prompts_in_bundle": len(prompts_bundle),
        }
    )
    evaluation_details = helpers.drop_none(
        {
            "dataset_item_ids": dataset_item_ids,
            "optimization_id": optimization_id,
        }
    )
    additional_metadata = (
        {"evaluation": evaluation_details} if evaluation_details else None
    )

    experiment_config = optimizer._prepare_experiment_config(
        prompt=prompts_bundle,
        dataset=dataset,
        metric=metric,
        agent=optimizer.agent,
        experiment_config=experiment_config,
        configuration_updates=configuration_updates,
        additional_metadata=additional_metadata,
    )

    def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
        # Pass full bundle to the agent
        model_output = optimizer.agent.invoke_agent(
            prompts=prompts_bundle,
            dataset_item=dataset_item,
        )

        # Add tags to trace for optimization tracking
        if (
            hasattr(optimizer, "current_optimization_id")
            and optimizer.current_optimization_id
        ):
            opik_context.update_current_trace(
                tags=[optimizer.current_optimization_id, "Evaluation", "Bundle"]
            )

        return {"llm_output": model_output}

    score = task_evaluator.evaluate(
        dataset=dataset,
        dataset_item_ids=dataset_item_ids,
        metric=metric,
        evaluated_task=llm_task,
        num_threads=optimizer.n_threads,
        project_name=optimizer.project_name,
        n_samples=n_samples if dataset_item_ids is None else None,
        experiment_config=experiment_config,
        optimization_id=optimization_id,
        verbose=verbose,
    )
    return score
