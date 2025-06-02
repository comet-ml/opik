from typing import List, Any, Dict

from rich import box
from rich.align import Align
from rich.console import Console, Group
from rich.live import Live
from rich.padding import Padding
from rich.panel import Panel

# Rich imports
from rich.progress import (
    BarColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
    TimeRemainingColumn,
)
from rich.rule import Rule
from rich.style import Style
from rich.table import Table
from rich.text import Text

console = Console(
    width=120,
    style=Style(color="white"),
    highlight=True,
    soft_wrap=True,
)

STYLES = {
    "header": Style(color="cyan", bold=True),
    "success": Style(color="green", bold=True),
    "warning": Style(color="yellow", bold=True),
    "error": Style(color="red", bold=True),
    "info": Style(color="blue"),
    "dim": Style(dim=True),
}

PROGRESS_COLUMNS = (
    SpinnerColumn(),
    TextColumn("[progress.description]{task.description}"),
    BarColumn(bar_width=40),
    TaskProgressColumn(),
    TextColumn("•"),
    TimeElapsedColumn(),
    TextColumn("•"),
    TimeRemainingColumn(),
)

class BenchmarkLogger():
    def __init__(self):
        pass

    def setup_logger(
        self,
        demo_datasets: List[str],
        optimizers: List[str],
        models: List[str],
        test_mode: bool,
        run_id: str
    ):
        self.demo_datasets = demo_datasets
        self.optimizers = optimizers
        self.models = models,
        self.test_mode = test_mode
        self.run_id = run_id 
        self.tasks_status: Dict[Any, Any] = {}

    def print_benchmark_header(self):
        """Print a clean header for the benchmark run."""
        console.print(Rule("[bold blue]Benchmark Configuration[/bold blue]", style="blue"))
        
        table = Table(box=box.ROUNDED, show_header=False, padding=(0, 1))
        table.add_row("Datasets", ", ".join(self.demo_datasets), style=STYLES["header"])
        table.add_row("Optimizers", ", ".join(self.optimizers), style=STYLES["header"])
        table.add_row("Test Mode", str(self.test_mode), style=STYLES["info"])
        
        console.print(Panel(table, border_style="blue", padding=(1, 2)))
        console.print()

        total_tasks = len(self.demo_datasets) * len(self.optimizers) * len(self.models)
        console.print(Rule("Phase 2: Running Optimizations", style="dim blue"))
        console.print(f"Preparing to run [bold cyan]{total_tasks}[/bold cyan] optimization tasks...")

        progress = Progress(*PROGRESS_COLUMNS, console=console, transient=False, expand=True)
        progress.add_task("[bold blue]Overall Progress[/bold blue]", total=len(self.demo_datasets) * len(self.optimizers) * len(self.models))
        
        self.progress = progress
        self.total_tasks = total_tasks
    
    def update_active_task_status(self, future, task_description, optimizer_name, model_name, status):
        self.tasks_status[future] = {
            "desc": f"Running: {task_description}",
            "optimizer_key": optimizer_name,
            "model": model_name,
            "status": status
        }

    def _generate_live_display_message(self) -> Group:
        active_list = []
        for status_info in self.tasks_status.values():
            desc = status_info.get("desc", "Unknown Task") 
            opt_key = status_info.get("optimizer_key", "?") # Use optimizer_key
            model_original = status_info.get("model", "?") # Use the original model name stored in status_info["model"]
            # Extract dataset from desc (first part before '/')
            # TODO: Move this to a function
            try:
                dataset_part = desc.split('/')[0].replace("Running: ", "").strip()
                # Use opt_key (config name) in display
                display_text = f" • {dataset_part} + {model_original}" 
            except Exception:
                display_text = f" • {desc}" # Fallback to full desc
            
            active_list.append(
                    Text.assemble((display_text, "yellow"), (f" [{opt_key}]", "dim")) # Use opt_key
                )
            
        if not active_list:
            active_tasks_content = Group(Text("Waiting for tasks...", style="dim"))
        else:
            active_tasks_content = Group(*active_list)
        updated_active_panel = Panel(active_tasks_content, title="Active Tasks", border_style="blue", padding=(0,1))

        nb_active_tasks = len([x for x in self.tasks_status.values() if x["status"] == "Pending"])
        nb_success_tasks = len([x for x in self.tasks_status.values() if x["status"] == "Success"])
        nb_failed_tasks = len([x for x in self.tasks_status.values() if x["status"] == "Failed"])
        summary_line = Text(f"Run: {self.run_id} | Tasks: {nb_active_tasks}/{self.total_tasks} | Success: {nb_success_tasks} | Failed: {nb_failed_tasks} | Active: {nb_active_tasks}", style="dim")
        
        return Group(self.progress, Padding(summary_line, (0, 0, 1, 0)), updated_active_panel)

    def create_live_panel(self):
        return Live(console=console, refresh_per_second=4, vertical_overflow="visible")
