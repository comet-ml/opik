"""Run-level display orchestration for optimization flows."""

from __future__ import annotations

from typing import Any, ContextManager, Protocol, TYPE_CHECKING

from ...api_objects import chat_prompt

if TYPE_CHECKING:
    from ...core.state import OptimizationContext
from .format import format_score_progress, summarize_selection_policy
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
        tool_use_allowed = optimizer_config.get("allow_tool_use")
        optimizable_roles = optimizer_config.get("optimizable_roles")
        if isinstance(optimizable_roles, list):
            optimizable_roles = set(map(str, optimizable_roles))
        elif optimizable_roles is None:
            optimizable_roles = None
        else:
            optimizable_roles = {str(optimizable_roles)}
        optimized_tool_names = None
        tool_names = optimizer_config.get("tool_names")
        if isinstance(tool_names, list):
            optimized_tool_names = set(map(str, tool_names))
        elif optimizer_config.get("optimize_tools") is False:
            optimized_tool_names = set()
        display_terminal.display_configuration(
            messages=prompt,
            optimizer_config=optimizer_config,
            verbose=self._verbose,
            tool_use_allowed=tool_use_allowed,
            optimizable_roles=optimizable_roles,
            optimized_tool_names=optimized_tool_names,
        )

    def baseline_evaluation(self, context: OptimizationContext) -> ContextManager[Any]:
        dataset = context.evaluation_dataset
        dataset_name = getattr(dataset, "name", None)
        is_validation = (
            context.validation_dataset is not None
            and context.evaluation_dataset is context.validation_dataset
        )
        evaluation_settings = self._format_evaluation_settings(context)
        return display_terminal.display_evaluation(
            verbose=self._verbose,
            dataset_name=dataset_name,
            is_validation=is_validation,
            selection_summary=evaluation_settings,
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
        compare_score = prev_best if isinstance(prev_best, (int, float)) else best_score
        score_text, style = format_score_progress(coerced_score, compare_score)

        info = display_info or self._build_evaluation_display_info(context)
        display_terminal.display_evaluation_progress(
            prefix=prefix,
            score_text=score_text,
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
            "evaluation_settings": self._format_evaluation_settings(context),
        }

    def _format_evaluation_settings(self, context: OptimizationContext) -> str:
        selection_summary = summarize_selection_policy(context.prompts)
        if selection_summary.startswith("n=1") and "policy=" in selection_summary:
            selection_summary = "n=1"

        sample_parts: list[str] = []
        if context.n_samples is not None:
            sample_parts.append(f"n_samples={context.n_samples}")
        else:
            sample_parts.append("n_samples=all")

        if context.n_samples_minibatch is not None:
            sample_parts.append(f"minibatch_samples={context.n_samples_minibatch}")

        if context.n_samples_strategy:
            sample_parts.append(f"strategy={context.n_samples_strategy}")

        if sample_parts:
            return selection_summary + " | " + ", ".join(sample_parts)
        return selection_summary

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
