from __future__ import annotations

import functools
import hashlib
import logging
import os
import re
import sys
from datetime import datetime
from typing import TYPE_CHECKING, Any
from collections.abc import Callable

from rich import box
from rich.console import Group
from rich.live import Live
from rich.padding import Padding
from rich.panel import Panel
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
from rich.table import Table
from rich.text import Text

from benchmarks.utils.sinks import BenchmarkEvent, EventSink, NullSink
from opik_optimizer.utils.reporting import get_console

if TYPE_CHECKING:
    from benchmarks.core.types import TaskResult

console = get_console(width=120, soft_wrap=True)

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


class BenchmarkLogger:
    def __init__(self, event_sink: EventSink | None = None) -> None:
        self.event_sink = event_sink or NullSink()

    def setup_logger(
        self,
        demo_datasets: list[str],
        optimizers: list[str],
        models: list[str],
        test_mode: bool,
        run_id: str,
    ) -> None:
        self.demo_datasets = demo_datasets
        self.optimizers = optimizers
        self.models = models
        self.test_mode = test_mode
        self.run_id = run_id
        self.tasks_status: dict[Any, dict[str, Any]] = {}
        self.completed_tasks_count = {"Success": 0, "Failed": 0}
        self.task_results: list[TaskResult] = []

    def print_benchmark_header(self) -> None:
        console.print(Rule("[bold blue]Benchmark Configuration[/bold blue]"))

        table = Table(box=box.ROUNDED, show_header=False, padding=(0, 1))
        table.add_row("Run ID", self.run_id)
        table.add_row("Datasets", ", ".join(self.demo_datasets))
        table.add_row("Optimizers", ", ".join(self.optimizers))
        table.add_row("Models", ", ".join(self.models))
        table.add_row("Test mode", str(self.test_mode))

        console.print(Panel(table, border_style="blue", padding=(1, 2)))
        console.print()

        total_tasks = len(self.demo_datasets) * len(self.optimizers) * len(self.models)
        console.print(Rule("Phase 2: Running Optimizations", style="dim blue"))
        console.print(
            f"Preparing to run [bold cyan]{total_tasks}[/bold cyan] optimization tasks..."
        )

        self.progress = Progress(
            *PROGRESS_COLUMNS, console=console, transient=False, expand=True
        )
        self.progress_task_id = self.progress.add_task(
            "[bold blue]Overall Progress[/bold blue]", total=total_tasks
        )
        self.total_tasks = total_tasks

    def update_active_task_status(
        self,
        future: Any,
        dataset_name: str,
        optimizer_name: str,
        model_name: str,
        status: str,
        short_id: str | None = None,
    ) -> None:
        self.tasks_status[future] = {
            "dataset_name": dataset_name,
            "optimizer_name": optimizer_name,
            "model_name": model_name,
            "status": status,
            "short_id": short_id,
        }
        self.event_sink.emit(
            BenchmarkEvent(
                name="task_status_updated",
                payload={
                    "dataset_name": dataset_name,
                    "optimizer_name": optimizer_name,
                    "model_name": model_name,
                    "status": status,
                    "short_id": short_id,
                },
            )
        )

    def remove_active_task_status(
        self, future: Any, final_status: str | None = None
    ) -> None:
        if future in self.tasks_status:
            if final_status in ("Success", "Failed"):
                self.completed_tasks_count[final_status] += 1
                self.progress.advance(self.progress_task_id, 1)
                self.event_sink.emit(
                    BenchmarkEvent(
                        name="task_finished", payload={"status": final_status}
                    )
                )
            del self.tasks_status[future]

    def _generate_live_display_message(self) -> Group:
        active_lines: list[Text] = []
        for status_info in self.tasks_status.values():
            if status_info.get("status") != "Running":
                continue
            dataset_name = status_info.get("dataset_name", "Unknown")
            optimizer_name = status_info.get("optimizer_name", "?")
            model_name = status_info.get("model_name", "?")
            short_id = status_info.get("short_id")
            if short_id:
                line = Text.assemble(
                    " • ",
                    (f"#{short_id} ", "dim"),
                    (dataset_name, "yellow"),
                    (f" [{optimizer_name}]", "dim"),
                    (f" ({model_name})", "dim"),
                )
            else:
                line = Text.assemble(
                    " • ",
                    (dataset_name, "yellow"),
                    (f" [{optimizer_name}]", "dim"),
                    (f" ({model_name})", "dim"),
                )
            active_lines.append(line)

        active_tasks_content = (
            Group(*active_lines)
            if active_lines
            else Group(Text("Waiting for tasks...", style="dim"))
        )
        active_panel = Panel(
            active_tasks_content,
            title="Active Tasks",
            border_style="blue",
            padding=(0, 1),
        )

        nb_active_tasks = len(
            [x for x in self.tasks_status.values() if x["status"] == "Running"]
        )
        nb_success = self.completed_tasks_count["Success"]
        nb_failed = self.completed_tasks_count["Failed"]
        summary_line = Text(
            f"Run: {self.run_id} | Tasks: {nb_success + nb_failed}/{self.total_tasks} | Success: {nb_success} | Failed: {nb_failed} | Active: {nb_active_tasks}",
            style="dim",
        )

        return Group(self.progress, Padding(summary_line, (0, 0, 1, 0)), active_panel)

    def create_live_panel(self) -> Live:
        return Live(console=console, refresh_per_second=4, vertical_overflow="visible")

    def add_result_panel(
        self,
        dataset_name: str,
        optimizer_name: str,
        task_detail_data: TaskResult | None = None,
    ) -> None:
        del dataset_name
        del optimizer_name
        if task_detail_data is not None:
            self.task_results.append(task_detail_data)

    @staticmethod
    def _extract_primary_metric(result: TaskResult) -> tuple[str, str]:
        evals = result.evaluations or {}
        initial_set = evals.get("initial")
        final_set = evals.get("final")

        def _metric_value(eval_set: Any, split: str) -> tuple[str, float] | None:
            entry = getattr(eval_set, split, None) if eval_set else None
            eval_result = getattr(entry, "result", None) if entry else None
            metrics = getattr(eval_result, "metrics", None) if eval_result else None
            if not metrics:
                return None
            first = metrics[0]
            name = str(first.get("metric_name", "metric"))
            score = first.get("score")
            if isinstance(score, (int, float)):
                return name, float(score)
            return None

        for split in ("validation", "train", "test"):
            initial = _metric_value(initial_set, split)
            final = _metric_value(final_set, split)
            if initial and final:
                metric_name = initial[0]
                improvement = final[1] - initial[1]
                return (
                    metric_name,
                    f"{initial[1]:.4f} -> {final[1]:.4f} ({improvement:+.4f})",
                )
            if final:
                return final[0], f"{final[1]:.4f}"
        return "metric", "N/A"

    def _render_task_detail(self, task: TaskResult) -> None:
        rich_candidate = task.optimization_summary or task.optimization_raw_result
        if rich_candidate is not None and hasattr(rich_candidate, "__rich__"):
            console.print(rich_candidate)
            if task.error_message:
                console.print(
                    Panel(task.error_message, title="Task Error", border_style="red")
                )
            return

        status_style = "green" if task.status == "Success" else "red"
        metric_name, metric_summary = self._extract_primary_metric(task)
        table = Table(show_header=False, box=None, padding=(0, 1))
        table.add_row("Task", task.id)
        table.add_row("Status", f"[{status_style}]{task.status}[/{status_style}]")
        table.add_row("Dataset", task.dataset_name)
        table.add_row("Optimizer", task.optimizer_name)
        table.add_row("Model", task.model_name)
        table.add_row(metric_name, metric_summary)
        if task.llm_calls_total_optimization is not None:
            table.add_row("LLM calls", str(task.llm_calls_total_optimization))
        if task.error_message:
            table.add_row("Error", task.error_message)

        console.print(
            Panel(table, title=f"Task Result: {task.id}", border_style=status_style)
        )

    def print_benchmark_footer(
        self, results: list[TaskResult], total_duration: float
    ) -> None:
        successful_tasks = len([x for x in results if x.status == "Success"])
        failed_tasks = len([x for x in results if x.status == "Failed"])

        console.print(Rule("[bold blue]Benchmark Run Complete[/bold blue]"))

        summary_table = Table(box=box.ROUNDED, show_header=False, padding=(0, 1))
        summary_table.add_row("Total", str(successful_tasks + failed_tasks))
        summary_table.add_row("Success", f"[green]{successful_tasks}[/green]")
        summary_table.add_row("Failed", f"[red]{failed_tasks}[/red]")
        summary_table.add_row("Duration", f"{total_duration:.2f}s")
        console.print(Panel(summary_table, title="Summary", border_style="blue"))

        if results:
            results_table = Table(box=box.SIMPLE, show_header=True)
            results_table.add_column("ID", style="dim", no_wrap=True)
            results_table.add_column("Dataset")
            results_table.add_column("Optimizer")
            results_table.add_column("Model")
            results_table.add_column("Status", no_wrap=True)
            results_table.add_column("LLM Calls", justify="right")
            for task in sorted(results, key=lambda x: x.id):
                status_style = "green" if task.status == "Success" else "red"
                short_id = hashlib.sha1(
                    f"{self.run_id}:{task.id}".encode()
                ).hexdigest()[:5]
                results_table.add_row(
                    short_id,
                    task.dataset_name,
                    task.optimizer_name,
                    task.model_name,
                    f"[{status_style}]{task.status}[/{status_style}]",
                    str(task.llm_calls_total_optimization or "-"),
                )
            console.print(Panel(results_table, title="Tasks", border_style="blue"))

        if self.task_results:
            console.print(Rule("Task Details", style="dim blue"))
            for task in self.task_results:
                self._render_task_detail(task)
                console.print()


