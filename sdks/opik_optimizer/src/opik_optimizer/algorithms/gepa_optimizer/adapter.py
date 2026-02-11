from __future__ import annotations

from types import SimpleNamespace
from typing import TYPE_CHECKING, Any
from collections.abc import Iterable
import logging
import random

from gepa.core.adapter import EvaluationBatch, GEPAAdapter
from opik import Dataset

from ... import helpers
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...base_optimizer import _OPTIMIZER_VERSION
from ...core import evaluation as task_evaluator
from ...core import runtime
from ...core.state import prepare_experiment_config
from ...utils.candidate_selection import select_candidate
from .types import OpikDataInst

if TYPE_CHECKING:
    from ...agents import OptimizableAgent
    from ...core.state import OptimizationContext

logger = logging.getLogger(__name__)


def _extract_candidate_system(candidate: dict[str, str]) -> str | None:
    for key in ("system_prompt", "system", "prompt"):
        value = candidate.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return None


class OpikGEPAAdapter(GEPAAdapter[OpikDataInst, dict[str, Any], dict[str, Any]]):
    """GEPA adapter for Opik datasets/prompts with sampling + tool-calling support.

    This adapter supports both construction styles:
    - Legacy multi-prompt flow used by the optimizer runtime/tests.
    - Single-prompt flow used by GEPA-native bridge calls.
    """

    def __init__(
        self,
        *,
        base_prompts: dict[str, chat_prompt.ChatPrompt] | None = None,
        agent: OptimizableAgent | None = None,
        optimizer: Any,
        context: OptimizationContext | None = None,
        metric: MetricFunction,
        dataset: Dataset,
        experiment_config: dict[str, Any] | None,
        validation_dataset: Dataset | None = None,
        base_prompt: chat_prompt.ChatPrompt | None = None,
        system_fallback: str | None = None,
        allow_tool_use: bool = True,
        candidate_selection_policy: str | None = None,
    ) -> None:
        if base_prompts is None:
            if base_prompt is None:
                raise ValueError("Either base_prompts or base_prompt must be provided")
            normalized = base_prompt.copy()
            if system_fallback:
                messages = normalized.get_messages()
                if messages and messages[0].get("role") == "system":
                    messages[0]["content"] = system_fallback
                    normalized.set_messages(messages)
                elif messages:
                    messages.insert(0, {"role": "system", "content": system_fallback})
                    normalized.set_messages(messages)
                else:
                    normalized.system = system_fallback
            base_prompts = {"prompt": normalized}

        self._base_prompts = base_prompts
        self._optimizer = optimizer
        self._metric = metric
        self._dataset = dataset
        self._validation_dataset = validation_dataset
        self._experiment_config = experiment_config
        self._metric_name = metric.__name__
        self._allow_tool_use = allow_tool_use
        self._candidate_selection_policy = candidate_selection_policy

        self._context: OptimizationContext | Any = context or SimpleNamespace(
            extra_params={}, should_stop=False
        )
        self._allowed_roles = (
            self._context.extra_params.get("optimizable_roles")
            if getattr(self._context, "extra_params", None)
            else None
        )

        if agent is None:
            first_prompt = next(iter(self._base_prompts.values()))
            if hasattr(optimizer, "_create_agent_for_prompt"):
                agent = optimizer._create_agent_for_prompt(  # type: ignore[assignment]
                    first_prompt,
                    project_name=getattr(optimizer, "project_name", None),
                )
            else:
                raise ValueError(
                    "agent must be provided when optimizer cannot create one"
                )
        self._agent = agent

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

    def _should_stop(self) -> bool:
        return bool(getattr(self._context, "should_stop", False))

    def _resolve_dataset_for_batch(self, dataset_item_ids: list[str]) -> Dataset:
        if not dataset_item_ids:
            return self._dataset
        if self._validation_dataset is not None and self._val_item_ids:
            if any(id_ in self._val_item_ids for id_ in dataset_item_ids):
                return self._validation_dataset
        return self._dataset

    def _resolve_selection_policy(
        self, prompt_variants: dict[str, chat_prompt.ChatPrompt]
    ) -> str:
        if self._candidate_selection_policy:
            return self._candidate_selection_policy
        prompt = next(iter(prompt_variants.values()))
        model_kwargs = prompt.model_kwargs or {}
        policy = model_kwargs.get("candidate_selection_policy") or model_kwargs.get(
            "selection_policy"
        )
        if isinstance(policy, str) and policy.strip():
            return policy
        return "best_by_metric"

    def _extract_candidate_logprobs(self, candidates: list[str]) -> list[float] | None:
        logprobs = getattr(self._agent, "_last_candidate_logprobs", None)
        if not isinstance(logprobs, list):
            return None
        if len(logprobs) != len(candidates):
            return None
        try:
            return [float(v) for v in logprobs]
        except Exception:
            return None

    def _collect_candidates(
        self,
        prompt_variants: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
    ) -> list[str]:
        has_tools = any(bool(p.tools) for p in prompt_variants.values())
        allow_tool_use = self._allow_tool_use and has_tools

        candidates: list[str] = []
        if hasattr(self._agent, "invoke_agent_candidates"):
            try:
                raw_candidates = self._agent.invoke_agent_candidates(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                    allow_tool_use=allow_tool_use,
                )
            except TypeError:
                raw_candidates = self._agent.invoke_agent_candidates(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                )
            candidates = [
                str(c).strip()
                for c in raw_candidates
                if c is not None and str(c).strip()
            ]

        if not candidates:
            try:
                raw_output = self._agent.invoke_agent(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                    allow_tool_use=allow_tool_use,
                )
            except TypeError:
                raw_output = self._agent.invoke_agent(
                    prompts=prompt_variants,
                    dataset_item=dataset_item,
                )
            if raw_output is not None:
                normalized = str(raw_output).strip()
                if normalized:
                    candidates = [normalized]

        return candidates

    def _metric_to_float(self, dataset_item: dict[str, Any], output: str) -> float:
        metric_result = self._metric(dataset_item, output)
        if hasattr(metric_result, "value"):
            return float(metric_result.value)  # type: ignore[arg-type]
        if hasattr(metric_result, "score"):
            return float(metric_result.score)  # type: ignore[arg-type]
        return float(metric_result)  # type: ignore[arg-type]

    def _select_output_and_score(
        self,
        *,
        prompt_variants: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        candidates: list[str],
    ) -> tuple[str, float]:
        if not candidates:
            return "", 0.0

        policy = self._resolve_selection_policy(prompt_variants)
        candidate_logprobs = self._extract_candidate_logprobs(candidates)

        selection = select_candidate(
            candidates=candidates,
            policy=policy,
            metric=lambda item, output: self._metric(item, output),
            dataset_item=dataset_item,
            candidate_logprobs=candidate_logprobs,
            rng=random.Random(0),
        )

        if (
            selection.candidate_scores is not None
            and selection.chosen_index is not None
            and 0 <= selection.chosen_index < len(selection.candidate_scores)
        ):
            return selection.output, float(selection.candidate_scores[selection.chosen_index])

        return selection.output, self._metric_to_float(dataset_item, selection.output)

    def _record_adapter_metric_call(self) -> None:
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
        self._record_adapter_metric_call()

        if not hasattr(self._optimizer, "pre_round") or not hasattr(
            self._optimizer, "post_round"
        ):
            return

        merged_extra = {"score_label": "per_item"}
        if extra:
            merged_extra.update(extra)

        merged_metrics = dict(metrics or {})
        merged_metrics.setdefault("per_item_score", score)

        try:
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
        except Exception:
            logger.debug("Failed to post GEPA adapter candidate", exc_info=True)

    def _rebuild_prompts_from_candidate(
        self, candidate: dict[str, str]
    ) -> dict[str, chat_prompt.ChatPrompt]:
        rebuilt: dict[str, chat_prompt.ChatPrompt] = {}
        dropped_components = 0
        candidate_system = _extract_candidate_system(candidate)

        for prompt_name, prompt_obj in self._base_prompts.items():
            original_messages = prompt_obj.get_messages()
            new_messages = []

            for idx, msg in enumerate(original_messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                original_content = msg.get("content", "")

                if (
                    self._allowed_roles is not None
                    and msg.get("role") not in self._allowed_roles
                ):
                    optimized_content = original_content
                    dropped_components += 1
                else:
                    optimized_content = candidate.get(component_key, original_content)
                    if (
                        optimized_content == original_content
                        and msg.get("role") == "system"
                        and candidate_system is not None
                    ):
                        optimized_content = candidate_system

                new_messages.append({"role": msg["role"], "content": optimized_content})

            new_prompt = prompt_obj.copy()
            new_prompt.set_messages(new_messages)
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
        prompt_variants = self._rebuild_prompts_from_candidate(candidate)

        dataset_item_ids: list[str] = []
        missing_ids = False
        for inst in batch:
            dataset_item_id = inst.opik_item.get("id")
            if dataset_item_id is None:
                missing_ids = True
                break
            dataset_item_ids.append(str(dataset_item_id))

        target_dataset = self._resolve_dataset_for_batch(dataset_item_ids)
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
            outputs: list[dict[str, Any]] = []
            scores: list[float] = []
            trajectories: list[dict[str, Any]] | None = [] if capture_traces else None

            for inst in batch:
                dataset_item = inst.opik_item
                candidates = self._collect_candidates(prompt_variants, dataset_item)
                raw_output, score = self._select_output_and_score(
                    prompt_variants=prompt_variants,
                    dataset_item=dataset_item,
                    candidates=candidates,
                )

                outputs.append({"output": raw_output})
                scores.append(score)
                self._record_and_post_candidate(
                    prompt_variants=prompt_variants,
                    score=score,
                    metrics={"adapter_metric": score},
                )
                if self._should_stop():
                    break

                if trajectories is not None:
                    trajectories.append(
                        {
                            "input": dataset_item,
                            "output": raw_output,
                            "score": score,
                        }
                    )

            return EvaluationBatch(outputs=outputs, scores=scores, trajectories=trajectories)

        if missing_ids:
            logger.debug("Dataset items are missing IDs; falling back to local evaluation")
            return _local_evaluation()

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            candidates = self._collect_candidates(prompt_variants, dataset_item)
            output, _ = self._select_output_and_score(
                prompt_variants=prompt_variants,
                dataset_item=dataset_item,
                candidates=candidates,
            )
            return {"llm_output": output}

        try:
            _, eval_result = task_evaluator.evaluate_with_result(
                dataset=target_dataset,
                evaluated_task=llm_task,
                metric=self._metric,
                num_threads=self._optimizer.n_threads,
                optimization_id=getattr(self._optimizer, "current_optimization_id", None),
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
            logger.debug("Opik evaluator returned no results; using local evaluation")
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
            test_result = results_by_id.get(dataset_item_id) if dataset_item_id is not None else None

            output_text = ""
            score_value = 0.0
            if test_result is not None:
                output_text = str(test_result.test_case.task_output.get("llm_output", "")).strip()
                score_result = next(
                    (sr for sr in test_result.score_results if sr.name == self._metric_name),
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
            if self._should_stop():
                break

            if trajectories is not None:
                trajectories.append(
                    {
                        "input": dataset_item,
                        "output": output_text,
                        "score": score_value,
                    }
                )

        return EvaluationBatch(outputs=outputs, scores=scores, trajectories=trajectories)

    def make_reflective_dataset(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch[dict[str, Any], dict[str, Any]],
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        if not components_to_update:
            components_to_update = [
                key
                for key in candidate.keys()
                if not key.startswith("_") and key not in ("source", "id")
            ]

        if not components_to_update:
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
            logger.debug("No trajectories captured for candidate; returning empty reflective dataset")

        return {component: reflective_records for component in components_to_update}
