"""Module containing the OptimizationResult class."""

from __future__ import annotations

from typing import Any, cast
import datetime
from dataclasses import dataclass, field

import pydantic
import rich.box
import rich.console
import rich.panel
import rich.table
import rich.text

from .reporting_utils import (
    _format_message_content,
    format_prompt_snippet,
    get_console,
    get_link_text,
    get_optimization_run_url_by_id,
)

from .api_objects import chat_prompt
from .constants import OPTIMIZATION_RESULT_SCHEMA_VERSION


def _format_float(value: Any, digits: int = 6) -> str:
    """Format float values with specified precision."""
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def _format_prompt_for_plaintext(
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Format a prompt (single or dict) for plain text display."""
    if isinstance(prompt, dict):
        prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
        parts = []
        for key, chat_p in prompt_dict.items():
            parts.append(f"[{key}]")
            for msg in chat_p.get_messages():
                role = msg.get("role", "unknown")
                content = msg.get("content", "")
                # Handle both string and multimodal content
                if isinstance(content, str):
                    snippet = format_prompt_snippet(content, max_length=150)
                else:
                    snippet = "[multimodal content]"
                parts.append(f"  {role}: {snippet}")
        return "\n".join(parts)
    else:
        # Single ChatPrompt
        parts = []
        for msg in prompt.get_messages():
            role = msg.get("role", "unknown")
            content = msg.get("content", "")
            if isinstance(content, str):
                snippet = format_prompt_snippet(content, max_length=150)
            else:
                snippet = "[multimodal content]"
            parts.append(f"  {role}: {snippet}")
        return "\n".join(parts)


DETAILS_SCHEMA_VERSION = "1"


def _resolve_rounds_ran(details: dict[str, Any]) -> int:
    """Resolve a reasonable rounds-completed count from optimizer details."""
    rounds = details.get("rounds")
    if isinstance(rounds, list):
        return len(rounds)
    iterations_completed = details.get("iterations_completed")
    if isinstance(iterations_completed, int):
        return iterations_completed
    total_rounds = details.get("total_rounds")
    if isinstance(total_rounds, int):
        return total_rounds
    return 0


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
    prompt: Any
    metrics: dict[str, Any] | None = None
    dataset: str | None = None
    extras: dict[str, Any] | None = None
    timestamp: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "trial_index": self.trial_index,
            "score": self.score,
            "prompt": self.prompt,
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

    Optimizers are encouraged to emit rounds through OptimizationHistoryBuilder
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
    timestamp: str | None = None

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


class OptimizationHistoryBuilder:
    """
    Stateful builder for optimization history entries.

    Captures running best scores and timestamps so optimizers can append
    consistent history without post-hoc normalization. The builder is the
    single ingestion point for history: optimizers append dicts or structured
    OptimizationRound/OptimizationTrial instances, and the builder handles
    merging entries for the same round_index, coercing floats, and stamping
    timestamps/best_so_far. BaseOptimizer later consumes builder entries
    directly when constructing the final OptimizationResult.

    TODO(opik_optimizer/#history-state): extend to richer state management
    (fingerprints, streaming state, resumability) once consumers are ready.
    """

    def __init__(
        self, schema_version: str = OPTIMIZATION_RESULT_SCHEMA_VERSION
    ) -> None:
        self.schema_version = schema_version
        self.entries: list[dict[str, Any]] = []
        self._best_so_far: float | None = None

    @staticmethod
    def _now_iso() -> str:
        return (
            datetime.datetime.now(datetime.timezone.utc)
            .isoformat()
            .replace("+00:00", "Z")
        )

    @staticmethod
    def _coerce_float(value: Any) -> float | None:
        try:
            return float(value)
        except Exception:
            return None

    def _normalize_candidates(
        self, candidates: list[dict[str, Any]] | None
    ) -> list[dict[str, Any]] | None:
        if not candidates:
            return None

        normalized: list[dict[str, Any]] = []
        for candidate in candidates:
            if not isinstance(candidate, dict):
                normalized.append({"prompt": candidate})
                continue

            payload = dict(candidate)
            candidate_id = payload.pop("id", None)
            prompt_payload = payload.pop("prompt", payload.pop("prompts", None))
            score = payload.pop("score", None)
            metrics = payload.pop("metrics", None)
            notes = payload.pop("notes", payload.pop("reason", None))
            tools = payload.pop("tools", None)
            extra = payload.pop("extra", None)

            if payload:
                extra = {**(extra or {}), **payload}

            normalized.append(
                {
                    "id": candidate_id,
                    "prompt": prompt_payload,
                    "score": score,
                    "metrics": metrics,
                    "notes": notes,
                    "tools": tools,
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

    def append(
        self,
        *,
        round_index: int,
        trial_index: int | None,
        score: float | None,
        best_score: float | None,
        best_prompt: Any | None = None,
        candidates: list[dict[str, Any]] | None = None,
        trials: list[dict[str, Any]] | None = None,
        improvement: float | None = None,
        extras: dict[str, Any] | None = None,
        stop_reason: str | None = None,
        best_so_far: float | None = None,
        timestamp: str | None = None,
    ) -> None:
        best_score_val = self._coerce_float(best_score)
        score_val = self._coerce_float(score) if score is not None else best_score_val

        if best_so_far is not None:
            self._best_so_far = self._coerce_float(best_so_far)
        elif best_score_val is not None:
            self._best_so_far = (
                best_score_val
                if self._best_so_far is None
                else max(self._best_so_far, best_score_val)
            )

        entry: dict[str, Any] = {
            "round_index": int(round_index),
            "trial_index": int(trial_index) if isinstance(trial_index, int) else None,
            "score": score_val,
            "best_score": best_score_val if best_score_val is not None else score_val,
            "best_so_far": self._best_so_far,
            "best_prompt": best_prompt,
            "candidates": self._normalize_candidates(candidates),
            "trials": trials,
            "improvement": improvement,
            "timestamp": timestamp or self._now_iso(),
            "stop_reason": stop_reason,
            "stopped": stop_reason not in (None, "completed"),
            "extra": extras,
        }
        self._merge_round(entry)

    def append_trial(
        self,
        *,
        round_index: int,
        trial_index: int | None,
        score: float | None,
        prompt: Any | None = None,
        metrics: dict[str, Any] | None = None,
        dataset: str | None = None,
        trial_extras: dict[str, Any] | None = None,
        round_extras: dict[str, Any] | None = None,
        stop_reason: str | None = None,
        timestamp: str | None = None,
    ) -> dict[str, Any]:
        """
        Convenience helper to record a single trial into history.

        Returns the normalized entry dict that was appended.
        """
        trial_payload = OptimizationTrial(
            trial_index=trial_index,
            score=score,
            prompt=prompt,
            metrics=metrics,
            dataset=dataset,
            extras=trial_extras,
            timestamp=timestamp or self._now_iso(),
        ).to_dict()

        entry = {
            "round_index": round_index,
            "trial_index": trial_index,
            "trials": [trial_payload],
            "score": score,
            "best_score": score,
            "best_prompt": prompt,
            "extra": round_extras,
            "timestamp": trial_payload.get("timestamp"),
            "stop_reason": stop_reason,
        }
        self.append_entry(entry)
        return entry

    def append_entry(self, entry: Any) -> None:
        """
        Append an existing entry (dict or structured round/trial), normalizing
        to the builder schema without performing post-hoc history fixes.
        """
        if isinstance(entry, OptimizationRound):
            payload = entry.to_dict()
        elif isinstance(entry, OptimizationTrial):
            payload = entry.to_dict()
        elif isinstance(entry, dict):
            payload = dict(entry)
        else:
            return

        extra = payload.get("extra") or {}
        dataset_split = payload.pop("dataset_split", None)
        if dataset_split is not None:
            extra = {**extra, "dataset_split": dataset_split}

        generated = payload.get("generated_prompts")
        candidates = payload.get("candidates") or generated
        trials_payload = payload.get("trials")
        if trials_payload:
            normalized_trials: list[dict[str, Any]] = []
            for trial in trials_payload:
                if isinstance(trial, OptimizationTrial):
                    normalized_trials.append(trial.to_dict())
                elif isinstance(trial, dict):
                    normalized_trials.append(
                        {
                            "trial_index": trial.get("trial_index"),
                            "score": self._coerce_float(trial.get("score")),
                            "prompt": trial.get("prompt"),
                            "metrics": trial.get("metrics"),
                            "dataset": trial.get("dataset"),
                            "extra": trial.get("extra") or trial.get("extras"),
                            "timestamp": trial.get("timestamp"),
                        }
                    )
            trials_payload = normalized_trials or None

        self.append(
            round_index=int(payload.get("round_index", len(self.entries))),
            trial_index=payload.get("trial_index"),
            score=payload.get("current_score") or payload.get("score"),
            best_score=payload.get("best_score") or payload.get("current_score"),
            best_so_far=payload.get("best_so_far"),
            best_prompt=payload.get("best_prompt") or payload.get("current_prompt"),
            candidates=candidates if isinstance(candidates, list) else None,
            trials=trials_payload if isinstance(trials_payload, list) else None,
            improvement=payload.get("improvement"),
            extras=extra if extra else None,
            stop_reason=payload.get("stop_reason"),
            timestamp=payload.get("timestamp"),
        )

    def get_entries(self) -> list[dict[str, Any]]:
        return list(self.entries)

    def clear(self) -> None:
        self.entries.clear()
        self._best_so_far = None


class OptimizationResult(pydantic.BaseModel):
    """
    User-facing result object returned from optimize_prompt/optimize_parameter.

    The framework wraps AlgorithmResult into this model, adding metadata such as
    initial scores, model parameters, counters, and IDs. History is assumed to be
    pre-normalized by OptimizationHistoryBuilder; OptimizationResult does not
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
        # Minimal normalization: ensure schema version markers are present.
        self.details = dict(self.details)
        self.details.setdefault("schema_version", DETAILS_SCHEMA_VERSION)
        self.details["history_version"] = self.schema_version
        # Maintain backward-compatible counters when not provided.
        if "trials_completed" not in self.details:
            rounds_field = self.details.get("rounds")
            if isinstance(rounds_field, list):
                self.details["trials_completed"] = len(rounds_field)
                self.details.setdefault("rounds_completed", len(rounds_field))
            elif isinstance(self.details.get("trials_used"), int):
                self.details["trials_completed"] = self.details["trials_used"]
        if "rounds_completed" not in self.details:
            if isinstance(self.details.get("iterations_completed"), int):
                self.details["rounds_completed"] = self.details["iterations_completed"]
            elif isinstance(self.details.get("trials_completed"), int):
                self.details["rounds_completed"] = self.details["trials_completed"]
        # Populate stop_reason_details if stop_reason is provided.
        if (
            self.details.get("stop_reason") is not None
            and self.details.get("stop_reason_details") is None
        ):
            stop_details: dict[str, Any] = {"best_score": self.score}
            if self.details.get("error"):
                stop_details["error"] = str(self.details["error"])
            self.details["stop_reason_details"] = stop_details

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
        separator = "=" * 80
        rounds_ran = _resolve_rounds_ran(self.details)
        trials_completed = self.details.get("trials_completed")
        initial_score = self.initial_score
        initial_score_str = (
            f"{initial_score:.4f}" if isinstance(initial_score, (int, float)) else "N/A"
        )
        final_score_str = f"{self.score:.4f}"
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
        temp = self.details.get("temperature")
        temp_str = f"{temp:.1f}" if isinstance(temp, (int, float)) else "N/A"

        try:
            final_prompt_display = _format_prompt_for_plaintext(self.prompt)
        except Exception:
            final_prompt_display = str(self.prompt)

        output = [
            f"\n{separator}",
            "OPTIMIZATION COMPLETE",
            f"{separator}",
            f"Optimizer:        {self.optimizer}",
            f"Model Used:       {model_name} (Temp: {temp_str})",
            f"Metric Evaluated: {self.metric_name}",
            f"Initial Score:    {initial_score_str}",
            f"Final Best Score: {final_score_str}",
            f"Total Improvement:{improvement_str.rjust(max(0, 18 - len('Total Improvement:')))}",
            f"Trials Completed: {trials_completed if isinstance(trials_completed, int) else rounds_ran}",
        ]

        optimized_params = self.details.get("optimized_parameters") or {}
        parameter_importance = self.details.get("parameter_importance") or {}
        search_ranges = self.details.get("search_ranges") or {}
        precision = self.details.get("parameter_precision", 6)

        if optimized_params:

            def _format_range(desc: dict[str, Any]) -> str:
                if "min" in desc and "max" in desc:
                    step_str = (
                        f", step={_format_float(desc['step'], precision)}"
                        if desc.get("step") is not None
                        else ""
                    )
                    return f"[{_format_float(desc['min'], precision)}, {_format_float(desc['max'], precision)}{step_str}]"
                if desc.get("choices"):
                    return f"choices={desc['choices']}"
                return str(desc)

            rows = []
            stage_order = [
                record.get("stage")
                for record in self.details.get("search_stages", [])
                if record.get("stage") in search_ranges
            ]
            if not stage_order:
                stage_order = sorted(search_ranges)

            for name in sorted(optimized_params):
                contribution = parameter_importance.get(name)
                stage_ranges = []
                for stage in stage_order:
                    params = search_ranges.get(stage) or {}
                    if name in params:
                        stage_ranges.append(f"{stage}: {_format_range(params[name])}")
                if not stage_ranges:
                    for stage, params in search_ranges.items():
                        if name in params:
                            stage_ranges.append(
                                f"{stage}: {_format_range(params[name])}"
                            )
                joined_ranges = "\n".join(stage_ranges) if stage_ranges else "N/A"
                rows.append(
                    {
                        "parameter": name,
                        "value": optimized_params[name],
                        "contribution": contribution,
                        "ranges": joined_ranges,
                    }
                )

            if rows:
                output.append("Parameter Summary:")
                # Compute overall improvement fraction for gain calculation
                total_improvement = None
                if isinstance(self.initial_score, (int, float)) and isinstance(
                    self.score, (int, float)
                ):
                    if self.initial_score != 0:
                        total_improvement = (self.score - self.initial_score) / abs(
                            self.initial_score
                        )
                    else:
                        total_improvement = self.score
                for row in rows:
                    value_str = _format_float(row["value"], precision)
                    contrib_val = row["contribution"]
                    if contrib_val is not None:
                        contrib_percent = contrib_val * 100
                        gain_str = ""
                        if total_improvement is not None:
                            gain_value = contrib_val * total_improvement * 100
                            gain_str = f" ({gain_value:+.2f}%)"
                        contrib_str = f"{contrib_percent:.1f}%{gain_str}"
                    else:
                        contrib_str = "N/A"
                    output.append(
                        f"- {row['parameter']}: value={value_str}, contribution={contrib_str}, ranges=\n  {row['ranges']}"
                    )

        output.extend(
            [
                "\nFINAL OPTIMIZED PROMPT / STRUCTURE:",
                "--------------------------------------------------------------------------------",
                f"{final_prompt_display}",
                "--------------------------------------------------------------------------------",
                f"{separator}",
            ]
        )
        return "\n".join(output)

    def __rich__(self) -> rich.panel.Panel:
        """Provides a rich, formatted output for terminals supporting Rich."""
        improvement_str = self._calculate_improvement_str()
        rounds_ran = _resolve_rounds_ran(self.details)
        trials_completed = self.details.get("trials_completed")
        initial_score = self.initial_score
        initial_score_str = (
            f"{initial_score:.4f}"
            if isinstance(initial_score, (int, float))
            else "[dim]N/A[/dim]"
        )
        final_score_str = f"{self.score:.4f}"

        model_name = self.details.get("model", "[dim]N/A[/dim]")

        table = rich.table.Table.grid(padding=(0, 1))
        table.add_column(style="dim")
        table.add_column()

        table.add_row(
            "Optimizer:",
            f"[bold]{self.optimizer}[/bold]",
        )
        table.add_row("Model Used:", f"{model_name}")
        table.add_row("Metric Evaluated:", f"[bold]{self.metric_name}[/bold]")
        table.add_row("Initial Score:", initial_score_str)
        table.add_row("Final Best Score:", f"[bold cyan]{final_score_str}[/bold cyan]")
        table.add_row("Total Improvement:", improvement_str)
        display_trials = (
            str(trials_completed)
            if isinstance(trials_completed, int)
            else str(rounds_ran)
        )
        table.add_row("Trials Completed:", display_trials)
        table.add_row(
            "Optimization run link:",
            get_link_text(
                pre_text="",
                link_text="Open in Opik Dashboard",
                dataset_id=self.dataset_id,
                optimization_id=self.optimization_id,
            ),
        )

        optimized_params = self.details.get("optimized_parameters") or {}
        parameter_importance = self.details.get("parameter_importance") or {}
        search_ranges = self.details.get("search_ranges") or {}
        precision = self.details.get("parameter_precision", 6)

        # Display Chat Structure if available
        panel_title = "[bold]Final Optimized Prompt[/bold]"
        try:
            chat_group_items: list[rich.console.RenderableType] = []

            # Handle both single ChatPrompt and dict of ChatPrompts
            if isinstance(self.prompt, dict):
                # Dictionary of prompts
                prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], self.prompt)
                for key, chat_p in prompt_dict.items():
                    # Add key header
                    key_header = rich.text.Text(f"[{key}]", style="bold yellow")
                    chat_group_items.append(key_header)
                    chat_group_items.append(rich.text.Text("---", style="dim"))

                    # Add messages for this prompt
                    for msg in chat_p.get_messages():
                        role = msg.get("role", "unknown")
                        content = msg.get("content", "")
                        role_style = (
                            "bold green"
                            if role == "user"
                            else (
                                "bold blue"
                                if role == "assistant"
                                else ("bold magenta" if role == "system" else "")
                            )
                        )
                        formatted_content = _format_message_content(content)
                        role_text = rich.text.Text(
                            f"{role.capitalize()}:", style=role_style
                        )
                        chat_group_items.append(
                            rich.console.Group(role_text, formatted_content)
                        )
                        chat_group_items.append(rich.text.Text("---", style="dim"))
                    chat_group_items.append(
                        rich.text.Text("")
                    )  # Extra space between prompts
            else:
                # Single ChatPrompt
                for msg in self.prompt.get_messages():
                    role = msg.get("role", "unknown")
                    content = msg.get("content", "")
                    role_style = (
                        "bold green"
                        if role == "user"
                        else (
                            "bold blue"
                            if role == "assistant"
                            else ("bold magenta" if role == "system" else "")
                        )
                    )
                    formatted_content = _format_message_content(content)
                    role_text = rich.text.Text(
                        f"{role.capitalize()}:", style=role_style
                    )
                    chat_group_items.append(
                        rich.console.Group(role_text, formatted_content)
                    )
                    chat_group_items.append(rich.text.Text("---", style="dim"))

            prompt_renderable: rich.console.RenderableType = rich.console.Group(
                *chat_group_items
            )

        except Exception:
            # Fallback to simple text prompt
            prompt_renderable = rich.text.Text(str(self.prompt or ""), overflow="fold")
            panel_title = "[bold]Final Optimized Prompt (Instruction - fallback)[/bold]"

        prompt_panel = rich.panel.Panel(
            prompt_renderable, title=panel_title, border_style="blue", padding=(1, 2)
        )

        renderables: list[rich.console.RenderableType] = [table, "\n"]

        if optimized_params:
            summary_table = rich.table.Table(
                title="Parameter Summary", show_header=True, title_style="bold"
            )
            summary_table.add_column("Parameter", justify="left", style="cyan")
            summary_table.add_column("Value", justify="left")
            summary_table.add_column("Importance", justify="left", style="magenta")
            summary_table.add_column("Gain", justify="left", style="dim")
            summary_table.add_column("Ranges", justify="left")

            stage_order = [
                record.get("stage")
                for record in self.details.get("search_stages", [])
                if record.get("stage") in search_ranges
            ]
            if not stage_order:
                stage_order = sorted(search_ranges)

            def _format_range(desc: dict[str, Any]) -> str:
                if "min" in desc and "max" in desc:
                    step_str = (
                        f", step={_format_float(desc['step'], precision)}"
                        if desc.get("step") is not None
                        else ""
                    )
                    return f"[{_format_float(desc['min'], precision)}, {_format_float(desc['max'], precision)}{step_str}]"
                if desc.get("choices"):
                    return ",".join(map(str, desc["choices"]))
                return str(desc)

            total_improvement = None
            if isinstance(self.initial_score, (int, float)) and isinstance(
                self.score, (int, float)
            ):
                if self.initial_score != 0:
                    total_improvement = (self.score - self.initial_score) / abs(
                        self.initial_score
                    )
                else:
                    total_improvement = self.score

            for name in sorted(optimized_params):
                value_str = _format_float(optimized_params[name], precision)
                contrib_val = parameter_importance.get(name)
                if contrib_val is not None:
                    contrib_str = f"{contrib_val:.1%}"
                    gain_str = (
                        f"{contrib_val * total_improvement:+.2%}"
                        if total_improvement is not None
                        else "N/A"
                    )
                else:
                    contrib_str = "N/A"
                    gain_str = "N/A"
                ranges_parts = []
                for stage in stage_order:
                    params = search_ranges.get(stage) or {}
                    if name in params:
                        ranges_parts.append(f"{stage}: {_format_range(params[name])}")
                if not ranges_parts:
                    for stage, params in search_ranges.items():
                        if name in params:
                            ranges_parts.append(
                                f"{stage}: {_format_range(params[name])}"
                            )

                summary_table.add_row(
                    name,
                    value_str,
                    contrib_str,
                    gain_str,
                    "\n".join(ranges_parts) if ranges_parts else "N/A",
                )

            renderables.extend([summary_table, "\n"])

        renderables.append(prompt_panel)

        content_group = rich.console.Group(*renderables)

        return rich.panel.Panel(
            content_group,
            title="[bold yellow]Optimization Complete[/bold yellow]",
            border_style="yellow",
            box=rich.box.DOUBLE_EDGE,
            padding=1,
        )

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
