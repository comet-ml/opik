import logging
from typing import Any, Callable, Dict, List, Optional, Union

import opik
from opik import Dataset
from opik.evaluation.metrics.score_result import ScoreResult

from ..base_optimizer import BaseOptimizer
from ..optimization_config.configs import TaskConfig
from ..optimization_result import OptimizationResult
from ..utils import optimization_context


logger = logging.getLogger(__name__)


class GepaOptimizer(BaseOptimizer):
    """
    Wrapper around the external GEPA package (https://github.com/gepa-ai/gepa).

    This optimizer uses GEPA's DefaultAdapter to optimize a single system prompt for
    a single-turn task. It maps an Opik dataset + metric to GEPA's expected
    train/val set format and returns an OptimizationResult compatible with Opik's SDK.
    """

    def __init__(
        self,
        model: str,
        project_name: Optional[str] = None,
        reflection_model: Optional[str] = None,
        verbose: int = 1,
        **model_kwargs: Any,
    ) -> None:
        super().__init__(model=model, verbose=verbose, **model_kwargs)
        self.project_name = project_name
        # GEPA reflection LLM; defaults to same as task model if not provided
        self.reflection_model = reflection_model or model
        self.num_threads = self.model_kwargs.pop("num_threads", 6)

    def _to_gepa_default_datainst(
        self,
        dataset_items: List[Dict[str, Any]],
        input_key: str,
        output_key: str,
    ) -> List[Dict[str, Any]]:
        """
        Convert Opik dataset items to GEPA DefaultAdapter DataInst entries.
        Each item is a dict with keys: input, answer, additional_context.
        """
        default_items: List[Dict[str, Any]] = []
        for item in dataset_items:
            addl_context: Dict[str, str] = {}
            # Try common places where context may be stored
            # tiny_test uses metadata["context"].
            meta = item.get("metadata") or {}
            if isinstance(meta, dict) and "context" in meta and isinstance(meta["context"], str):
                addl_context["context"] = meta["context"]
            elif "context" in item and isinstance(item["context"], str):
                addl_context["context"] = item["context"]

            default_items.append(
                {
                    "input": str(item.get(input_key, "")),
                    "answer": str(item.get(output_key, "")),
                    "additional_context": addl_context,
                }
            )
        return default_items

    def _call_gepa_optimize(
        self,
        seed_prompt_text: str,
        trainset: List[Dict[str, Any]],
        valset: Optional[List[Dict[str, Any]]],
        max_metric_calls: int,
        reflection_minibatch_size: int = 3,
        candidate_selection_strategy: str = "pareto",
    ):
        try:
            import gepa
        except Exception as e:  # pragma: no cover - only triggered if not installed
            raise ImportError(
                "gepa package is required for GepaOptimizer. Install with `pip install gepa`."
            ) from e

        # Use GEPA default adapter by passing task_lm as model string
        # reflection_lm must be provided when using DefaultAdapter
        result = gepa.optimize(
            seed_candidate={"system_prompt": seed_prompt_text},
            trainset=trainset,
            valset=valset or trainset,
            task_lm=self.model,
            reflection_lm=self.reflection_model,
            candidate_selection_strategy=candidate_selection_strategy,
            reflection_minibatch_size=reflection_minibatch_size,
            max_metric_calls=max_metric_calls,
            # keep GEPA progress output modest; Opik handles reporting
            display_progress_bar=False,
            track_best_outputs=False,
        )
        return result

    def evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        task_config: TaskConfig,
        prompt: Optional[Union[str, OptimizationResult]] = None,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        verbose: int = 1,
        **kwargs: Any,
    ) -> float:
        """
        Compute the score of a prompt on a subset of the dataset by delegating
        to BaseOptimizer.evaluate_prompt where possible. This is a convenience
        evaluator; GEPA's main entry point is optimize_prompt.
        """
        # Fallback to BaseOptimizer evaluation with a simple chat prompt
        from ..optimization_config import chat_prompt

        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        if isinstance(prompt, OptimizationResult):
            # If OptimizationResult provided, use its prompt messages
            prompt_messages = prompt.prompt
        elif isinstance(prompt, str):
            prompt_messages = [{"role": "system", "content": prompt}]
        else:
            prompt_messages = [
                {"role": "system", "content": task_config.instruction_prompt}
            ]

        cp = chat_prompt.ChatPrompt(
            messages=prompt_messages,
            input_key=task_config.input_dataset_fields[0],
            project_name=self.project_name,
            model=self.model,
            model_kwargs=self.model_kwargs,
        )

        score = super().evaluate_prompt(
            prompt=cp,
            dataset=dataset,
            metric=metric,
            n_threads=self.num_threads,
            verbose=verbose,
            dataset_item_ids=dataset_item_ids,
            experiment_config=experiment_config,
            n_samples=n_samples,
        )
        return score

    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: Callable[[Dict[str, Any], str], ScoreResult],
        task_config: TaskConfig,
        max_metric_calls: int = 30,
        reflection_minibatch_size: int = 3,
        candidate_selection_strategy: str = "pareto",
        n_samples: Optional[int] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Run GEPA optimization using DefaultAdapter over the given dataset and metric.

        Args:
            dataset: An Opik dataset name or object.
            metric: Opik metric function (dataset_item, llm_output) -> ScoreResult.
            task_config: TaskConfig mapping input/output fields and optional tools.
            max_metric_calls: GEPA budget for total metric calls across the run.
            reflection_minibatch_size: Batch size GEPA uses for reflective updates.
            candidate_selection_strategy: 'pareto' (default) or 'best'.
            n_samples: If provided, sub-sample the dataset for train/val.
            experiment_config: Extra metadata to record in results.
        """
        if isinstance(dataset, str):
            client = opik.Opik(project_name=self.project_name)
            dataset = client.get_dataset(dataset)

        # Prepare seed prompt and data
        seed_prompt_text = task_config.instruction_prompt
        input_key = task_config.input_dataset_fields[0]
        output_key = task_config.output_dataset_field

        items = dataset.get_items()
        if n_samples is not None and n_samples > 0 and n_samples < len(items):
            # deterministic sub-sample for reproducibility
            items = items[: n_samples]

        trainset = self._to_gepa_default_datainst(items, input_key, output_key)
        valset = None  # default: GEPA uses trainset if valset is None

        # Track optimization run in Opik
        self._opik_client = opik.Opik(project_name=self.project_name)
        with optimization_context(
            client=self._opik_client,
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            metadata={"optimizer": self.__class__.__name__},
        ) as optimization:
            gepa_result = self._call_gepa_optimize(
                seed_prompt_text=seed_prompt_text,
                trainset=trainset,
                valset=valset,
                max_metric_calls=max_metric_calls,
                reflection_minibatch_size=reflection_minibatch_size,
                candidate_selection_strategy=candidate_selection_strategy,
            )

        # Build OptimizationResult
        best_idx = gepa_result.best_idx
        best_candidate = gepa_result.best_candidate
        # DefaultAdapter uses the first value as the system prompt
        best_prompt_text = list(best_candidate.values())[0]
        score = gepa_result.val_aggregate_scores[best_idx]

        # Build a lightweight history from GEPA candidates
        history: List[Dict[str, Any]] = []
        for i, cand in enumerate(gepa_result.candidates):
            cand_prompt = list(cand.values())[0] if cand else ""
            cand_score = gepa_result.val_aggregate_scores[i]
            history.append(
                {
                    "iteration": i + 1,
                    "prompt_candidate": cand_prompt,
                    "scores": [
                        {
                            "metric_name": metric.__name__,
                            "score": cand_score,
                            "opik_evaluation_id": None,
                        }
                    ],
                }
            )

        details: Dict[str, Any] = {
            "model": self.model,
            "temperature": self.model_kwargs.get("temperature"),
            "optimizer": self.__class__.__name__,
            "num_candidates": getattr(gepa_result, "num_candidates", None),
            "total_metric_calls": getattr(gepa_result, "total_metric_calls", None),
            "val_scores": gepa_result.val_aggregate_scores,
            "parents": gepa_result.parents,
        }
        if experiment_config:
            details.update({"experiment": experiment_config})

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=[{"role": "system", "content": best_prompt_text}],
            score=score,
            metric_name=metric.__name__,
            details=details,
            history=history,
            llm_calls=None,  # not tracked for GEPA DefaultAdapter
        )
