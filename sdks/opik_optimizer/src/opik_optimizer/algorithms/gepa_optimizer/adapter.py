from __future__ import annotations

from typing import Any, TYPE_CHECKING
from collections.abc import Iterable
import random

import logging

from gepa.core.adapter import EvaluationBatch, GEPAAdapter
from opik import Dataset

from ... import helpers
from ...core.state import prepare_experiment_config
from ...base_optimizer import _OPTIMIZER_VERSION
from ...core import evaluation as task_evaluator
from ...utils.toolcalling.core import metadata as tool_metadata
from ...utils.toolcalling.core import segment_updates
from ...core import runtime
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...agents import OptimizableAgent
from ...utils.candidate_selection import select_candidate
from .types import OpikDataInst

if TYPE_CHECKING:
    from ...core.state import OptimizationContext


logger = logging.getLogger(__name__)


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
        context: OptimizationContext,
        metric: MetricFunction,
        dataset: Dataset,
        experiment_config: dict[str, Any] | None,
        validation_dataset: Dataset | None = None,
    ) -> None:
        self._base_prompts = base_prompts
        self._agent = agent
        self._optimizer = optimizer
        self._context = context
        self._metric = metric
        self._dataset = dataset
        self._validation_dataset = validation_dataset
        self._experiment_config = experiment_config
        self._metric_name = metric.__name__
        self._allowed_roles = (
            context.extra_params.get("optimizable_roles")
            if context.extra_params
            else None
        )
        # TODO: Replace with native GEPA adapter once available; role constraints may drop candidate edits.

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

    def _collect_candidates(
        self,
        prompt_variants: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
    ) -> list[str]:
        """Invoke the agent and normalize candidate outputs for scoring."""
        allow_tool_use = bool(getattr(self._context, "allow_tool_use", False))
        if hasattr(self._agent, "invoke_agent_candidates"):
            invoke_candidates = self._agent.invoke_agent_candidates
            try:
                candidates = invoke_candidates(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                    allow_tool_use=allow_tool_use,
                )
            except TypeError as exc:
                if "allow_tool_use" not in str(exc):
                    raise
                candidates = invoke_candidates(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                )
        else:
            invoke_agent = self._agent.invoke_agent
            try:
                single_candidate = invoke_agent(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                    allow_tool_use=allow_tool_use,
                )
            except TypeError as exc:
                if "allow_tool_use" not in str(exc):
                    raise
                single_candidate = invoke_agent(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                )
            candidates = [single_candidate]
        return [str(c).strip() for c in candidates if c is not None and str(c).strip()]

    def _record_adapter_metric_call(self) -> None:
        """Increment adapter metric counters while tolerating missing attributes."""
        try:
            self._optimizer._adapter_metric_calls += 1
        except Exception:
            pass

    def _record_and_post_candidate(
        self,
        *,
        prompt_variants: dict[str, chat_prompt.ChatPrompt],
        score: float,
        candidate_id: str | None = None,
        metrics: dict[str, Any] | None = None,
        extra: dict[str, Any] | None = None,
    ) -> None:
        """Record adapter metric call and post candidate/round to history."""
        self._record_adapter_metric_call()
        merged_extra = {"score_label": "per_item"}
        if extra:
            merged_extra.update(extra)
        merged_metrics = dict(metrics or {})
        merged_metrics.setdefault("per_item_score", score)
        round_handle = self._optimizer.pre_round(self._context)
        runtime.record_and_post_trial(
            optimizer=self._optimizer,
            context=self._context,
            prompt_or_payload=prompt_variants,
            score=None,
            candidate_id=candidate_id,
            metrics=merged_metrics,
            extra=merged_extra,
            round_handle=round_handle,
        )
        self._optimizer.post_round(
            round_handle,
            context=self._context,
            best_score=None,
            best_prompt=prompt_variants,
            extras={"score_label": "per_item"},
        )

    def _rebuild_prompts_from_candidate(
        self, candidate: dict[str, str]
    ) -> dict[str, chat_prompt.ChatPrompt]:
        """Rebuild prompts with optimized messages, preserving tools/function_map/model."""
        rebuilt: dict[str, chat_prompt.ChatPrompt] = {}
        dropped_components = 0
        for prompt_name, prompt_obj in self._base_prompts.items():
            original_messages = prompt_obj.get_messages()
            new_messages = []
            for idx, msg in enumerate(original_messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                # Use optimized content if available, otherwise keep original
                original_content = msg.get("content", "")
                if (
                    self._allowed_roles is not None
                    and msg.get("role") not in self._allowed_roles
                ):
                    optimized_content = original_content
                    dropped_components += 1
                else:
                    optimized_content = candidate.get(component_key, original_content)
                new_messages.append({"role": msg["role"], "content": optimized_content})

            # prompt.copy() preserves tools, function_map, model, model_kwargs
            new_prompt = prompt_obj.copy()
            new_prompt.set_messages(new_messages)

            new_prompt = segment_updates.apply_tool_updates_from_candidate(
                candidate=candidate,
                prompt=new_prompt,
                tool_component_prefix=f"{prompt_name}{segment_updates.TOOL_COMPONENT_PREFIX}",
                tool_param_component_prefix=(
                    f"{prompt_name}{segment_updates.TOOL_PARAM_COMPONENT_PREFIX}"
                ),
            )
            # Final prompt
            rebuilt[prompt_name] = new_prompt
        if dropped_components:
            logger.warning(
                "GEPA adapter dropped %s component(s) due to optimize_prompt constraints.",
                dropped_components,
            )
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
        experiment_config = prepare_experiment_config(
            optimizer=self._optimizer,
            prompt=prompt_variants,
            dataset=target_dataset,
            metric=self._metric,
            experiment_config=self._experiment_config,
            configuration_updates=configuration_updates,
            additional_metadata=None,
            validation_dataset=None,
            is_single_prompt_optimization=isinstance(
                prompt_variants, chat_prompt.ChatPrompt
            ),
            agent=None,
            build_optimizer_version=_OPTIMIZER_VERSION,
        )
        project_name = experiment_config.get("project_name") or getattr(
            self._optimizer, "project_name", None
        )

        def _local_evaluation() -> EvaluationBatch[dict[str, Any], dict[str, Any]]:
            """Fallback to direct evaluation without task_evaluator."""
            # TODO(opik_optimizer/#gepa-adapter): Remove this local scoring path once GEPA provides a native Opik adapter.
            outputs: list[dict[str, Any]] = []
            scores: list[float] = []
            trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

            def _score_candidates(
                dataset_item: dict[str, Any], candidates: list[str]
            ) -> tuple[str, float]:
                """Pick the best candidate using the optimizer metric for pass@k evaluation."""
                # FIXME: Align this selection with GEPA's Pareto/multi-metric scoring once integrated.
                selection = select_candidate(
                    candidates=candidates,
                    policy="best_by_metric",
                    metric=lambda item, output: self._metric(item, output),
                    dataset_item=dataset_item,
                    candidate_logprobs=None,
                    rng=random.Random(0),
                )
                if (
                    selection.candidate_scores is not None
                    and selection.chosen_index is not None
                ):
                    best_score = selection.candidate_scores[selection.chosen_index]
                else:
                    metric_result = self._metric(dataset_item, selection.output)
                    if hasattr(metric_result, "value"):
                        best_score = float(metric_result.value)  # type: ignore[arg-type]
                    elif hasattr(metric_result, "score"):
                        best_score = float(metric_result.score)  # type: ignore[arg-type]
                    else:
                        best_score = float(metric_result)  # type: ignore[arg-type]
                return selection.output, best_score

            for inst in batch:
                dataset_item = inst.opik_item
                candidates = self._collect_candidates(prompt_variants, dataset_item)
                raw_output, score = _score_candidates(dataset_item, candidates)

                outputs.append({"output": raw_output})
                scores.append(score)
                self._record_and_post_candidate(
                    prompt_variants=prompt_variants,
                    score=score,
                    metrics={"adapter_metric": score},
                )
                if self._context.should_stop:
                    break

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
            # Choose the best candidate output before passing into Opik evaluation.
            candidates = self._collect_candidates(prompt_variants, dataset_item)
            if not candidates:
                raise RuntimeError("No candidates produced by agent")

            best_output = candidates[0]
            best_score = float("-inf")
            for candidate in candidates:
                metric_result = self._metric(dataset_item, candidate)
                if hasattr(metric_result, "value"):
                    score = float(metric_result.value)  # type: ignore[arg-type]
                elif hasattr(metric_result, "score"):
                    score = float(metric_result.score)  # type: ignore[arg-type]
                else:
                    score = float(metric_result)  # type: ignore[arg-type]
                if score > best_score:
                    best_score = score
                    best_output = candidate

            return {"llm_output": best_output}

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
            self._record_and_post_candidate(
                prompt_variants=prompt_variants,
                score=score_value,
                candidate_id=candidate.get("id"),
                metrics={self._metric_name: score_value, "opik_score": score_value},
                extra={"output": output_text, "candidate": candidate},
            )
            if self._context.should_stop:
                break

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
        tool_metadata_by_component: dict[str, str] = {}
        if self._context.extra_params.get("optimize_tools"):
            tool_metadata_by_component = tool_metadata.build_tool_metadata_by_component(
                base_prompts=self._base_prompts
            )

        def _records(component_key: str) -> Iterable[dict[str, Any]]:
            tool_metadata = tool_metadata_by_component.get(component_key)
            for traj in trajectories:
                dataset_item = traj.get("input", {})
                output_text = traj.get("output", "")
                score = traj.get("score", 0.0)
                feedback = f"Observed score={score:.4f}. Expected answer: {dataset_item.get('answer', '')}"
                if tool_metadata:
                    feedback += f" Tool metadata: {tool_metadata}"
                yield {
                    "Inputs": {
                        "text": dataset_item.get("input")
                        or dataset_item.get("question")
                        or "",
                    },
                    "Generated Outputs": output_text,
                    "Feedback": feedback,
                }

        if not trajectories:
            logger.debug(
                "No trajectories captured for candidate; returning empty reflective dataset"
            )
            return {component: [] for component in components_to_update}

        return {
            component: list(_records(component)) for component in components_to_update
        }
