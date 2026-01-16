"""Module containing the OptimizationResult class."""

from __future__ import annotations

from typing import Any
from collections.abc import Iterator
import datetime
from dataclasses import dataclass, field
from contextlib import contextmanager

import pydantic
import rich.box
import rich.console
import rich.panel
import rich.table
import rich.text

from .utils.reporting import (
    get_console,
    get_optimization_run_url_by_id,
)
from .utils.display import (
    format_prompt_for_plaintext,
    build_plaintext_summary,
)

from .api_objects import chat_prompt
from .constants import OPTIMIZATION_RESULT_SCHEMA_VERSION


# FIXME: Move to helpers.py
def _now_iso() -> str:
    return (
        datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    )


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
    extras: dict[str, Any] | None = None
    timestamp: str = field(default_factory=_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "trial_index": self.trial_index,
            "score": self.score,
            "candidate": self.candidate,
            "metrics": self.metrics,
            "dataset": self.dataset,
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
    best_prompt: Any | None = None
    candidates: list[dict[str, Any]] | None = None
    generated_prompts: list[dict[str, Any]] | None = None
    stop_reason: str | None = None
    extras: dict[str, Any] | None = None
    timestamp: str = field(default_factory=_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "round_index": self.round_index,
            "trials": [t.to_dict() for t in self.trials],
            "best_score": self.best_score,
            "best_prompt": self.best_prompt,
            "candidates": self.candidates,
            "generated_prompts": self.generated_prompts,
            "stop_reason": self.stop_reason,
            "extra": self.extras,
            "timestamp": self.timestamp,
        }


class OptimizationHistoryState:
    """
    Stateful history manager with explicit start/record/end lifecycle.

    Captures running best scores and timestamps so optimizers can append
    consistent history without post-hoc normalization. It merges entries for
    the same round_index, coerces floats, and stamps timestamps/best_so_far.
    BaseOptimizer later consumes entries directly when constructing the final
    OptimizationResult.
    """

    def __init__(self) -> None:
        self.entries: list[dict[str, Any]] = []
        self._best_so_far: float | None = None
        self._open_rounds: dict[Any, dict[str, Any]] = {}
        self._default_dataset_split: str | None = None
        self._current_selection_meta: dict[str, Any] | None = None
        self._current_pareto_front: list[dict[str, Any]] | None = None

    @staticmethod
    def _coerce_float(value: Any) -> float | None:
        try:
            return float(value)
        except Exception:
            return None

    def _next_candidate_id(self, prefix: str | None = None) -> str:
        """Generate a simple incremental candidate ID, optionally with a prefix."""
        base = len(self.entries)
        suffix = sum(len(e.get("trials") or []) for e in self.entries)
        stem = prefix or "cand"
        return f"{stem}_{base}_{suffix}"

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
                normalized.append({"candidate": candidate})
                continue

            payload = dict(candidate)
            candidate_id = payload.pop("id", None)
            prompt_payload = payload.pop("prompt", payload.pop("prompts", None))
            if prompt_payload is None and "candidate" in payload:
                prompt_payload = payload.pop("candidate")
            score = payload.pop("score", None)
            metrics = payload.pop("metrics", None)
            notes = payload.pop("notes", payload.pop("reason", None))
            extra = payload.pop("extra", None)

            if payload:
                extra = {**(extra or {}), **payload}

            normalized.append(
                {
                    "id": candidate_id,
                    "candidate": prompt_payload,
                    "score": score,
                    "metrics": metrics,
                    "notes": notes,
                    "extra": extra,
                }
            )

        return normalized

    def _merge_round(self, entry: dict[str, Any]) -> None:
        """
        Merge a new entry into the existing entries when round_index matches.

        This keeps trials and candidates grouped per round while preserving
        the most recent best_score/prompt metadata.
        """
        round_idx = entry.get("round_index")
        if round_idx is None:
            self.entries.append(entry)
            return

        for existing in self.entries:
            if existing.get("round_index") == round_idx:
                # Merge trials
                trials_new = entry.get("trials") or []
                trials_existing = existing.get("trials") or []
                existing["trials"] = trials_existing + trials_new

                # Merge candidates
                candidates_new = entry.get("candidates") or []
                candidates_existing = existing.get("candidates") or []
                existing["candidates"] = candidates_existing + candidates_new

                # Prefer newer best_score/best_prompt if present
                if entry.get("best_score") is not None:
                    existing["best_score"] = entry.get("best_score")
                if entry.get("best_prompt") is not None:
                    existing["best_prompt"] = entry.get("best_prompt")
                if entry.get("best_so_far") is not None:
                    existing["best_so_far"] = entry.get("best_so_far")

                # Merge extras shallowly
                if entry.get("extra"):
                    existing["extra"] = {
                        **(existing.get("extra") or {}),
                        **entry["extra"],
                    }

                # Update stop_reason/stopped if provided
                if entry.get("stop_reason") is not None:
                    existing["stop_reason"] = entry.get("stop_reason")
                    existing["stopped"] = entry.get("stop_reason") not in (
                        None,
                        "completed",
                    )
                return

        # No existing round, append new
        self.entries.append(entry)

    def start_round(
        self,
        round_index: int | None = None,
        extras: dict[str, Any] | None = None,
        timestamp: str | None = None,
    ) -> Any:
        idx = int(round_index) if round_index is not None else len(self.entries)
        entry: dict[str, Any] = {
            "round_index": idx,
            "trials": [],
            "candidates": [],
            "extra": extras or {},
            "timestamp": timestamp or _now_iso(),
        }
        self._open_rounds[idx] = entry
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
        entry = self._open_rounds.setdefault(
            round_handle,
            {
                "round_index": round_handle,
                "trials": [],
                "candidates": [],
                "extra": {},
            },
        )
        score_val = self._coerce_float(score) if score is not None else None
        candidate_payload_raw: Any = (
            candidate.to_dict()
            if isinstance(candidate, OptimizerCandidate)
            else candidate
        )
        candidate_payload: Any
        if isinstance(candidate_payload_raw, dict):
            # Work on a shallow copy to avoid mutating source prompt dicts.
            candidate_payload = dict(candidate_payload_raw)
        else:
            candidate_payload = candidate_payload_raw
        dataset_split_val = dataset_split or self._default_dataset_split
        trial_payload: dict[str, Any] = {
            "trial_index": int(trial_index) if isinstance(trial_index, int) else None,
            "score": score_val,
            "candidate": candidate_payload,
            "metrics": metrics,
            "dataset": dataset,
            "dataset_split": dataset_split_val,
            "extra": extras,
            "timestamp": timestamp or _now_iso(),
        }
        candidate_id: str | None = None
        if trial_payload["trial_index"] is None:
            # Auto-assign a trial index if one wasn't provided.
            trial_payload["trial_index"] = sum(
                len(e.get("trials") or []) for e in self.entries
            ) + len(entry.get("trials") or [])
        if isinstance(candidate_payload, dict):
            candidate_id = candidate_payload.get("id")
        # TODO: Align history schema and downstream consumers with candidate_id
        # stored on trials (not injected into candidate payloads).
        if candidate_id is None and candidate_id_prefix is not None:
            candidate_id = self._next_candidate_id(candidate_id_prefix)
        if candidate_id is not None:
            trial_payload["candidate_id"] = candidate_id
        entry.setdefault("trials", []).append(trial_payload)
        if score_val is not None:
            self._best_so_far = (
                score_val
                if self._best_so_far is None
                else max(self._best_so_far, score_val)
            )
            entry["best_so_far"] = self._best_so_far
        if candidates:
            normalized_candidates = self._normalize_candidates(candidates) or []
            entry.setdefault("candidates", []).extend(normalized_candidates)
        if stop_reason is not None:
            entry["stop_reason"] = stop_reason
            entry["stopped"] = stop_reason not in (None, "completed")
        else:
            entry.setdefault("stop_reason", None)
            entry.setdefault("stopped", False)
        return trial_payload

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
            round_handle,
            {
                "round_index": round_handle,
                "trials": [],
                "candidates": [],
                "extra": {},
            },
        )
        dataset_split_val = dataset_split or self._default_dataset_split
        if candidates:
            normalized_candidates = self._normalize_candidates(candidates) or []
            entry.setdefault("candidates", []).extend(normalized_candidates)
        if best_score is not None and entry.get("best_score") is None:
            best_score_val = self._coerce_float(best_score)
            entry["best_score"] = best_score_val
            if best_score_val is not None:
                self._best_so_far = (
                    best_score_val
                    if self._best_so_far is None
                    else max(self._best_so_far, best_score_val)
                )
        if best_candidate is not None:
            entry["best_candidate"] = (
                best_candidate.to_dict()
                if isinstance(best_candidate, OptimizerCandidate)
                else best_candidate
            )
        if best_prompt is not None:
            entry["best_prompt"] = best_prompt
        if self._best_so_far is not None:
            entry["best_so_far"] = self._best_so_far
        if extras:
            entry["extra"] = {**(entry.get("extra") or {}), **extras}
        if dataset_split_val is not None:
            entry["dataset_split"] = dataset_split_val

        # FIXME: Move to pareto_front utils
        pareto_payload = pareto_front
        if pareto_payload is None:
            pareto_payload = self._current_pareto_front
        if pareto_payload is not None:
            entry.setdefault("extra", {})
            entry["extra"]["pareto_front"] = pareto_payload
        selection_meta_payload = (
            selection_meta
            if selection_meta is not None
            else self._current_selection_meta
        )
        if selection_meta_payload is not None:
            entry.setdefault("extra", {})
            entry["extra"]["selection_meta"] = selection_meta_payload
        if stop_reason is None and entry.get("stop_reason") is not None:
            stop_reason = entry.get("stop_reason")
        entry["stop_reason"] = stop_reason
        entry["timestamp"] = timestamp or entry.get("timestamp") or _now_iso()
        self._merge_round(entry)
        # Reset round-scoped selection meta once consumed
        self._current_selection_meta = None
        self._current_pareto_front = None
        return entry

    def finalize_stop(self, stop_reason: str | None = None) -> None:
        self._stamp_stop_reason(stop_reason)

    def get_entries(self) -> list[dict[str, Any]]:
        return list(self.entries)

    def clear(self) -> None:
        self.entries.clear()
        self._best_so_far = None
        self._open_rounds.clear()
        self._default_dataset_split = None
        self._current_selection_meta = None

    def _stamp_stop_reason(self, stop_reason: str | None) -> None:
        if not self.entries or stop_reason is None:
            return
        last = self.entries[-1]
        last["stop_reason"] = stop_reason
        last["stopped"] = stop_reason not in (None, "completed")


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
    return OptimizerCandidate(
        candidate=prompt_or_payload,
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
        self.details.setdefault("schema_version", OPTIMIZATION_RESULT_SCHEMA_VERSION)
        # Fill counters from current history if not provided explicitly.
        self.details.setdefault("trials_completed", len(self.history))
        self.details.setdefault("rounds_completed", len(self.history))
        # Populate stop_reason_details if stop_reason is provided.
        if (
            self.details.get("stop_reason") is not None
            and self.details.get("stop_reason_details") is None
        ):
            stop_details: dict[str, Any] = {"best_score": self.score}
            if self.details.get("error"):
                stop_details["error"] = str(self.details["error"])
            self.details["stop_reason_details"] = stop_details

    # FIXME: Move to display/reporting utils
    def get_run_link(self) -> str:
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

    def __str__(self) -> str:
        """Provides a clean, well-formatted plain-text summary."""
        rounds_ran = (
            self.details.get("rounds_completed")
            if isinstance(self.details.get("rounds_completed"), int)
            else len(self.history)
        )
        trials_completed = self.details.get("trials_completed")
        improvement_str = (
            self._calculate_improvement_str()
            .replace("[bold green]", "")
            .replace("[/bold green]", "")
            .replace("[bold red]", "")
            .replace("[/bold red]", "")
            .replace("[dim]", "")
            .replace("[/dim]", "")
        )
        model_name = self.details.get("model", "N/A")
        optimized_params = self.details.get("optimized_parameters") or {}
        parameter_importance = self.details.get("parameter_importance") or {}
        search_ranges = self.details.get("search_ranges") or {}
        precision = self.details.get("parameter_precision", 6)
        try:
            final_prompt_display = format_prompt_for_plaintext(self.prompt)
        except Exception:
            final_prompt_display = str(self.prompt)

        # FIXME: Pass context/history or something to pull everything to AVOID use of this huge args passing.
        return build_plaintext_summary(
            optimizer=self.optimizer,
            model_name=f"{model_name} (Temp: {self.details.get('temperature')})"
            if self.details.get("temperature") is not None
            else model_name,
            metric_name=self.metric_name,
            initial_score=self.initial_score,
            final_score=self.score,
            improvement_str=improvement_str,
            trials_completed=trials_completed
            if isinstance(trials_completed, int)
            else None,
            rounds_ran=int(rounds_ran)
            if isinstance(rounds_ran, int)
            else len(self.history),
            optimized_params=optimized_params,
            parameter_importance=parameter_importance,
            search_ranges=search_ranges,
            parameter_precision=precision,
            final_prompt_display=final_prompt_display,
        )

    def __rich__(self) -> rich.panel.Panel:
        """Provides a rich, formatted output for terminals supporting Rich."""
        from .utils.display import render_rich_result

        return render_rich_result(self)

    def display(self) -> None:
        """
        Displays the OptimizationResult using rich formatting
        """
        console = get_console()
        console.print(self)
        # Gracefully handle cases where optimization tracking isn't available
        if self.dataset_id and self.optimization_id:
            try:
                print("Optimization run link:", self.get_run_link())
            except Exception:
                print("Optimization run link: No optimization run link available")
        else:
            print("Optimization run link: No optimization run link available")
