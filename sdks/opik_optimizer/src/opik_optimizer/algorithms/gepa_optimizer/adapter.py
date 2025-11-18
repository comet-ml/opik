from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from collections.abc import Callable, Iterable

import logging

from gepa.core.adapter import EvaluationBatch, GEPAAdapter

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
    ) -> None:
        self._base_prompt = base_prompt
        self._optimizer = optimizer
        self._metric = metric
        self._system_fallback = system_fallback

    def evaluate(
        self,
        batch: list[OpikDataInst],
        candidate: dict[str, str],
        capture_traces: bool = False,
    ) -> EvaluationBatch[dict[str, Any], dict[str, Any]]:
        system_text = _extract_system_text(candidate, self._system_fallback)
        prompt_variant = _apply_system_text(self._base_prompt, system_text)

        agent_class = create_litellm_agent_class(
            prompt_variant, optimizer_ref=self._optimizer
        )
        agent = agent_class(prompt_variant)

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
            outputs=outputs, scores=scores, trajectories=trajectories
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
