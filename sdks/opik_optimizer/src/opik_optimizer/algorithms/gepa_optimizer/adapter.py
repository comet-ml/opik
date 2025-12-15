from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Callable, Iterable

import logging

from gepa.core.adapter import EvaluationBatch, GEPAAdapter
from opik import Dataset

from ... import helpers, task_evaluator
from ...api_objects import chat_prompt
from ...utils import create_litellm_agent_class


logger = logging.getLogger(__name__)


@dataclass
class OpikDataInst:
    """Data instance handed to GEPA.

    We keep the original Opik dataset item so metrics and prompt formatting can use it
    directly without duplicated bookkeeping.
    """

    input_text: str
    answer: str
    additional_context: dict[str, str]
    opik_item: dict[str, Any]


def _extract_system_text(candidate: dict[str, str], fallback: str) -> str:
    for key in ("system_prompt", "system", "prompt"):
        value = candidate.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return fallback


def _apply_system_text(
    prompt_obj: chat_prompt.ChatPrompt, system_text: str
) -> chat_prompt.ChatPrompt:
    updated = prompt_obj.copy()
    if updated.messages is not None:
        messages = updated.get_messages()
        if messages and messages[0].get("role") == "system":
            messages[0]["content"] = system_text
        else:
            messages.insert(0, {"role": "system", "content": system_text})
        updated.set_messages(messages)
    else:
        updated.system = system_text
    return updated


