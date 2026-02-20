"""Module containing the OptimizationResult class."""

from __future__ import annotations

from typing import Any
from collections.abc import Callable, Iterator
from dataclasses import dataclass, field
from contextlib import contextmanager

import pydantic
import rich.panel
from ..utils.reporting import get_console, get_optimization_run_url_by_id
from ..utils.display import (
    format_prompt_for_plaintext,
    build_plaintext_summary,
    render_rich_result,
)

from ..api_objects import chat_prompt
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .state import OptimizationContext
from ..constants import OPTIMIZATION_RESULT_SCHEMA_VERSION
from ..utils.logging import debug_log


from ..utils.time import now_iso


@dataclass
class OptimizerCandidate:
    """
    Canonical candidate payload used across trials/rounds.

    Encapsulates a prompt (or bundle of prompts) plus optional metadata such as
    score, metrics, notes, tools, and extra fields for lineage/selection data.
    """

    candidate: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt] | Any
    id: str | None = None
    score: float | None = None
    metrics: dict[str, Any] | None = None
    notes: str | None = None
    extra: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "candidate": self.candidate,
            "score": self.score,
            "metrics": self.metrics,
            "notes": self.notes,
            "extra": self.extra,
        }


@dataclass
class OptimizationTrial:
    """
    Atomic evaluation record used in history.

    A trial represents one scoring event of a candidate prompt (or prompt bundle)
    against a dataset split. Trials can carry prompt payloads, per-metric scores,
    and optimizer-specific extras. These get nested under an OptimizationRound to
    preserve grouping and budgeting alignment (trial_index is tied to the global
    trials_completed counter).
    """

    trial_index: int | None
    score: float | None
    candidate: Any
    metrics: dict[str, Any] | None = None
    dataset: str | None = None
    dataset_split: str | None = None
    candidate_id: str | None = None
    extras: dict[str, Any] | None = None
    timestamp: str = field(default_factory=now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "trial_index": self.trial_index,
            "score": self.score,
            "candidate": self.candidate,
            "metrics": self.metrics,
            "dataset": self.dataset,
            "dataset_split": self.dataset_split,
            "candidate_id": self.candidate_id,
            "extra": self.extras,
            "timestamp": self.timestamp,
        }


@dataclass
class OptimizationRound:
    """
    Iteration-level history record.

    A round captures the optimizer's state for one outer iteration/generation:
    - round_index: zero-based iteration counter.
    - trials: list of OptimizationTrial entries executed in this round.
    - best_score/best_prompt: per-round best values (primary metric).
    - candidates/generated_prompts: optional candidate metadata for display.
    - stop_reason/extras/timestamp: control/context metadata.

    Optimizers are encouraged to emit rounds through OptimizationHistoryState
    so that per-round aggregation and best_so_far bookkeeping are consistent.
    """

    round_index: int
    trials: list[OptimizationTrial] = field(default_factory=list)
    best_score: float | None = None
    best_so_far: float | None = None
    best_prompt: Any | None = None
    best_candidate: Any | None = None
    candidates: list[dict[str, Any]] | None = None
    generated_prompts: list[dict[str, Any]] | None = None
    stop_reason: str | None = None
    stopped: bool | None = None
    dataset_split: str | None = None
    extras: dict[str, Any] | None = None
    timestamp: str = field(default_factory=now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "round_index": self.round_index,
            "trials": [t.to_dict() for t in self.trials],
            "best_score": self.best_score,
            "best_so_far": self.best_so_far,
            "best_prompt": self.best_prompt,
            "best_candidate": self.best_candidate,
            "candidates": self.candidates,
            "generated_prompts": self.generated_prompts,
            "stop_reason": self.stop_reason,
            "stopped": self.stopped,
            "dataset_split": self.dataset_split,
            "extra": self.extras,
            "timestamp": self.timestamp,
        }


