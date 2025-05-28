"""Module containing the OptimizationResult class."""

from typing import Dict, List, Any, Optional, Union, Literal
import pydantic
from opik.evaluation.metrics import BaseMetric
from pydantic import BaseModel, Field
from .base_optimizer import OptimizationRound  # Adjust import as necessary
import rich

class OptimizationStep(BaseModel):
    """Represents a single step or trial in an optimization process."""
    step: int
    score: Optional[float] = None
    prompt: Optional[Union[str, List[Dict[str, str]]]] = None
    parameters: Optional[Dict[str, Any]] = None
    timestamp: Optional[str] = None
    # Add other relevant details per step if needed


class OptimizationResult(pydantic.BaseModel):
    """Result of an optimization run."""

    prompt: Union[str, List[Dict[Literal["role", "content"], str]]]
    score: float
    metric_name: str
    metadata: Dict[str, Any] = pydantic.Field(
        default_factory=dict
    )  # Default empty dict
    details: Dict[str, Any] = pydantic.Field(default_factory=dict)  # Default empty dict
    best_prompt: Optional[str] = None
    best_score: Optional[float] = None
    best_metric_name: Optional[str] = None
    best_details: Optional[Dict[str, Any]] = None
    all_results: Optional[List[Dict[str, Any]]] = None
    history: List[Dict[str, Any]] = []
    metric: Optional[BaseMetric] = None
    demonstrations: Optional[List[Dict[str, Any]]] = None
    optimizer: str = "Optimizer"
    tool_prompts: Optional[Dict[str, str]] = None
    opik_metadata: Optional[Dict[str, Any]] = None
    llm_calls: Optional[int] = None

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    def _calculate_improvement_str(self) -> str:
        """Helper to calculate improvement percentage string."""
        initial_s = self.details.get("initial_score")
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
        initial_score = self.details.get("initial_score")
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
        stopped_early = self.details.get("stopped_early", "N/A")

        model_name = self.details.get("model", "N/A")
        temp = self.details.get("temperature")
        temp_str = f"{temp:.1f}" if isinstance(temp, (int, float)) else "N/A"

        final_prompt_display = self.prompt
        if self.details.get("prompt_type") == "chat" and self.details.get(
            "chat_messages"
        ):
            try:
                chat_display = "\n".join(
                    [
                        f"  {msg.get('role', 'unknown')}: {str(msg.get('content', ''))[:150]}..."
                        for msg in self.details["chat_messages"]
                    ]
                )
                final_prompt_display = f"Instruction:\n  {self.prompt}\nFew-Shot Examples (Chat Structure):\n{chat_display}"
            except Exception:
                pass

        output = [
            f"\n{separator}",
            f"OPTIMIZATION COMPLETE",
            f"{separator}",
            f"Optimizer:        {self.optimizer}",
            f"Model Used:       {model_name} (Temp: {temp_str})",
            f"Metric Evaluated: {self.metric_name}",
            f"Initial Score:    {initial_score_str}",
            f"Final Best Score: {final_score_str}",
            f"Total Improvement:{improvement_str.rjust(max(0, 18 - len('Total Improvement:')))}",
            f"Rounds Completed: {rounds_ran}",
            f"Stopped Early:    {stopped_early}",
            f"\nFINAL OPTIMIZED PROMPT / STRUCTURE:",
            f"--------------------------------------------------------------------------------",
            f"{final_prompt_display}",
            f"--------------------------------------------------------------------------------",
            f"{separator}",
        ]
        return "\n".join(output)

    def __rich__(self) -> rich.panel.Panel:
        """Provides a rich, formatted output for terminals supporting Rich."""
        improvement_str = self._calculate_improvement_str()
        rounds_ran = len(self.details.get("rounds", []))
        initial_score = self.details.get("initial_score")
        initial_score_str = (
            f"{initial_score:.4f}"
            if isinstance(initial_score, (int, float))
            else "[dim]N/A[/dim]"
        )
        final_score_str = f"{self.score:.4f}"
        stopped_early = self.details.get("stopped_early", "N/A")

        model_name = self.details.get("model", "[dim]N/A[/dim]")
        temp = self.details.get("temperature")
        temp_str = f"{temp:.1f}" if isinstance(temp, (int, float)) else "[dim]N/A[/dim]"

        table = rich.table.Table.grid(padding=(0, 1))
        table.add_column(style="dim")
        table.add_column()

        table.add_row(
            "Optimizer:",
            f"[bold]{self.optimizer}[/bold]",
        )
        table.add_row("Model Used:", f"{model_name} ([dim]Temp:[/dim] {temp_str})")
        table.add_row("Metric Evaluated:", f"[bold]{self.metric_name}[/bold]")
        table.add_row("Initial Score:", initial_score_str)
        table.add_row("Final Best Score:", f"[bold cyan]{final_score_str}[/bold cyan]")
        table.add_row("Total Improvement:", improvement_str)
        table.add_row("Rounds Completed:", str(rounds_ran))
        table.add_row("Stopped Early:", str(stopped_early))

        # Display Chat Structure if available
        prompt_renderable: Any = rich.text.Text(
            self.prompt or "", overflow="fold"
        )  # Default to text
        panel_title = "[bold]Final Optimized Prompt (Instruction)[/bold]"

        if self.details.get("prompt_type") == "chat" and self.details.get(
            "chat_messages"
        ):
            panel_title = "[bold]Final Optimized Prompt (Chat Structure)[/bold]"
            try:
                chat_group_items = [
                    f"[dim]Instruction:[/dim] [i]{self.prompt}[/i]\n---"
                ]
                for msg in self.details["chat_messages"]:
                    role = msg.get("role", "unknown")
                    content = str(msg.get("content", ""))
                    role_style = (
                        "bold green"
                        if role == "user"
                        else (
                            "bold blue"
                            if role == "assistant"
                            else ("bold magenta" if role == "system" else "")
                        )
                    )
                    chat_group_items.append(
                        f"[{role_style}]{role.capitalize()}:[/] {content}"
                    )
                    chat_group_items.append("---")  # Separator
                prompt_renderable = rich.console.Group(*chat_group_items)

            except Exception:
                # Fallback to simple text prompt
                prompt_renderable = rich.text.Text(self.prompt or "", overflow="fold")
                panel_title = (
                    "[bold]Final Optimized Prompt (Instruction - fallback)[/bold]"
                )

        prompt_panel = rich.panel.Panel(
            prompt_renderable, title=panel_title, border_style="blue", padding=(1, 2)
        )

        content_group = rich.console.Group(table, "\n", prompt_panel)

        return rich.panel.Panel(
            content_group,
            title="[bold yellow]Optimization Complete[/bold yellow]",
            border_style="yellow",
            box=rich.box.DOUBLE_EDGE,
            padding=1,
        )

    def model_dump(self) -> Dict[str, Any]:
        return super().model_dump()

    def display(self) -> None:
        """
        Displays the OptimizationResult using rich formatting
        """
        rich.print(self)
