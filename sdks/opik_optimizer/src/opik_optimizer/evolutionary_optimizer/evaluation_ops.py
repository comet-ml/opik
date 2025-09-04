from typing import Any, Callable, Dict, List, Optional


from .. import task_evaluator
from ..optimization_config import mappers, chat_prompt
import opik


class EvaluationOps:
    def _evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        messages: List[Dict[str, str]],
        dataset: opik.Dataset,
        metric: Callable,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        verbose: int = 0,
        **kwargs: Any,
    ) -> float:
        """Evaluate a single prompt (individual) against the dataset and return the score."""
        total_items = len(dataset.get_items())

        new_prompt = prompt.copy()
        new_prompt.set_messages(messages)

        experiment_config = experiment_config or {}
        experiment_config["project_name"] = self.agent_class.project_name
        experiment_config = {
            **experiment_config,
            "optimizer": self.__class__.__name__,
            "agent_class": self.agent_class.__name__,
            "agent_config": new_prompt.to_dict(),
            "metric": metric.__name__,
            "dataset": dataset.name,
            "configuration": {
                "prompt": new_prompt.get_messages(),
                "n_samples_for_eval": (
                    len(dataset_item_ids) if dataset_item_ids is not None else n_samples
                ),
                "total_dataset_items": total_items,
            },
        }
        try:
            agent = self.agent_class(new_prompt)
        except Exception:
            return 0.0

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            messages = new_prompt.get_messages(dataset_item)
            model_output = agent.invoke(messages)
            return {mappers.EVALUATED_LLM_TASK_OUTPUT: model_output}

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=experiment_config["project_name"],
            n_samples=n_samples if dataset_item_ids is None else None,
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            verbose=verbose,
        )
        return score
