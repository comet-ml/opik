from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Iterable

import logging

from gepa.core.adapter import EvaluationBatch, GEPAAdapter
from opik import Dataset

from ... import helpers, task_evaluator
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...agents import OptimizableAgent


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


class OpikGEPAAdapter(GEPAAdapter[OpikDataInst, dict[str, Any], dict[str, Any]]):
    """GEPA adapter that supports multi-prompt, multi-message optimization.

    This adapter handles optimization of all message types (system, user, assistant)
    across multiple prompts in a dict.
    """

    def __init__(
        self,
        base_prompts: dict[str, chat_prompt.ChatPrompt],
        agent: OptimizableAgent,
        optimizer: Any,
        metric: MetricFunction,
        dataset: Dataset,
        experiment_config: dict[str, Any] | None,
    ) -> None:
        self._base_prompts = base_prompts
        self._agent = agent
        self._optimizer = optimizer
        self._metric = metric
        self._dataset = dataset
        self._experiment_config = experiment_config
        self._metric_name = metric.__name__

    def _rebuild_prompts_from_candidate(
        self, candidate: dict[str, str]
    ) -> dict[str, chat_prompt.ChatPrompt]:
        """Rebuild prompts with optimized messages, preserving tools/function_map/model.

        Args:
            candidate: Dict mapping component keys (e.g., "prompt_name_role_idx") to optimized content.

        Returns:
            Dict of ChatPrompt objects with optimized message content.
        """
        rebuilt: dict[str, chat_prompt.ChatPrompt] = {}
        for prompt_name, prompt_obj in self._base_prompts.items():
            original_messages = prompt_obj.get_messages()
            new_messages = []
            for idx, msg in enumerate(original_messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                # Use optimized content if available, otherwise keep original
                original_content = msg.get("content", "")
                optimized_content = candidate.get(component_key, original_content)
                new_messages.append({"role": msg["role"], "content": optimized_content})

            # prompt.copy() preserves tools, function_map, model, model_kwargs
            new_prompt = prompt_obj.copy()
            new_prompt.set_messages(new_messages)
            rebuilt[prompt_name] = new_prompt
        return rebuilt

    def evaluate(
        self,
        batch: list[OpikDataInst],
        candidate: dict[str, str],
        capture_traces: bool = False,
    ) -> EvaluationBatch[dict[str, Any], dict[str, Any]]:
        """
        Evaluate a GEPA candidate against a batch of Opik items.

        Rebuilds all prompts with optimized message content from the candidate,
        then uses the agent to evaluate each item.
        """
        # Rebuild prompts with optimized components from candidate
        prompt_variants = self._rebuild_prompts_from_candidate(candidate)

        dataset_item_ids: list[str] = []
        missing_ids = False
        for inst in batch:
            dataset_item_id = inst.opik_item.get("id")
            if dataset_item_id is None:
                missing_ids = True
                break
            dataset_item_ids.append(str(dataset_item_id))

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
            prompt=prompt_variants,
            dataset=self._dataset,
            metric=self._metric,
            experiment_config=self._experiment_config,
            configuration_updates=configuration_updates,
        )
        project_name = experiment_config.get("project_name") or getattr(
            self._optimizer, "project_name", None
        )

        def _local_evaluation() -> EvaluationBatch[dict[str, Any], dict[str, Any]]:
            """Fallback to direct evaluation without task_evaluator."""
            outputs: list[dict[str, Any]] = []
            scores: list[float] = []
            trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

            for inst in batch:
                dataset_item = inst.opik_item
                # Use agent.invoke_agent with dict of prompts
                raw_output = self._agent.invoke_agent(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                )
                raw_output = (
                    raw_output.strip()
                    if isinstance(raw_output, str)
                    else str(raw_output).strip()
                )

                metric_result = self._metric(dataset_item, raw_output)
                if hasattr(metric_result, "value"):
                    score = float(metric_result.value)  # type: ignore[arg-type]
                elif hasattr(metric_result, "score"):
                    score = float(metric_result.score)  # type: ignore[arg-type]
                else:
                    score = float(metric_result)  # type: ignore[arg-type]

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
            raw_output = self._agent.invoke_agent(
                prompts=prompt_variants,
                dataset_item=dataset_item,
            )
            raw_output = (
                raw_output.strip()
                if isinstance(raw_output, str)
                else str(raw_output).strip()
            )
            return {"llm_output": raw_output}

        try:
            _, eval_result = task_evaluator.evaluate_with_result(
                dataset=self._dataset,
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
        """Create reflective dataset for GEPA's learning process.

        Uses the component keys from the candidate (e.g., "prompt_name_role_idx")
        to organize feedback for each optimizable component.
        """
        # If no specific components requested, use all component keys from candidate
        # that match our prompt structure
        if not components_to_update:
            components_to_update = [
                key
                for key in candidate.keys()
                if not key.startswith("_") and key not in ("source", "id")
            ]

        if not components_to_update:
            # Fallback to legacy behavior
            components_to_update = ["system_prompt"]

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

        return {component: reflective_records for component in components_to_update}
