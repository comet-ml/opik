from typing import Any, TYPE_CHECKING
from collections.abc import Callable


from .... import task_evaluator, helpers
from ....api_objects import chat_prompt
from ....mcp_utils.mcp_workflow import MCPExecutionConfig
import opik
from opik import opik_context
import copy

if TYPE_CHECKING:  # pragma: no cover - typing only
    from .. import evolutionary_optimizer


def evaluate_prompt(
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    prompt: chat_prompt.ChatPrompt,
    messages: list[dict[str, str]],
    dataset: opik.Dataset,
    metric: Callable,
    n_samples: int | None = None,
    dataset_item_ids: list[str] | None = None,
    experiment_config: dict | None = None,
    optimization_id: str | None = None,
    verbose: int = 0,
    **kwargs: Any,
) -> float:
    """Evaluate a single prompt (individual) against the dataset and return the score."""
    total_items = len(dataset.get_items())

    new_prompt = prompt.copy()
    new_prompt.set_messages(messages)
    tools = getattr(messages, "tools", None)
    if tools is not None:
        new_prompt.tools = copy.deepcopy(tools)

    configuration_updates = helpers.drop_none(
        {
            "n_samples_for_eval": (
                len(dataset_item_ids) if dataset_item_ids is not None else n_samples
            ),
            "total_dataset_items": total_items,
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
        prompt=new_prompt,
        dataset=dataset,
        metric=metric,
        experiment_config=experiment_config,
        configuration_updates=configuration_updates,
        additional_metadata=additional_metadata,
    )
    try:
        agent = optimizer.agent_class(new_prompt)
    except Exception:
        return 0.0

    mcp_execution_config: MCPExecutionConfig | None = kwargs.get("mcp_config")

    def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
        messages = new_prompt.get_messages(dataset_item)

        if mcp_execution_config is None:
            model_output = agent.invoke(messages)

            # Add tags to trace for optimization tracking
            if (
                hasattr(optimizer, "current_optimization_id")
                and optimizer.current_optimization_id
            ):
                opik_context.update_current_trace(
                    tags=[optimizer.current_optimization_id, "Evaluation"]
                )

            return {"llm_output": model_output}

        coordinator = mcp_execution_config.coordinator
        coordinator.reset()

        raw_model_output = agent.llm_invoke(
            messages=messages,
            seed=getattr(optimizer, "seed", None),
            allow_tool_use=True,
        )

        second_pass_messages = coordinator.build_second_pass_messages(
            base_messages=messages,
            dataset_item=dataset_item,
        )

        if (
            second_pass_messages is None
            and mcp_execution_config.fallback_invoker is not None
        ):
            fallback_args = mcp_execution_config.fallback_arguments(dataset_item)
            if fallback_args:
                summary_override = mcp_execution_config.fallback_invoker(fallback_args)
                second_pass_messages = coordinator.build_second_pass_messages(
                    base_messages=messages,
                    dataset_item=dataset_item,
                    summary_override=summary_override,
                )

        if second_pass_messages is not None:
            final_response = agent.llm_invoke(
                messages=second_pass_messages,
                seed=getattr(optimizer, "seed", None),
                allow_tool_use=mcp_execution_config.allow_tool_use_on_second_pass,
            )
        else:
            final_response = raw_model_output

        # Add tags to trace for optimization tracking
        if (
            hasattr(optimizer, "current_optimization_id")
            and optimizer.current_optimization_id
        ):
            opik_context.update_current_trace(
                tags=[optimizer.current_optimization_id, "Evaluation"]
            )

        return {"llm_output": final_response.strip()}

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
