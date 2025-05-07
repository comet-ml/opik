"""Module containing the OptimizationResult class."""

from typing import Dict, List, Any, Optional, Union, Literal
import pydantic
from opik.evaluation.metrics import BaseMetric
from pydantic import BaseModel, Field
from .base_optimizer import OptimizationRound # Adjust import as necessary
from rich import box
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich.console import Group


class OptimizationResult(pydantic.BaseModel):
    """Result of an optimization run."""
    prompt: Union[str, List[Dict[Literal["role", "content"], str]]]
    score: float
    metric_name: str
    metadata: Dict[str, Any] = pydantic.Field(default_factory=dict)  # Default empty dict
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

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    def _calculate_improvement_str(self) -> str:
        """Helper to calculate improvement percentage string."""
        initial_s = self.details.get('initial_score')
        final_s = self.score
        if initial_s is None or final_s is None:
            return "N/A"
        
        if initial_s != 0:
            improvement_pct = (final_s - initial_s) / abs(initial_s)
            return f"{improvement_pct:.2%}"
        elif final_s > 0:
            return "infinite (initial score was 0)"
        else:
             return "0.00% (no improvement from 0)"

    def __str__(self) -> str:
        """Provides a clean, well-formatted plain-text summary."""
        separator = "=" * 80
        rounds_ran = len(self.details.get('rounds', []))
        initial_score_str = f"{self.details.get('initial_score', 'N/A'):.4f}"
        final_score_str = f"{self.score:.4f}"
        improvement_str = self._calculate_improvement_str()

        output = [
            f"\n{separator}",
            f"OPTIMIZATION COMPLETE",
            f"{separator}",
            f"Metric Evaluated: {self.metric_name}",
            f"Initial Score:    {initial_score_str}",
            f"Final Best Score: {final_score_str}",
            f"Total Improvement: {improvement_str}",
            f"Rounds Completed: {rounds_ran}",
            f"Stopped Early:    {self.details.get('stopped_early', 'N/A')}",
            f"\nFINAL OPTIMIZED PROMPT:",
            f"--------------------------------------------------------------------------------",
            f"{self.prompt}",
            f"--------------------------------------------------------------------------------",
            f"{separator}"
        ]
        return "\n".join(output)

    def __rich__(self) -> Panel:
        """Provides a rich, formatted output for terminals supporting Rich."""
        rounds_ran = len(self.details.get('rounds', []))
        initial_score_str = f"{self.details.get('initial_score', 'N/A'):.4f}"
        final_score_str = f"{self.score:.4f}"
        improvement_str = self._calculate_improvement_str()
        initial_s = self.details.get('initial_score')
        final_s = self.score

        if initial_s is not None and final_s is not None:
             if initial_s != 0:
                 improvement_pct = (final_s - initial_s) / abs(initial_s)
                 if improvement_pct > 0:
                     improvement_str = f"[bold green]{improvement_str}[/bold green]"
                 elif improvement_pct < 0:
                     improvement_str = f"[bold red]{improvement_str}[/bold red]"
             elif final_s > 0:
                 improvement_str = "[bold green]infinite[/bold green] (initial score was 0)"

        table = Table.grid(padding=(0, 1))
        table.add_column(style="dim")
        table.add_column()
        
        table.add_row("Metric Evaluated:", f"[bold]{self.metric_name}[/bold]")
        table.add_row("Initial Score:", initial_score_str)
        table.add_row("Final Best Score:", f"[bold cyan]{final_score_str}[/bold cyan]")
        table.add_row("Total Improvement:", improvement_str)
        table.add_row("Rounds Completed:", str(rounds_ran))
        table.add_row("Stopped Early:", str(self.details.get('stopped_early', 'N/A')))

        prompt_panel = Panel(
            Text(self.prompt, overflow="fold"),
            title="[bold]Final Optimized Prompt[/bold]",
            border_style="blue",
            padding=(1, 2)
        )

        content_group = Group(
            table,
            "\n",
            prompt_panel
        )

        return Panel(
            content_group,
            title="[bold yellow]Optimization Complete[/bold yellow]",
            border_style="yellow",
            box=box.DOUBLE_EDGE,
            padding=1
        )

    def model_dump(self) -> Dict[str, Any]:
        return super().model_dump()
