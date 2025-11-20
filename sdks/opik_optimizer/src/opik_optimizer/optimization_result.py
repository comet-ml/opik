"""Module containing the OptimizationResult class."""

from typing import Any

import pydantic
import rich

from .reporting_utils import (
    _format_message_content,
    get_console,
    get_link_text,
    get_optimization_run_url_by_id,
)


def _format_float(value: Any, digits: int = 6) -> str:
    """Format float values with specified precision."""
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


class OptimizationResult(pydantic.BaseModel):
    """Result oan optimization run."""

    optimizer: str = "Optimizer"

    prompt: list[dict[str, Any]]
    score: float
    metric_name: str

    optimization_id: str | None = None
    dataset_id: str | None = None

    # Initial score
    initial_prompt: list[dict[str, Any]] | None = None
    initial_score: float | None = None

    details: dict[str, Any] = pydantic.Field(default_factory=dict)
    history: list[dict[str, Any]] = []
    llm_calls: int | None = None
    tool_calls: int | None = None

    # MIPRO specific
    demonstrations: list[dict[str, Any]] | None = None
    mipro_prompt: str | None = None
    tool_prompts: dict[str, str] | None = None

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

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

    def apply_to_prompt(self, prompt: Any) -> Any:
        """
        Apply optimized parameters to a prompt.

        Args:
            prompt: ChatPrompt instance to apply optimizations to

        Returns:
            New ChatPrompt instance with optimized parameters applied
        """
        prompt_copy = prompt.copy()
        if "optimized_model_kwargs" in self.details:
            prompt_copy.model_kwargs = self.details["optimized_model_kwargs"]
        if "optimized_model" in self.details:
            prompt_copy.model = self.details["optimized_model"]
        return prompt_copy

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
        rounds_ran = len(self.details.get("rounds", []))
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
            final_prompt_display = "\n".join(
                [
                    f"  {msg.get('role', 'unknown')}: {str(msg.get('content', ''))[:150]}..."
                    for msg in self.prompt
                ]
            )
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
            f"Rounds Completed: {rounds_ran}",
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
        rounds_ran = len(self.details.get("rounds", []))
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
        table.add_row("Rounds Completed:", str(rounds_ran))
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
            for msg in self.prompt:
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
                # Format content using Rich, handling both string and multimodal content
                formatted_content = _format_message_content(content)
                role_text = rich.text.Text(f"{role.capitalize()}:", style=role_style)
                chat_group_items.append(
                    rich.console.Group(role_text, formatted_content)
                )
                chat_group_items.append(rich.text.Text("---", style="dim"))  # Separator
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
