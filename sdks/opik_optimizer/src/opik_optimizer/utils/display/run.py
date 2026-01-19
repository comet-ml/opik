"""Run-level display orchestration for optimization flows."""

from __future__ import annotations

import math
from typing import Any, ContextManager, Protocol, TYPE_CHECKING

from ...api_objects import chat_prompt

if TYPE_CHECKING:
    from ...core.state import OptimizationContext
from .format import summarize_selection_policy
from . import terminal as display_terminal


class RunDisplay(Protocol):
    """Protocol for run-level display handlers."""

    def show_header(
        self,
        *,
        algorithm: str,
        optimization_id: str | None,
        dataset_id: str | None = None,
    ) -> None: ...

    def show_configuration(
        self,
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        optimizer_config: dict[str, Any],
    ) -> None: ...

    def baseline_evaluation(
        self, context: OptimizationContext
    ) -> ContextManager[Any]: ...

    def evaluation_progress(
        self,
        *,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        score: float,
        display_info: dict[str, Any] | None = None,
    ) -> None: ...

    def show_final_result(
        self,
        *,
        initial_score: float,
        best_score: float,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    ) -> None: ...


class OptimizationRunDisplay:
    """Coordinate display output for a single optimization run."""

    def __init__(self, *, verbose: int = 1) -> None:
        self._verbose = verbose

    def show_header(
        self,
        *,
        algorithm: str,
        optimization_id: str | None,
        dataset_id: str | None = None,
    ) -> None:
        display_terminal.display_header(
            algorithm=algorithm,
            optimization_id=optimization_id,
            dataset_id=dataset_id,
            verbose=self._verbose,
        )

    def show_configuration(
        self,
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        optimizer_config: dict[str, Any],
    ) -> None:
        display_terminal.display_configuration(
            messages=prompt,
            optimizer_config=optimizer_config,
            verbose=self._verbose,
        )

    def baseline_evaluation(self, context: OptimizationContext) -> ContextManager[Any]:
        dataset = context.evaluation_dataset
        dataset_name = getattr(dataset, "name", None)
        is_validation = (
            context.validation_dataset is not None
            and context.evaluation_dataset is context.validation_dataset
        )
        selection_summary = summarize_selection_policy(context.prompts)
        return display_terminal.display_evaluation(
            verbose=self._verbose,
            dataset_name=dataset_name,
            is_validation=is_validation,
            selection_summary=selection_summary,
        )

    def evaluation_progress(
        self,
        *,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        score: float,
        display_info: dict[str, Any] | None = None,
    ) -> None:
        if self._verbose < 1:
            return

        coerced_score = score
        best_score = context.current_best_score
        prev_best = None
        if display_info is not None:
            prev_best = display_info.get("prev_best_score")

        max_trials = getattr(context, "max_trials", None)
        if isinstance(max_trials, int) and max_trials > 0:
            prefix = f"Trial {context.trials_completed}/{max_trials}"
        else:
            prefix = f"Trial {context.trials_completed}"
        score_label = ""
        compare_score = prev_best if isinstance(prev_best, (int, float)) else best_score
        if not math.isfinite(coerced_score):
            style = "yellow"
        elif isinstance(compare_score, (int, float)):
            delta = coerced_score - compare_score
            if abs(delta) < 1e-12:
                style = "yellow"
                score_label = " ▸ [Δ 0.0000 vs best | no improvement]"
            elif delta > 0:
                style = "green"
                score_label = f" ▲ [Δ +{delta:.4f} vs best | improved]"
            else:
                style = "red"
                score_label = f" ▼ [Δ {delta:.4f} vs best | no improvement]"
        elif best_score is None:
            style = "green"
        else:
            style = "yellow"

        info = display_info or self._build_evaluation_display_info(context)
        display_terminal.display_evaluation_progress(
            prefix=prefix,
            score_text=(
                f"{coerced_score:.4f}{score_label}"
                if math.isfinite(coerced_score)
                else "non-finite score"
            ),
            style=style,
            prompts=prompts,
            verbose=self._verbose,
            dataset_name=info.get("dataset_name"),
            dataset_type=info.get("dataset_type"),
            evaluation_settings=info.get("evaluation_settings"),
        )

    def _build_evaluation_display_info(
        self, context: OptimizationContext
    ) -> dict[str, Any]:
        dataset = context.evaluation_dataset
        dataset_name = getattr(dataset, "name", None)

        is_validation = (
            context.validation_dataset is not None
            and context.evaluation_dataset is context.validation_dataset
        )
        dataset_type = "validation" if is_validation else "training"
        context.dataset_split = dataset_type

        return {
            "dataset_name": dataset_name,
            "dataset_type": dataset_type,
            "evaluation_settings": None,
        }

    def show_final_result(
        self,
        *,
        initial_score: float,
        best_score: float,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    ) -> None:
        display_terminal.display_result(
            initial_score=initial_score,
            best_score=best_score,
            prompt=prompt,
            verbose=self._verbose,
        )
