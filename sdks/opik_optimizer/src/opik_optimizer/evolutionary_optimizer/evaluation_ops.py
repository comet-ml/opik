from typing import Any, TYPE_CHECKING, cast
from collections.abc import Callable


from .. import task_evaluator
from ..optimization_config import mappers, chat_prompt
import opik
import copy

if TYPE_CHECKING:  # pragma: no cover - typing only
    from ..base_optimizer import BaseOptimizer


class EvaluationOps:
    if TYPE_CHECKING:
        agent_class: type[Any]
        num_threads: int

    def _evaluate_prompt(
        self,
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

        optimizer = cast("BaseOptimizer", self)

        configuration_updates = optimizer._drop_none(
            {
                "n_samples_for_eval": (
                    len(dataset_item_ids) if dataset_item_ids is not None else n_samples
                ),
                "total_dataset_items": total_items,
            }
        )
        evaluation_details = optimizer._drop_none(
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
            agent = self.agent_class(new_prompt)
        except Exception:
            return 0.0

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            messages = new_prompt.get_messages(dataset_item)
            model_output = agent.invoke(messages)
            return {mappers.EVALUATED_LLM_TASK_OUTPUT: model_output}

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=experiment_config.get("project_name"),
            n_samples=n_samples if dataset_item_ids is None else None,
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            verbose=verbose,
        )
        return score