class OpikGEPAAdapter(GEPAAdapter[OpikDataInst, dict[str, Any], dict[str, Any]]):
    """Minimal GEPA adapter that routes evaluation through Opik's metric."""

    def __init__(
        self,
        base_prompt: chat_prompt.ChatPrompt,
        optimizer: Any,
        metric: Callable[[dict[str, Any], str], Any],
        system_fallback: str,
        dataset: Dataset,
        experiment_config: dict[str, Any] | None,
        validation_dataset: Dataset | None = None,
    ) -> None:
        self._base_prompt = base_prompt
        self._optimizer = optimizer
        self._metric = metric
        self._system_fallback = system_fallback
        self._dataset = dataset
        self._validation_dataset = validation_dataset
        self._experiment_config = experiment_config
        self._metric_name = getattr(metric, "__name__", str(metric))

        # Pre-compute item ID sets for fast lookup during evaluate()
        self._train_item_ids: set[str] = {
            str(item.get("id")) for item in dataset.get_items() if item.get("id")
        }
        self._val_item_ids: set[str] = set()
        if validation_dataset is not None:
            self._val_item_ids = {
                str(item.get("id"))
                for item in validation_dataset.get_items()
                if item.get("id")
            }

    def _resolve_dataset_for_batch(self, dataset_item_ids: list[str]) -> Dataset:
        """
        Determine which dataset to use based on the batch item IDs.

        GEPA passes items from either trainset or valset to evaluate(), never mixed.
        We check validation first (if provided), then fall back to train.
        """
        if not dataset_item_ids:
            return self._dataset

        # Check validation dataset first (if provided)
        if self._validation_dataset is not None and self._val_item_ids:
            if any(id_ in self._val_item_ids for id_ in dataset_item_ids):
                return self._validation_dataset

        return self._dataset

    def evaluate(
        self,
        batch: list[OpikDataInst],
        candidate: dict[str, str],
        capture_traces: bool = False,
    ) -> EvaluationBatch[dict[str, Any], dict[str, Any]]:
        """
        Evaluate a GEPA candidate against a batch of Opik items.

        We first try to delegate evaluation to Opik's evaluator so every trace/span
        lands under the optimizer's project/trace. If that isn't possible (missing
        dataset IDs or evaluator failure), we fallback to the original inline
        evaluation logic so GEPA continues to function.
        """

        # TODO(opik-gepa): replace this adapter patch with a native GEPA <-> Opik bridge
        # once GEPA exposes a public opik adapter for tracing + evaluation.
        system_text = _extract_system_text(candidate, self._system_fallback)
        prompt_variant = _apply_system_text(self._base_prompt, system_text)

        dataset_item_ids: list[str] = []
        missing_ids = False
        for inst in batch:
            dataset_item_id = inst.opik_item.get("id")
            if dataset_item_id is None:
                missing_ids = True
                break
            dataset_item_ids.append(str(dataset_item_id))

        # Resolve which dataset to use based on item IDs (train vs validation)
        target_dataset = self._resolve_dataset_for_batch(dataset_item_ids)

        # Attach GEPA-specific metadata without disturbing the caller's experiment config.
        configuration_updates = helpers.drop_none(
            {
                "gepa": helpers.drop_none(
                    {
                        "phase": "candidate",
                        "source": candidate.get("source"),
                        "candidate_id": candidate.get("id"),
                    }
                )
            }
        )
        experiment_config = self._optimizer._prepare_experiment_config(
            prompt=prompt_variant,
            dataset=target_dataset,
            metric=self._metric,
            experiment_config=self._experiment_config,
            configuration_updates=configuration_updates,
        )
        project_name = experiment_config.get("project_name") or getattr(
            self._optimizer, "project_name", None
        )

        agent_class = create_litellm_agent_class(
            prompt_variant, optimizer_ref=self._optimizer
        )
        instantiate_kwargs: dict[str, Any] = {}
        if project_name is not None:
            instantiate_kwargs["project_name"] = project_name

        try:
            agent = self._optimizer._instantiate_agent(
                prompt_variant, agent_class=agent_class, **instantiate_kwargs
            )
        except TypeError:
            agent = self._optimizer._instantiate_agent(
                prompt_variant, agent_class=agent_class
            )

        def _local_evaluation() -> EvaluationBatch[dict[str, Any], dict[str, Any]]:
            outputs: list[dict[str, Any]] = []
            scores: list[float] = []
            trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

            for inst in batch:
                dataset_item = inst.opik_item
                messages = prompt_variant.get_messages(dataset_item)
                raw_output = agent.invoke(messages).strip()

                metric_result = self._metric(dataset_item, raw_output)
                if hasattr(metric_result, "value"):
                    score = float(metric_result.value)
                elif hasattr(metric_result, "score"):
                    score = float(metric_result.score)
                else:
                    score = float(metric_result)

                outputs.append({"output": raw_output})
                scores.append(score)
                try:
                    self._optimizer._gepa_live_metric_calls += 1
                except Exception:
                    pass

                if trajectories is not None:
                    trajectories.append(
                        {
                            "input": dataset_item,
                            "output": raw_output,
                            "score": score,
                        }
                    )

            return EvaluationBatch(
                outputs=outputs,
                scores=scores,
                trajectories=trajectories,
            )

        if missing_ids:
            logger.debug(
                "Dataset items are missing IDs; falling back to local GEPA evaluation"
            )
            return _local_evaluation()

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            messages = prompt_variant.get_messages(dataset_item)
            raw_output = agent.invoke(messages).strip()
            return {"llm_output": raw_output}

        try:
            _, eval_result = task_evaluator.evaluate_with_result(
                dataset=target_dataset,
                evaluated_task=llm_task,
                metric=self._metric,
                num_threads=self._optimizer.n_threads,
                optimization_id=getattr(
                    self._optimizer, "current_optimization_id", None
                ),
                dataset_item_ids=dataset_item_ids,
                project_name=project_name,
                n_samples=None,
                experiment_config=experiment_config,
                verbose=0,
            )
        except Exception:
            logger.exception(
                "Evaluating GEPA candidate via task_evaluator failed; reverting to local evaluation"
            )
            return _local_evaluation()

        if eval_result is None:
            logger.debug(
                "Opik evaluator returned no results; using local GEPA evaluation"
            )
            return _local_evaluation()

        results_by_id = {
            test_result.test_case.dataset_item_id: test_result
            for test_result in eval_result.test_results
        }

        outputs: list[dict[str, Any]] = []
        scores: list[float] = []
        trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

        for inst in batch:
            dataset_item = inst.opik_item
            dataset_item_id = dataset_item.get("id")
            test_result = (
                results_by_id.get(dataset_item_id)
                if dataset_item_id is not None
                else None
            )

            output_text = ""
            score_value = 0.0
            if test_result is not None:
                output_text = str(
                    test_result.test_case.task_output.get("llm_output", "")
                ).strip()
                score_result = next(
                    (
                        sr
                        for sr in test_result.score_results
                        if sr.name == self._metric_name
                    ),
                    None,
                )
                if score_result is not None and score_result.value is not None:
                    score_value = float(score_result.value)

            outputs.append({"output": output_text})
            scores.append(score_value)
            try:
                self._optimizer._gepa_live_metric_calls += 1
            except Exception:
                pass

            if trajectories is not None:
                trajectories.append(
                    {
                        "input": dataset_item,
                        "output": output_text,
                        "score": score_value,
                    }
                )

        return EvaluationBatch(
            outputs=outputs,
            scores=scores,
            trajectories=trajectories,
        )

    def make_reflective_dataset(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch[dict[str, Any], dict[str, Any]],
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        components = components_to_update or ["system_prompt"]
        trajectories = eval_batch.trajectories or []

        def _records() -> Iterable[dict[str, Any]]:
            for traj in trajectories:
                dataset_item = traj.get("input", {})
                output_text = traj.get("output", "")
                score = traj.get("score", 0.0)
                feedback = f"Observed score={score:.4f}. Expected answer: {dataset_item.get('answer', '')}"
                yield {
                    "Inputs": {
                        "text": dataset_item.get("input")
                        or dataset_item.get("question")
                        or "",
                    },
                    "Generated Outputs": output_text,
                    "Feedback": feedback,
                }

        reflective_records = list(_records())
        if not reflective_records:
            logger.debug(
                "No trajectories captured for candidate; returning empty reflective dataset"
            )
            reflective_records = []

        return {component: reflective_records for component in components}