def log_console_output_to_file() -> Callable:
    """Capture stdout/stderr to per-task logs while keeping normal terminal output."""

    class TeeOutput:
        def __init__(self, file: Any, original_stream: Any) -> None:
            self.file = file
            self.original_stream = original_stream

        def write(self, data: str) -> None:
            ansi_escape = re.compile(r"\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
            clean_data = ansi_escape.sub("", data)
            self.file.write(clean_data)

        def flush(self) -> None:
            self.file.flush()
            self.original_stream.flush()

    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            if "checkpoint_folder" in kwargs:
                checkpoint_folder = kwargs["checkpoint_folder"]
                kwargs.pop("checkpoint_folder")
            else:
                checkpoint_folder = os.path.abspath(
                    os.path.join(
                        os.path.expanduser("~"),
                        ".opik_optimizer",
                        "benchmark_results",
                    )
                )

            dataset_name = kwargs.get(
                "dataset_name", args[0] if len(args) > 0 else "unknown"
            )
            optimizer_name = kwargs.get(
                "optimizer_name", args[1] if len(args) > 1 else "unknown"
            )
            model_name = kwargs.get(
                "model_name", args[2] if len(args) > 2 else "unknown"
            )

            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            sanitized_dataset_name = str(dataset_name).replace("/", "_")
            sanitized_optimizer_name = str(optimizer_name).replace("/", "_")
            sanitized_model_name = str(model_name).replace("/", "_")
            file_path = os.path.join(
                checkpoint_folder,
                (
                    "optimization_"
                    f"{sanitized_dataset_name}_"
                    f"{sanitized_optimizer_name}_"
                    f"{sanitized_model_name}_"
                    f"{timestamp}.log"
                ),
            )
            os.makedirs(os.path.dirname(file_path), exist_ok=True)

            original_stdout = sys.stdout
            original_stderr = sys.stderr
            opik_optimizer_logger = logging.getLogger("opik_optimizer")
            original_level = opik_optimizer_logger.level

            try:
                opik_optimizer_logger.setLevel(logging.INFO)
                with open(file_path, "w", encoding="utf-8") as f:
                    sys.stdout = TeeOutput(f, original_stdout)  # type: ignore[assignment]
                    sys.stderr = TeeOutput(f, original_stderr)  # type: ignore[assignment]
                    return func(*args, **kwargs)
            finally:
                opik_optimizer_logger.setLevel(original_level)
                sys.stdout = original_stdout
                sys.stderr = original_stderr
                print(f"Console output has been saved to: {file_path}")

        return wrapper

    return decorator