def round_payload(round_data: OptimizationRound | dict[str, Any]) -> dict[str, Any]:
    """Return a dict payload for a round entry (typed or plain dict)."""
    if isinstance(round_data, OptimizationRound):
        return round_data.to_dict()
    return dict(round_data)


class OptimizationHistoryState:
    """
    Stateful history manager with explicit start/record/end lifecycle.

    Captures running best scores and timestamps so optimizers can append
    consistent history without post-hoc normalization. It merges entries for
    the same round_index, coerces floats, and stamps timestamps/best_so_far.
    BaseOptimizer later consumes entries directly when constructing the final
    OptimizationResult.
    """

    def __init__(self, context: OptimizationContext | None = None) -> None:
        self.entries: list[OptimizationRound] = []
        self._best_so_far: float | None = None
        self._open_rounds: dict[Any, OptimizationRound] = {}
        self._default_dataset_split: str | None = None
        self._current_selection_meta: dict[str, Any] | None = None
        self._current_pareto_front: list[dict[str, Any]] | None = None
        self._context: OptimizationContext | None = context

    def set_context(self, context: OptimizationContext | None) -> None:
        """Attach the current optimization context for downstream hooks."""
        self._context = context

    @staticmethod
    def _coerce_float(value: Any) -> float | None:
        try:
            return float(value)
        except Exception:
            return None

    def _next_candidate_id(self, prefix: str | None = None) -> str:
        """Generate a simple incremental candidate ID, optionally with a prefix."""
        base = len(self.entries)
        suffix = sum(len(e.trials) for e in self.entries)
        stem = prefix or "cand"
        return f"{stem}_{base}_{suffix}"

    @staticmethod
    def _round_key(round_handle: Any) -> Any:
        if isinstance(round_handle, int):
            return round_handle
        return round_handle

    def _get_open_round(self, round_handle: Any) -> OptimizationRound:
        key = self._round_key(round_handle)
        entry = self._open_rounds.get(key)
        if entry is None:
            entry = OptimizationRound(
                round_index=key, trials=[], candidates=[], extras={}
            )
            self._open_rounds[key] = entry
        return entry

    def _next_trial_index(self, entry: OptimizationRound) -> int:
        return sum(len(e.trials) for e in self.entries) + len(entry.trials)

    @staticmethod
    def _normalize_candidate_payload(candidate: Any) -> Any:
        if candidate is None:
            return None

        def _capture_tools(prompt_obj: Any) -> list[dict[str, Any]] | None:
            tools = getattr(prompt_obj, "tools", None)
            if not tools:
                return None
            return [
                dict(tool) if isinstance(tool, dict) else {"value": tool}
                for tool in tools
            ]

        def _capture_original_tools(prompt_obj: Any) -> list[dict[str, Any]] | None:
            tools = getattr(prompt_obj, "tools_original", None)
            if not tools:
                return None
            return [
                dict(tool) if isinstance(tool, dict) else {"value": tool}
                for tool in tools
            ]

        # Normalize prompts to messages format to match baseline evaluation format
        # Always use dict format: {prompt_name: messages}
        if isinstance(candidate, chat_prompt.ChatPrompt):
            # Single prompt: normalize to dict format with prompt name as key
            prompt_name = getattr(candidate, "name", "prompt")
            payload: dict[str, Any] = {"messages": candidate.get_messages()}
            tool_payload = _capture_tools(candidate)
            if tool_payload:
                payload["tools"] = tool_payload
            original_tools = _capture_original_tools(candidate)
            if original_tools:
                payload["tools_original"] = original_tools
            model_kwargs = getattr(candidate, "model_kwargs", None)
            if model_kwargs:
                payload["model_kwargs"] = model_kwargs
            return {prompt_name: payload}
        if isinstance(candidate, dict):
            # Check if it's a dict of ChatPrompts
            first_value = next(iter(candidate.values())) if candidate else None
            if isinstance(first_value, chat_prompt.ChatPrompt):
                # Multi-prompt: normalize to dict[str, list[dict]]
                normalized: dict[str, Any] = {}
                for key, prompt_obj in candidate.items():
                    payload = {"messages": prompt_obj.get_messages()}
                    tool_payload = _capture_tools(prompt_obj)
                    if tool_payload:
                        payload["tools"] = tool_payload
                    original_tools = _capture_original_tools(prompt_obj)
                    if original_tools:
                        payload["tools_original"] = original_tools
                    model_kwargs = getattr(prompt_obj, "model_kwargs", None)
                    if model_kwargs:
                        payload["model_kwargs"] = model_kwargs
                    normalized[key] = payload
                return normalized
            # Otherwise, keep as-is (already a dict, not ChatPrompts)
            return candidate
        return {"value": candidate}

    def _normalize_candidates(
        self, candidates: list[dict[str, Any]] | list[OptimizerCandidate] | None
    ) -> list[dict[str, Any]] | None:
        if not candidates:
            return None

        normalized: list[dict[str, Any]] = []
        for candidate in candidates:
            if isinstance(candidate, OptimizerCandidate):
                normalized.append(candidate.to_dict())
                continue
            if not isinstance(candidate, dict):
                normalized.append(
                    {"candidate": self._normalize_candidate_payload(candidate)}
                )
                continue

            payload = dict(candidate)
            candidate_id = payload.pop("id", None)
            prompt_payload = payload.pop("prompt", payload.pop("prompts", None))
            if prompt_payload is None and "candidate" in payload:
                prompt_payload = payload.pop("candidate")
            prompt_payload = self._normalize_candidate_payload(prompt_payload)
            score = payload.pop("score", None)
            metrics = payload.pop("metrics", None)
            notes = payload.pop("notes", payload.pop("reason", None))
            extra = payload.pop("extra", None)
            if isinstance(extra, str):
                notes = notes or extra
                extra = None

            if prompt_payload is None and payload:
                prompt_payload = payload.pop("payload", None)

            if payload:
                extra = {**(extra or {}), **payload}

            normalized.append(
                {
                    "id": candidate_id,
                    "candidate": prompt_payload
                    if prompt_payload is not None
                    else self._normalize_candidate_payload(candidate),
                    "score": score,
                    "metrics": metrics,
                    "notes": notes,
                    "extra": extra,
                }
            )

        return normalized

    def _merge_round(self, entry: OptimizationRound) -> None:
        """Merge a new entry into existing entries when round_index matches."""
        round_idx = entry.round_index
        for existing in self.entries:
            if existing.round_index == round_idx:
                existing.trials.extend(entry.trials)
                if entry.candidates:
                    existing.candidates = (existing.candidates or []) + entry.candidates
                if entry.best_score is not None:
                    existing.best_score = entry.best_score
                if entry.best_prompt is not None:
                    existing.best_prompt = entry.best_prompt
                if entry.best_candidate is not None:
                    existing.best_candidate = entry.best_candidate
                if entry.best_so_far is not None:
                    existing.best_so_far = entry.best_so_far
                if entry.extras:
                    existing.extras = {**(existing.extras or {}), **entry.extras}
                if entry.stop_reason is not None:
                    existing.stop_reason = entry.stop_reason
                    existing.stopped = entry.stop_reason not in (None, "completed")
                if entry.dataset_split is not None:
                    existing.dataset_split = entry.dataset_split
                return
        self.entries.append(entry)

    def start_round(
        self,
        round_index: int | None = None,
        extras: dict[str, Any] | None = None,
        timestamp: str | None = None,
    ) -> Any:
        idx = int(round_index) if round_index is not None else len(self.entries)
        entry = self._get_open_round(idx)
        entry.extras = extras or entry.extras or {}
        entry.timestamp = timestamp or entry.timestamp or now_iso()
        debug_log("round_start", round_index=idx, extras=extras)
        return idx

    def set_default_dataset_split(self, dataset_split: str | None) -> None:
        """Set a default dataset split for subsequent trials/rounds."""
        self._default_dataset_split = dataset_split

    def set_selection_meta(self, selection_meta: dict[str, Any] | None) -> None:
        """Set selection metadata for the current round; consumed on end_round."""
        self._current_selection_meta = selection_meta

    def set_pareto_front(self, pareto_front: list[dict[str, Any]] | None) -> None:
        """Set pareto front for the current round; consumed on end_round."""
        self._current_pareto_front = pareto_front

    @contextmanager
    def with_dataset_split(self, dataset_split: str | None) -> Iterator[None]:
        """Temporarily set the default dataset split within the context."""
        previous = self._default_dataset_split
        self.set_default_dataset_split(dataset_split)
        try:
            yield
        finally:
            self.set_default_dataset_split(previous)

    def _build_trial(
        self,
        entry: OptimizationRound,
        *,
        score: float | None,
        candidate: Any | None,
        trial_index: int | None,
        metrics: dict[str, Any] | None,
        dataset: str | None,
        dataset_split: str | None,
        extras: dict[str, Any] | None,
        timestamp: str | None,
    ) -> tuple[OptimizationTrial, Any, str | None]:
        score_val = self._coerce_float(score) if score is not None else None
        candidate_payload_raw: Any = (
            candidate.to_dict()
            if isinstance(candidate, OptimizerCandidate)
            else candidate
        )
        if isinstance(candidate_payload_raw, dict):
            candidate_payload = dict(candidate_payload_raw)
        else:
            candidate_payload = candidate_payload_raw
        candidate_payload = self._normalize_candidate_payload(candidate_payload)
        dataset_split_val = dataset_split or self._default_dataset_split
        trial = OptimizationTrial(
            trial_index=int(trial_index) if isinstance(trial_index, int) else None,
            score=score_val,
            candidate=candidate_payload,
            metrics=metrics,
            dataset=dataset,
            dataset_split=dataset_split_val,
            extras=extras,
            timestamp=timestamp or now_iso(),
        )
        if trial.trial_index is None:
            trial.trial_index = self._next_trial_index(entry)
        return trial, candidate_payload, dataset_split_val

    @staticmethod
    def _resolve_candidate_id(
        trial: OptimizationTrial,
        *,
        candidate_payload: Any,
        candidates: list[dict[str, Any]] | None,
        candidate_id_prefix: str | None,
        next_candidate_id: Callable[[str], str],
    ) -> None:
        candidate_id: str | None = None
        if isinstance(candidate_payload, dict):
            candidate_id = candidate_payload.get("id")
        if candidate_id is None and candidates:
            candidate_id = next(
                (
                    candidate.get("id")
                    for candidate in candidates
                    if isinstance(candidate, dict)
                ),
                None,
            )
        if candidate_id is None and candidate_id_prefix is not None:
            candidate_id = next_candidate_id(candidate_id_prefix)
        if candidate_id is not None:
            trial.candidate_id = candidate_id

    def _update_best_so_far(
        self, entry: OptimizationRound, score_val: float | None
    ) -> None:
        if score_val is None:
            return
        self._best_so_far = (
            score_val
            if self._best_so_far is None
            else max(self._best_so_far, score_val)
        )
        entry.best_so_far = self._best_so_far

    def _merge_candidates_into_round(
        self,
        entry: OptimizationRound,
        *,
        candidates: list[dict[str, Any]] | None,
        candidate_payload: Any,
        score_val: float | None,
        metrics: dict[str, Any] | None,
        extras: dict[str, Any] | None,
    ) -> None:
        if candidates is None and candidate_payload is not None:
            candidates = [
                {
                    "candidate": candidate_payload,
                    "score": score_val,
                    "metrics": metrics,
                    "extra": extras,
                }
            ]
        if candidates:
            normalized_candidates = self._normalize_candidates(candidates) or []
            entry.candidates = (entry.candidates or []) + normalized_candidates

    @staticmethod
    def _apply_stop_reason(entry: OptimizationRound, stop_reason: str | None) -> None:
        if stop_reason is not None:
            entry.stop_reason = stop_reason
            entry.stopped = stop_reason not in (None, "completed")
        else:
            entry.stop_reason = entry.stop_reason or None
            entry.stopped = entry.stopped if entry.stopped is not None else False

    def record_trial(
        self,
        round_handle: Any,
        *,
        score: float | None,
        candidate: Any | None = None,
        trial_index: int | None = None,
        metrics: dict[str, Any] | None = None,
        dataset: str | None = None,
        dataset_split: str | None = None,
        extras: dict[str, Any] | None = None,
        candidates: list[dict[str, Any]] | None = None,
        timestamp: str | None = None,
        stop_reason: str | None = None,
        candidate_id_prefix: str | None = None,
    ) -> dict[str, Any]:
        entry = self._get_open_round(round_handle)
        trial, candidate_payload, dataset_split_val = self._build_trial(
            entry,
            score=score,
            candidate=candidate,
            trial_index=trial_index,
            metrics=metrics,
            dataset=dataset,
            dataset_split=dataset_split,
            extras=extras,
            timestamp=timestamp,
        )
        self._resolve_candidate_id(
            trial,
            candidate_payload=candidate_payload,
            candidates=candidates,
            candidate_id_prefix=candidate_id_prefix,
            next_candidate_id=self._next_candidate_id,
        )
        entry.trials.append(trial)
        self._update_best_so_far(entry, trial.score)
        self._merge_candidates_into_round(
            entry,
            candidates=candidates,
            candidate_payload=candidate_payload,
            score_val=trial.score,
            metrics=metrics,
            extras=extras,
        )
        self._apply_stop_reason(entry, stop_reason)
        debug_log(
            "trial_recorded",
            round_index=round_handle,
            trial_index=trial.trial_index,
            score=trial.score,
            dataset=dataset,
            dataset_split=dataset_split_val,
            candidate_id=trial.candidate_id,
            score_label=(extras or {}).get("score_label")
            if isinstance(extras, dict)
            else None,
        )
        return trial.to_dict()

    def _apply_best_score_to_round(
        self, entry: OptimizationRound, best_score: float | None
    ) -> None:
        if best_score is None or entry.best_score is not None:
            return
        best_score_val = self._coerce_float(best_score)
        entry.best_score = best_score_val
        self._update_best_so_far(entry, best_score_val)

    @staticmethod
    def _apply_best_candidate_to_round(
        entry: OptimizationRound, best_candidate: Any | None
    ) -> None:
        if best_candidate is None:
            return
        entry.best_candidate = (
            best_candidate.to_dict()
            if isinstance(best_candidate, OptimizerCandidate)
            else best_candidate
        )

    @staticmethod
    def _apply_best_prompt_to_round(
        entry: OptimizationRound, best_prompt: Any | None
    ) -> None:
        if best_prompt is not None:
            entry.best_prompt = best_prompt

    @staticmethod
    def _apply_round_extras(
        entry: OptimizationRound, extras: dict[str, Any] | None
    ) -> None:
        if extras:
            entry.extras = {**(entry.extras or {}), **extras}

    def _apply_round_selection_metadata(
        self,
        entry: OptimizationRound,
        *,
        pareto_front: list[dict[str, Any]] | None,
        selection_meta: dict[str, Any] | None,
    ) -> None:
        pareto_payload = (
            pareto_front if pareto_front is not None else self._current_pareto_front
        )
        if pareto_payload is not None:
            entry.extras = {**(entry.extras or {}), "pareto_front": pareto_payload}
        selection_meta_payload = (
            selection_meta
            if selection_meta is not None
            else self._current_selection_meta
        )
        if selection_meta_payload is not None:
            entry.extras = {
                **(entry.extras or {}),
                "selection_meta": selection_meta_payload,
            }

    @staticmethod
    def _resolve_round_stop_reason(
        entry: OptimizationRound, stop_reason: str | None
    ) -> str:
        if stop_reason is None:
            stop_reason = entry.stop_reason
        if stop_reason is None:
            stop_reason = "completed"
        return stop_reason

    def end_round(
        self,
        round_handle: Any,
        *,
        best_score: float | None = None,
        best_candidate: Any | None = None,
        best_prompt: Any | None = None,
        stop_reason: str | None = None,
        extras: dict[str, Any] | None = None,
        candidates: list[dict[str, Any]] | None = None,
        timestamp: str | None = None,
        pareto_front: list[dict[str, Any]] | None = None,
        selection_meta: dict[str, Any] | None = None,
        dataset_split: str | None = None,
    ) -> dict[str, Any]:
        entry = self._open_rounds.pop(
            self._round_key(round_handle),
            OptimizationRound(
                round_index=round_handle, trials=[], candidates=[], extras={}
            ),
        )
        dataset_split_val = dataset_split or self._default_dataset_split
        if candidates:
            normalized_candidates = self._normalize_candidates(candidates) or []
            entry.candidates = (entry.candidates or []) + normalized_candidates
        self._apply_best_score_to_round(entry, best_score)
        self._apply_best_candidate_to_round(entry, best_candidate)
        self._apply_best_prompt_to_round(entry, best_prompt)
        self._apply_round_extras(entry, extras)
        if dataset_split_val is not None:
            entry.dataset_split = dataset_split_val

        self._apply_round_selection_metadata(
            entry,
            pareto_front=pareto_front,
            selection_meta=selection_meta,
        )
        stop_reason = self._resolve_round_stop_reason(entry, stop_reason)
        self._apply_stop_reason(entry, stop_reason)
        entry.timestamp = timestamp or entry.timestamp or now_iso()
        self._merge_round(entry)
        self._current_selection_meta = None
        self._current_pareto_front = None
        debug_log(
            "round_end",
            round_index=round_handle,
            best_score=entry.best_score,
            best_so_far=entry.best_so_far,
            trials=len(entry.trials),
            selection_meta=(entry.extras or {}).get("selection_meta")
            if isinstance(entry.extras, dict)
            else None,
            score_label=(entry.extras or {}).get("score_label")
            if isinstance(entry.extras, dict)
            else None,
        )
        return entry.to_dict()

    def finalize_stop(self, stop_reason: str | None = None) -> None:
        self._stamp_stop_reason(stop_reason)

    def get_entries(self) -> list[dict[str, Any]]:
        for entry in list(self._open_rounds.values()):
            self._merge_round(entry)
        self._open_rounds.clear()
        return [entry.to_dict() for entry in self.entries]

    def get_rounds(self) -> list[OptimizationRound]:
        """Return the typed round entries accumulated so far."""
        return list(self.entries)

    def clear(self) -> None:
        self.entries.clear()
        self._best_so_far = None
        self._open_rounds.clear()
        self._default_dataset_split = None
        self._current_selection_meta = None
        self._current_pareto_front = None

    def _stamp_stop_reason(self, stop_reason: str | None) -> None:
        if not self.entries or stop_reason is None:
            return
        last = self.entries[-1]
        last.stop_reason = stop_reason
        last.stopped = stop_reason not in (None, "completed")


