import functools
import logging
import os
import re
import sys
import typing
from datetime import datetime
from typing import Any, Optional
from collections.abc import Callable

from rich import box
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

if typing.TYPE_CHECKING:
    from benchmark_task import TaskResult

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
    "Success": Style(color="green", bold=True),
    "Failed": Style(color="red", bold=True),
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


def clean_metric_name(metric_name: str) -> str:
    return metric_name


class BenchmarkLogger:
    def __init__(self) -> None:
        pass

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
        self.models = (models,)
        self.test_mode = test_mode
        self.run_id = run_id
        self.tasks_status: dict[Any, Any] = {}
        self.result_panels: list[Panel] = []
        self.completed_tasks_count = {"Success": 0, "Failed": 0}

    def _calculate_percentage_change(
        self, initial: float | None, final: float | None, metric_name: str
    ) -> Text:
        """Calculate the percentage change between two values."""

        LOWER_IS_BETTER = ["hallucination", "contextprecision"]
        metric_name_lower = metric_name.lower()
        lower_is_better = any(term in metric_name_lower for term in LOWER_IS_BETTER)

        if not isinstance(initial, (int, float)) or not isinstance(final, (int, float)):
            return Text("N/A", style=STYLES["dim"])
        if initial == final:
            return Text("No Change", style=STYLES["dim"])
        if initial == 0:
            if final > 0:
                style = STYLES["error"] if lower_is_better else STYLES["success"]
            elif final < 0:
                style = STYLES["success"] if lower_is_better else STYLES["error"]
            else:
                return Text(
                    "N/A", style=STYLES["dim"]
                )  # Should not happen if initial==final check passed
            return Text(f"{'+' if final > 0 else ''}Inf%", style=style)

        change = (final - initial) / abs(initial)
        if lower_is_better:
            style = STYLES["success"] if change < 0 else STYLES["error"]
        else:
            style = STYLES["success"] if change > 0 else STYLES["error"]

        return Text(f"{change:+.2%}", style=style)

    def print_benchmark_header(self) -> None:
        """Print a clean header for the benchmark run."""
        console.print(
            Rule("[bold blue]Benchmark Configuration[/bold blue]", style="blue")
        )

        table = Table(box=box.ROUNDED, show_header=False, padding=(0, 1))
        table.add_row("Datasets", ", ".join(self.demo_datasets), style=STYLES["header"])
        table.add_row("Optimizers", ", ".join(self.optimizers), style=STYLES["header"])
        table.add_row("Test Mode", str(self.test_mode), style=STYLES["info"])

        console.print(Panel(table, border_style="blue", padding=(1, 2)))
        console.print()

        total_tasks = len(self.demo_datasets) * len(self.optimizers) * len(self.models)
        console.print(Rule("Phase 2: Running Optimizations", style="dim blue"))
        console.print(
            f"Preparing to run [bold cyan]{total_tasks}[/bold cyan] optimization tasks..."
        )

        progress = Progress(
            *PROGRESS_COLUMNS, console=console, transient=False, expand=True
        )
        progress.add_task(
            "[bold blue]Overall Progress[/bold blue]",
            total=len(self.demo_datasets) * len(self.optimizers) * len(self.models),
        )

        self.progress = progress
        self.total_tasks = total_tasks

    def update_active_task_status(
        self,
        future: Any,
        dataset_name: str,
        optimizer_name: str,
        model_name: str,
        status: str,
    ) -> None:
        self.tasks_status[future] = {
            "desc": f"Running: {dataset_name} - {optimizer_name} - {model_name}",
            "dataset_name": dataset_name,
            "optimizer_name": optimizer_name,
            "model_name": model_name,
            "status": status,
        }

    def remove_active_task_status(
        self, future: Any, final_status: str | None = None
    ) -> None:
        if future in self.tasks_status:
            # Track completed tasks before removing
            if final_status in ("Success", "Failed"):
                self.completed_tasks_count[final_status] += 1
            del self.tasks_status[future]

    def _generate_live_display_message(self) -> Group:
        active_list = []
        for status_info in self.tasks_status.values():
            dataset_name = status_info.get("dataset_name", "Unknown Task")
            optimizer_name = status_info.get("optimizer_name", "?")
            model_name = status_info.get("model_name", "?")

            display_text = f" • {dataset_name} + {model_name}"
            if status_info["status"] == "Running":
                active_list.append(
                    Text.assemble(
                        (display_text, "yellow"), (f" [{optimizer_name}]", "dim")
                    )
                )

        if not active_list:
            active_tasks_content = Group(Text("Waiting for tasks...", style="dim"))
        else:
            active_tasks_content = Group(*active_list)
        updated_active_panel = Panel(
            active_tasks_content,
            title="Active Tasks",
            border_style="blue",
            padding=(0, 1),
        )

        nb_active_tasks = len(
            [x for x in self.tasks_status.values() if x["status"] == "Running"]
        )
        nb_success_tasks = self.completed_tasks_count["Success"]
        nb_failed_tasks = self.completed_tasks_count["Failed"]
        summary_line = Text(
            f"Run: {self.run_id} | Tasks: {nb_success_tasks + nb_failed_tasks}/{self.total_tasks} | Success: {nb_success_tasks} | Failed: {nb_failed_tasks} | Active: {nb_active_tasks}",
            style="dim",
        )

        return Group(
            self.progress, Padding(summary_line, (0, 0, 1, 0)), updated_active_panel
        )

    def create_live_panel(self) -> Live:
        return Live(console=console, refresh_per_second=4, vertical_overflow="visible")

    def add_result_panel(
        self,
        dataset_name: str,
        optimizer_name: str,
        task_detail_data: Optional["TaskResult"] = None,
    ) -> None:
        """Create a consistent panel for displaying optimization results, including the final prompt."""
        if task_detail_data is None:
            status_text_styled = Text("Failed", style=STYLES["error"])
            panel_title_text = Text.assemble(
                status_text_styled, f" {optimizer_name} on {dataset_name}"
            )

            self.result_panels.append(
                Panel(
                    Text("Failed to run task"),
                    title=panel_title_text,
                    border_style=STYLES["error"],
                    padding=(1, 2),
                    expand=True,
                )
            )
            return

        border_style_obj = STYLES.get(
            task_detail_data.status.lower(),
            STYLES.get("default", Style(color="yellow")),
        )
        status_text_styled = Text(
            task_detail_data.status.upper(), style=border_style_obj
        )
        panel_title_text = Text.assemble(
            status_text_styled, f" {optimizer_name} on {dataset_name}"
        )

        # Create a table that displays key configuration values and the final metrics
        table = Table.grid(padding=(0, 2), expand=True)
        table.add_column(style="dim", width=20)
        table.add_column()

        table.add_row("Dataset:", f"[bold]{dataset_name}[/bold]")
        table.add_row("Optimizer:", f"[bold]{optimizer_name}[/bold]")
        if task_detail_data.timestamp_end:
            table.add_row(
                "Time Taken:",
                f"{task_detail_data.timestamp_end - task_detail_data.timestamp_start:.2f}s",
            )
        else:
            table.add_row("Time Taken:", "[dim]N/A[/dim]")

        llm_calls_count = task_detail_data.llm_calls_total_optimization
        llm_calls_str = (
            f"[bold cyan]{llm_calls_count}[/bold cyan]"
            if llm_calls_count is not None
            else "[dim]N/A[/dim]"
        )
        table.add_row("LLM Calls (Opt):", llm_calls_str)

        # Process scores
        initial_evaluation_metrics = (
            task_detail_data.initial_evaluation.metrics
            if task_detail_data.initial_evaluation
            else []
        )
        final_evaluation_metrics = (
            task_detail_data.optimized_evaluation.metrics
            if task_detail_data.optimized_evaluation
            else []
        )

        all_metric_names = set(
            [x["metric_name"] for x in initial_evaluation_metrics]
            + [x["metric_name"] for x in final_evaluation_metrics]
        )
        initial_scores_grp = []
        final_scores_grp = []
        percent_changes_grp = []
        for metric_name in all_metric_names:
            initial_score_obj = next(
                (
                    x
                    for x in initial_evaluation_metrics
                    if x["metric_name"] == metric_name
                ),
                None,
            )
            initial_score = initial_score_obj["score"] if initial_score_obj else None
            optimized_score_obj = next(
                (
                    x
                    for x in final_evaluation_metrics
                    if x["metric_name"] == metric_name
                ),
                None,
            )
            optimized_score = (
                optimized_score_obj["score"] if optimized_score_obj else None
            )

            # Process initial scores
            style = (
                STYLES["success"]
                if isinstance(initial_score, (float, int))
                else STYLES["warning"]
            )
            value_str = (
                f"{initial_score:.4f}"
                if isinstance(initial_score, (float, int))
                else ("[dim]N/A[/dim]" if initial_score is None else str(initial_score))
            )
            initial_scores_grp.append(
                Text.assemble(f" • {metric_name}: ", (value_str, style))
            )

            # Process final scores
            final_style = (
                STYLES["success"]
                if isinstance(optimized_score, (float, int))
                else STYLES["warning"]
            )
            final_value_str = (
                f"{optimized_score:.4f}"
                if isinstance(optimized_score, (float, int))
                else (
                    "[dim]N/A[/dim]"
                    if optimized_score is None
                    else str(optimized_score)
                )
            )
            final_scores_grp.append(
                Text.assemble(
                    f" • Final {metric_name}: ", (final_value_str, final_style)
                )
            )

            # Process percentage change
            percent_change_text = self._calculate_percentage_change(
                initial_score, optimized_score, metric_name
            )
            percent_changes_grp.append(
                Text.assemble(f" • {metric_name}: ", percent_change_text)
            )

        score_rows: list[Text | Group] = []
        if initial_scores_grp:
            score_rows.append(Text("Initial Scores:", style="underline"))
            score_rows.append(Group(*initial_scores_grp))
        if final_scores_grp:
            score_rows.append(Text("Final Scores:", style="underline"))
            score_rows.append(Group(*final_scores_grp))
        if percent_changes_grp:
            score_rows.append(Text("% Change:", style="underline"))
            score_rows.append(Group(*percent_changes_grp))

        if score_rows:
            table.add_row("Scores:", Group(*score_rows))
        else:
            table.add_row("Scores:", Text("[dim]N/A[/dim]"))

        # Display final prompt
        if task_detail_data.optimized_prompt:
            prompt_elements = []
            for msg in task_detail_data.optimized_prompt.get_messages():
                style = Style()
                if msg.get("role") == "system":
                    style = Style(color="blue", bold=True)
                elif msg.get("role") == "user":
                    style = Style(color="green", bold=True)
                elif msg.get("role") == "assistant":
                    style = Style(color="magenta", bold=True)
                else:
                    style = Style(dim=True)
                prompt_elements.append(Text(f"{msg.get('role')}: ", style=style))
                prompt_elements.append(Text(msg.get("content", ""), overflow="fold"))
            prompt_content_display = (
                Group(*prompt_elements)
                if prompt_elements
                else Text("[dim](Empty chat list)[/dim]")
            )
        else:
            prompt_content_display = Text("[dim]Final prompt not available.[/dim]")
        final_prompt_panel = Panel(
            prompt_content_display,
            title="Final Prompt",
            border_style="dim",
            padding=1,
            expand=True,
        )

        # Add error message if available
        error_panel: Panel | Text
        if task_detail_data.error_message:
            error_panel = Panel(
                Text(task_detail_data.error_message, style="red", overflow="fold"),
                title="Error",
                border_style="red",
                padding=(1, 2),
                width=120,
            )
        else:
            error_panel = Text("No error occured.", style="dim")

        main_content_group = Group(
            table, Text("\n"), final_prompt_panel, Text("\n"), error_panel
        )
        self.result_panels.append(
            Panel(
                main_content_group,
                title=panel_title_text,
                border_style=border_style_obj,
                padding=(1, 2),
                expand=True,
            )
        )
        return None

    def print_benchmark_footer(
        self, results: list["TaskResult"], total_duration: float
    ) -> None:
        """Print footer with stats, pivoted results table, and individual panels+prompts."""
        successful_tasks = len([x for x in results if x.status == "Success"])
        failed_tasks = len([x for x in results if x.status == "Failed"])

        console.print(
            Rule("[bold blue]Benchmark Run Complete[/bold blue]", style="blue")
        )

        # Overall Statistics Panel
        summary_table = Table(
            box=box.ROUNDED, show_header=False, padding=(0, 1), show_edge=False
        )
        summary_table.add_row(
            "Total Benchmarks Run:",
            f"[bold cyan]{successful_tasks + failed_tasks}[/bold cyan]",
        )
        summary_table.add_row(
            "Successful Tasks:", f"[bold green]{successful_tasks}[/bold green]"
        )
        summary_table.add_row("Failed Tasks:", f"[bold red]{failed_tasks}[/bold red]")
        summary_table.add_row("Total Duration:", f"[cyan]{total_duration:.2f}s[/cyan]")
        console.print(
            Panel(
                summary_table,
                title="Overall Statistics",
                border_style="blue",
                padding=(1, 2),
                expand=False,
            )
        )

        # Detailed Pivoted Results Table
        if results:
            results_table = Table(
                box=box.SIMPLE_HEAVY,
                show_header=True,
                header_style=STYLES["header"],
                title="Detailed Results Summary",
                title_style="dim",
                show_lines=True,
                padding=(0, 1, 0, 1),
            )
            results_table.add_column(
                "Dataset",
                style=STYLES["dim"],
                max_width=25,
                overflow="ellipsis",
                no_wrap=True,
            )
            results_table.add_column(
                "Optimizer", max_width=25, overflow="fold", no_wrap=False
            )
            results_table.add_column(
                "Model", no_wrap=True, max_width=20, overflow="ellipsis"
            )
            # results_table.add_column("🔥", justify="center", no_wrap=True)
            results_table.add_column("☎️", justify="right", no_wrap=True)
            results_table.add_column("Metric", no_wrap=True)
            results_table.add_column("Run (s)", justify="right", no_wrap=True)
            results_table.add_column("Initial", justify="right", no_wrap=True)
            results_table.add_column("Final", justify="right", no_wrap=True)
            results_table.add_column("% Change", justify="right", no_wrap=True)

            all_metrics_names = set()
            processed_data_for_table = {}

            for task_summary in results:  # task_summary is an item from self.results
                if task_summary.status != "Success":
                    # Skip non-successful or tasks without detailed JSON for this table
                    continue

                dataset_name = task_summary.dataset_name
                optimizer_name = task_summary.optimizer_name
                model_name = task_summary.model_name
                if task_summary.timestamp_end:
                    time_taken = (
                        task_summary.timestamp_end - task_summary.timestamp_start
                    )
                else:
                    time_taken = 0
                task_id = task_summary.id

                table_key = (dataset_name, optimizer_name, model_name)
                processed_data_for_table[table_key] = {
                    "initial": {},
                    "final": {},
                    "time": time_taken,
                    "task_id": task_id,
                    "temperature": None,
                    "llm_calls_total_optimization": None,
                }

                # Fetch and store LLM calls for the summary table
                processed_data_for_table[table_key]["llm_calls_total_optimization"] = (
                    task_summary.llm_calls_total_optimization
                )

                initial_eval_metrics = (
                    task_summary.initial_evaluation.metrics
                    if task_summary.initial_evaluation
                    else []
                )
                for metric_entry in initial_eval_metrics:
                    metric_display_name = metric_entry.get("metric_name", "Unknown")
                    all_metrics_names.add(metric_display_name)
                    processed_data_for_table[table_key]["initial"][
                        metric_display_name
                    ] = metric_entry.get("score")  # type: ignore

                final_eval_metrics = (
                    task_summary.optimized_evaluation.metrics
                    if task_summary.optimized_evaluation
                    else []
                )
                for metric_entry in final_eval_metrics:
                    metric_display_name = metric_entry.get("metric_name", "Unknown")
                    all_metrics_names.add(metric_display_name)
                    processed_data_for_table[table_key]["final"][
                        metric_display_name
                    ] = metric_entry.get("score")  # type: ignore

            # Sort by dataset, then optimizer, then model for consistent table output
            sorted_table_keys = sorted(processed_data_for_table.keys())
            last_dataset_optimizer_model = None

            for i, key_tuple in enumerate(sorted_table_keys):
                dataset, optimizer, model = key_tuple
                data_for_run_key = processed_data_for_table[key_tuple]
                time_taken_for_run = data_for_run_key.get("time", 0)
                # temperature_for_run = data_for_run_key.get("temperature")
                llm_calls_for_run = data_for_run_key.get("llm_calls_total_optimization")
                scores_by_metric = {
                    "initial": data_for_run_key["initial"],
                    "final": data_for_run_key["final"],
                }
                is_new_block = last_dataset_optimizer_model != key_tuple

                for metric_i, metric_name_to_display in enumerate(
                    sorted(list(all_metrics_names))
                ):
                    initial_val = scores_by_metric["initial"].get(  # type: ignore[attr-defined]
                        metric_name_to_display
                    )
                    final_val = scores_by_metric["final"].get(metric_name_to_display)  # type: ignore

                    if (
                        initial_val is not None or final_val is not None
                    ):  # Only show rows if at least one score exists
                        initial_str = (
                            f"{initial_val:.4f}"
                            if isinstance(initial_val, (int, float))
                            else "[dim]-[/dim]"
                        )
                        final_str = (
                            f"{final_val:.4f}"
                            if isinstance(final_val, (int, float))
                            else "[dim]-[/dim]"
                        )
                        percent_change_text = ""  # calculate_percentage_change(initial_val, final_val, metric_name_to_display)

                        dataset_text = Text(dataset, overflow="ellipsis")
                        dataset_text.truncate(25)
                        model_parts = model.split("/")
                        model_text = Text("\n".join(model_parts), overflow="ellipsis")
                        model_text.truncate(20)

                        display_dataset = dataset_text if metric_i == 0 else ""
                        display_optimizer = optimizer if metric_i == 0 else ""
                        display_model = model_text if metric_i == 0 else ""
                        # display_temp = f"{temperature_for_run:.1f}" if isinstance(temperature_for_run, (int, float)) else "[dim]-[/dim]" if metric_i == 0 else ""
                        display_llm_calls = (
                            str(llm_calls_for_run)
                            if llm_calls_for_run is not None and metric_i == 0
                            else ("[dim]-[/dim]" if metric_i == 0 else "")
                        )  # Display LLM calls
                        display_time = (
                            f"{time_taken_for_run:.2f}" if metric_i == 0 else ""
                        )

                        # Add a line (end_section)
                        end_section_flag = is_new_block and i > 0 and metric_i == 0

                        results_table.add_row(
                            display_dataset,
                            display_optimizer,
                            display_model,
                            # display_temp,
                            display_llm_calls,
                            metric_name_to_display,
                            display_time,
                            initial_str,
                            final_str,
                            percent_change_text,
                            end_section=end_section_flag,
                        )
                last_dataset_optimizer_model = key_tuple

            if not processed_data_for_table:
                console.print(
                    "[yellow]No successful task data to display in pivoted results table.[/yellow]"
                )
            else:
                console.print(
                    Panel(
                        results_table,
                        border_style="blue",
                        padding=(1, 2),
                        title_align="left",
                    )
                )
        else:
            console.print(
                "[yellow]No results (task summaries) available to generate detailed summary table.[/yellow]"
            )

        # Individual Task Panels + Final Prompts
        console.print(Rule("Individual Task Results & Final Prompts", style="dim blue"))
        for panel in self.result_panels:
            console.print(panel)
            console.print()


def log_console_output_to_file() -> Callable:
    """
    Decorator that captures stdout, stderr, and Rich console output to a file
    while still displaying output to the console.
    Uses a hardcoded file path for now.

    Example:
        @log_console_output_to_file()
        def run_optimization(...):
            # Function code here
    """

    # Hardcoded file path

    class TeeOutput:
        def __init__(self, file: Any, original_stream: Any) -> None:
            self.file = file
            self.original_stream = original_stream

        def write(self, data: str) -> None:
            anscii_escape = re.compile(r"\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])")
            clean_data = anscii_escape.sub("", data)
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
                checkpoint_folder = os.path.abspath("./benchmark_results")
            # Extract function arguments for the log filename
            dataset_name = kwargs.get(
                "dataset_name", args[0] if len(args) > 0 else "unknown"
            )
            optimizer_name = kwargs.get(
                "optimizer_name", args[1] if len(args) > 1 else "unknown"
            )
            model_name = kwargs.get(
                "model_name", args[2] if len(args) > 2 else "unknown"
            )

            # Create a unique log file name
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            sanitized_dataset_name = dataset_name.replace("/", "_")
            sanitized_optimizer_name = optimizer_name.replace("/", "_")
            sanitized_model_name = model_name.replace("/", "_")
            file_path = os.path.join(
                checkpoint_folder,
                f"optimization_{sanitized_dataset_name}_{sanitized_optimizer_name}_{sanitized_model_name}_{timestamp}.log",
            )
            os.makedirs(os.path.dirname(file_path), exist_ok=True)

            # Store original stdout and stderr
            original_stdout = sys.stdout
            original_stderr = sys.stderr

            try:
                # Set debug level for all opik_optimizer loggers
                opik_optimizer_logger = logging.getLogger("opik_optimizer")
                original_level = opik_optimizer_logger.level
                opik_optimizer_logger.setLevel(logging.INFO)

                # Open the file for writing
                with open(file_path, "w", encoding="utf-8") as f:
                    # Create tee outputs for both stdout and stderr
                    sys.stdout = TeeOutput(f, original_stdout)  # type: ignore
                    sys.stderr = TeeOutput(f, original_stderr)  # type: ignore

                    return func(*args, **kwargs)
            except Exception:
                return None
            finally:
                opik_optimizer_logger.setLevel(original_level)

                # Restore original stdout and stderr
                sys.stdout = original_stdout
                sys.stderr = original_stderr

                print(f"Console output has been saved to: {file_path}")

        return wrapper

    return decorator