def build_candidate_entry(
    prompt_or_payload: Any,
    score: float | None = None,
    id: str | None = None,
    metrics: dict[str, Any] | None = None,
    notes: str | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """
    Normalize a candidate payload for trials/rounds.

    Args:
        prompt_or_payload: ChatPrompt, dict of ChatPrompts, or other payload.
        score: Optional score to attach.
        id: Optional stable identifier.
        metrics: Optional metric dict.
        notes: Optional free-text notes.
        extra: Optional extra metadata (lineage, params, components, etc.).
    """
    # Normalize prompts to messages format to match baseline evaluation format
    # Single ChatPrompt -> dict[str, list[dict]] (wrap in dict with prompt name)
    # Dict of ChatPrompts -> dict[str, list[dict]] (prompt_name -> messages)
    normalized_candidate = prompt_or_payload
    if isinstance(prompt_or_payload, chat_prompt.ChatPrompt):
        # Single prompt: normalize to dict format with prompt name as key
        prompt_name = getattr(prompt_or_payload, "name", "prompt")
        normalized_candidate = {prompt_name: prompt_or_payload.get_messages()}
    elif isinstance(prompt_or_payload, dict):
        # Check if it's a dict of ChatPrompts
        first_value = (
            next(iter(prompt_or_payload.values())) if prompt_or_payload else None
        )
        if isinstance(first_value, chat_prompt.ChatPrompt):
            # Multi-prompt: normalize to dict[str, list[dict]]
            normalized_candidate = {
                k: p.get_messages() for k, p in prompt_or_payload.items()
            }
        # Otherwise, keep as-is (already a dict, not ChatPrompts)

    return OptimizerCandidate(
        candidate=normalized_candidate,
        id=id,
        score=score,
        metrics=metrics,
        notes=notes,
        extra=extra,
    ).to_dict()


class OptimizationResult(pydantic.BaseModel):
    """
    User-facing result object returned from optimize_prompt/optimize_parameter.

    The framework wraps AlgorithmResult into this model, adding metadata such as
    initial scores, model parameters, counters, and IDs. History is assumed to be
    pre-normalized by OptimizationHistoryState; OptimizationResult does not
    perform post-hoc reshaping beyond stamping schema versions.
    """

    schema_version: str = OPTIMIZATION_RESULT_SCHEMA_VERSION
    details_version: str = OPTIMIZATION_RESULT_SCHEMA_VERSION
    optimizer: str = "Optimizer"

    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
    score: float
    metric_name: str

    optimization_id: str | None = None
    dataset_id: str | None = None

    # Initial score
    initial_prompt: (
        chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt] | None
    ) = None
    initial_score: float | None = None

    details: dict[str, Any] = pydantic.Field(default_factory=dict)
    history: list[dict[str, Any]] = []
    llm_calls: int | None = None
    llm_calls_tools: int | None = None
    llm_cost_total: float | None = None
    llm_token_usage_total: dict[str, int] | None = None

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    def model_post_init(self, _context: Any) -> None:
        self.details = dict(self.details)
        # Fill counters from current history if not provided explicitly.
        trials_from_history = sum(
            len(round_state.get("trials", [])) for round_state in self.history
        )
        self.details.setdefault("trials_completed", trials_from_history)
        # Populate stop_reason_details if stop_reason is provided.
        if (
            self.details.get("stop_reason") is not None
            and self.details.get("stop_reason_details") is None
        ):
            stop_details: dict[str, Any] = {"best_score": self.score}
            if self.details.get("error"):
                # Avoid exposing raw error messages which may contain sensitive data.
                stop_details["error"] = (
                    "An error occurred during optimization; "
                    "see internal logs for details."
                )
            self.details["stop_reason_details"] = stop_details

    def get_run_link(self) -> str:
        """Get the URL to view this optimization run in the Opik dashboard."""
        return get_optimization_run_url_by_id(
            optimization_id=self.optimization_id, dataset_id=self.dataset_id
        )

    def model_dump(self, *kargs: Any, **kwargs: Any) -> dict[str, Any]:
        return super().model_dump(*kargs, **kwargs)

    def get_optimized_model_kwargs(self) -> dict[str, Any]:
        """
        Extract optimized model_kwargs for use in other optimizers.

        Returns:
            Dictionary of optimized model kwargs, empty dict if not available
        """
        return self.details.get("optimized_model_kwargs", {})

    def get_optimized_model(self) -> str | None:
        """
        Extract optimized model name.

        Returns:
            Model name string if available, None otherwise
        """
        return self.details.get("optimized_model")

    def get_optimized_parameters(self) -> dict[str, Any]:
        """
        Extract optimized parameter values.

        Returns:
            Dictionary of optimized parameters, empty dict if not available
        """
        return self.details.get("optimized_parameters", {})

    def _calculate_improvement_str(self) -> str:
        """Helper to calculate improvement percentage string."""
        initial_s = self.initial_score
        final_s = self.score

        # Check if initial score exists and is a number
        if not isinstance(initial_s, (int, float)):
            return "[dim]N/A (no initial score)[/dim]"

        # Proceed with calculation only if initial_s is valid
        if initial_s != 0:
            improvement_pct = (final_s - initial_s) / abs(initial_s)
            # Basic coloring for rich, plain for str
            color_start = ""
            color_end = ""
            if improvement_pct > 0:
                color_start, color_end = "[bold green]", "[/bold green]"
            elif improvement_pct < 0:
                color_start, color_end = "[bold red]", "[/bold red]"
            return f"{color_start}{improvement_pct:.2%}{color_end}"
        elif final_s > 0:
            return "[bold green]infinite[/bold green] (initial score was 0)"
        else:
            return "0.00% (no improvement from 0)"

    def _rounds_completed(self) -> int:
        """Return rounds completed based on details or history length."""
        rounds_completed = self.details.get("rounds_completed")
        if isinstance(rounds_completed, int):
            return rounds_completed
        return len(self.history)

    def _trials_completed(self) -> int | None:
        """Return trials completed from details when available."""
        trials_completed = self.details.get("trials_completed")
        if isinstance(trials_completed, int):
            return trials_completed
        return None

    def __str__(self) -> str:
        """Provides a clean, well-formatted plain-text summary."""
        return build_plaintext_summary(**self._plaintext_summary_kwargs())

    def _plaintext_improvement_str(self) -> str:
        """Return improvement string without rich markup."""
        return (
            self._calculate_improvement_str()
            .replace("[bold green]", "")
            .replace("[/bold green]", "")
            .replace("[bold red]", "")
            .replace("[/bold red]", "")
            .replace("[dim]", "")
            .replace("[/dim]", "")
        )

    def _plaintext_summary_kwargs(self) -> dict[str, Any]:
        """Return the payload used for the plaintext summary formatter."""
        trials_completed = self._trials_completed()
        improvement_str = self._plaintext_improvement_str()
        model_name = self._model_name_with_temperature()
        optimized_params = self.details.get("optimized_parameters") or {}
        parameter_importance = self.details.get("parameter_importance") or {}
        search_ranges = self.details.get("search_ranges") or {}
        precision = self.details.get("parameter_precision", 6)
        try:
            final_prompt_display = format_prompt_for_plaintext(self.prompt)
        except Exception:
            final_prompt_display = str(self.prompt)

        return {
            "optimizer": self.optimizer,
            "model_name": model_name,
            "metric_name": self.metric_name,
            "initial_score": self.initial_score,
            "final_score": self.score,
            "improvement_str": improvement_str,
            "trials_completed": trials_completed,
            "rounds_ran": self._rounds_completed(),
            "optimized_params": optimized_params,
            "parameter_importance": parameter_importance,
            "search_ranges": search_ranges,
            "parameter_precision": precision,
            "final_prompt_display": final_prompt_display,
        }

    def __rich__(self) -> rich.panel.Panel:
        """Provides a rich, formatted output for terminals supporting Rich."""

        return render_rich_result(self)

    def display(self) -> None:
        """
        Display the OptimizationResult using rich formatting.

        Shows the optimization result with rich formatting and includes
        a link to view the run in the Opik dashboard if available.
        """
        console = get_console()
        console.print(self)

    def _model_name_with_temperature(self) -> str:
        model_name = self.details.get("model", "N/A")
        temperature = self.details.get("temperature")
        if temperature is not None:
            return f"{model_name} (Temp: {temperature})"
        return model_name
