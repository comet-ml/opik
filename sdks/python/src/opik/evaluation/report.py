from collections import defaultdict
from typing import Dict, List, Optional, Tuple

from rich import align, console, panel, table, text


from opik.api_objects.dataset.evaluation_suite import types as suite_types

from . import test_result, evaluation_result
from .metrics import score_result


def _format_time(seconds: float) -> str:
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{int(hours):02d}:{int(minutes):02d}:{int(seconds):02d}"


def _compute_average_scores(
    test_results: List[test_result.TestResult],
) -> Tuple[Dict[str, str], Dict[str, int]]:
    # Calculate average scores
    score_totals: Dict[str, float] = defaultdict(float)
    score_counts: Dict[str, int] = defaultdict(int)
    score_failed: Dict[str, int] = defaultdict(int)

    for result in test_results:
        for score in result.score_results:
            if score.scoring_failed:
                score_failed[score.name] += 1
                score_counts[score.name] += 0
            else:
                score_totals[score.name] += score.value
                score_counts[score.name] += 1
                score_failed[score.name] += 0

    average_scores = {
        name: "None" if counts == 0 else f"{score_totals[name] / counts:.4f}"
        for name, counts in score_counts.items()
    }
    failed_scores = {name: score_failed[name] for name in score_counts.keys()}

    return average_scores, failed_scores


def display_experiment_results(
    dataset_name: str,
    total_time: float,
    test_results: List[test_result.TestResult],
    experiment_scores: Optional[List[score_result.ScoreResult]] = None,
) -> None:
    average_scores, failed_scores = _compute_average_scores(test_results)
    nb_runs = len(test_results)

    # Count unique dataset items (not runs)
    unique_item_ids = {
        result.test_case.dataset_item_id
        for result in test_results
        if result.test_case.dataset_item_id is not None
    }
    nb_items = len(unique_item_ids) if unique_item_ids else nb_runs

    time_text = text.Text(f"Total time:        {_format_time(total_time)}")
    time_text.stylize("bold", 0, 18)
    time_text = align.Align.left(time_text)

    # Show both items and runs if they differ
    if nb_runs != nb_items:
        nb_samples_text = text.Text(
            f"Number of items:   {nb_items:,} ({nb_runs:,} runs)"
        )
    else:
        nb_samples_text = text.Text(f"Number of samples: {nb_items:,}")
    nb_samples_text.stylize("bold", 0, 18)
    nb_samples_text = align.Align.left(nb_samples_text)

    # Create test results text
    score_strings = text.Text("")
    for name, avg_score in average_scores.items():
        score_strings += text.Text(f"{name}: {avg_score} (avg)", style="green bold")
        if failed_scores[name] > 0:
            score_strings += text.Text(f" - {failed_scores[name]} failed", style="red")
        score_strings += text.Text("\n")

    # Add experiment scores if available
    if experiment_scores:
        for score in experiment_scores:
            score_strings += text.Text(
                f"{score.name}: {score.value:.4f}", style="green bold"
            )
            score_strings += text.Text("\n")

    aligned_test_results = align.Align.left(score_strings)

    # Combine table, time text, and test results
    content = table.Table.grid()
    content.add_row(text.Text(""))  # Empty space
    content.add_row(time_text)
    content.add_row(nb_samples_text)
    content.add_row(text.Text(""))
    content.add_row(aligned_test_results)

    # Create panel with content inside
    if nb_runs != nb_items:
        panel_title = f"{dataset_name} ({nb_items} items, {nb_runs} runs)"
    else:
        panel_title = f"{dataset_name} ({nb_items} samples)"
    panel_content = panel.Panel(
        content,
        title=panel_title,
        title_align="left",
        expand=False,
    )

    # Display results
    console_container = console.Console()
    console_container.print(panel_content)
    console_container.print("Uploading results to Opik ... ")


def display_suite_results(
    suite_name: str,
    total_time: float,
    suite_result: suite_types.EvaluationSuiteResult,
    verbose: int = 2,
) -> None:
    test_results = [
        tr
        for item_result in suite_result.item_results.values()
        for tr in item_result.test_results
    ]
    nb_runs = len(test_results)
    nb_items = suite_result.items_total
    items_passed = suite_result.items_passed
    items_total = suite_result.items_total
    suite_passed = suite_result.all_items_passed

    assertion_passed_count: Dict[str, int] = defaultdict(int)
    assertion_total_count: Dict[str, int] = defaultdict(int)

    for tr in test_results:
        for score in tr.score_results:
            assertion_total_count[score.name] += 1
            score_passed = not score.scoring_failed and (
                (isinstance(score.value, bool) and score.value) or score.value == 1
            )
            if score_passed:
                assertion_passed_count[score.name] += 1

    # Build display
    time_text = text.Text(f"Total time:        {_format_time(total_time)}")
    time_text.stylize("bold", 0, 18)
    time_text = align.Align.left(time_text)

    nb_samples_text = text.Text(f"Number of items:   {nb_items:,} ({nb_runs:,} runs)")
    nb_samples_text.stylize("bold", 0, 18)
    nb_samples_text = align.Align.left(nb_samples_text)

    pass_rate = items_passed / items_total if items_total > 0 else None
    if items_total == 0:
        pass_style = "yellow bold"
        pass_label = "NO ITEMS"
    elif suite_passed:
        pass_style = "green bold"
        pass_label = "PASSED"
    else:
        pass_style = "red bold"
        pass_label = "FAILED"
    pass_text = text.Text(f"Suite result:      {pass_label}", style=pass_style)
    pass_text.stylize("bold", 0, 18)
    pass_text = align.Align.left(pass_text)

    items_text = text.Text(f"Items passed:      {items_passed}/{items_total}")
    items_text.stylize("bold", 0, 18)
    items_text = align.Align.left(items_text)

    rate_value = f"{pass_rate:.1%}" if pass_rate is not None else "N/A"
    rate_text = text.Text(f"Pass rate:         {rate_value}")
    rate_text.stylize("bold", 0, 18)
    rate_text = align.Align.left(rate_text)

    # Compute per-item timing averages
    task_times = [
        tr.task_execution_time
        for tr in test_results
        if tr.task_execution_time is not None
    ]
    scoring_times = [
        tr.scoring_time for tr in test_results if tr.scoring_time is not None
    ]

    content = table.Table.grid()
    content.add_row(text.Text(""))
    content.add_row(time_text)
    content.add_row(nb_samples_text)
    content.add_row(pass_text)
    content.add_row(items_text)
    content.add_row(rate_text)

    if task_times and scoring_times:
        avg_task = sum(task_times) / len(task_times)
        avg_scoring = sum(scoring_times) / len(scoring_times)
        avg_total = avg_task + avg_scoring

        avg_task_text = text.Text(f"Avg task time:     {avg_task:.2f}s")
        avg_task_text.stylize("bold", 0, 18)
        avg_scoring_text = text.Text(f"Avg scoring time:  {avg_scoring:.2f}s")
        avg_scoring_text.stylize("bold", 0, 18)
        avg_total_text = text.Text(f"Avg total time:    {avg_total:.2f}s")
        avg_total_text.stylize("bold", 0, 18)

        content.add_row(text.Text(""))
        content.add_row(align.Align.left(avg_task_text))
        content.add_row(align.Align.left(avg_scoring_text))
        content.add_row(align.Align.left(avg_total_text))

    if verbose >= 2 and assertion_total_count:
        # Sort by pass rate ascending (most failed first)
        sorted_assertions = sorted(
            assertion_total_count.keys(),
            key=lambda n: (
                assertion_passed_count[n] / assertion_total_count[n]
                if assertion_total_count[n] > 0
                else 0.0
            ),
        )

        score_strings = text.Text("")
        for name in sorted_assertions:
            passed = assertion_passed_count[name]
            total = assertion_total_count[name]
            rate = passed / total if total > 0 else 0.0
            style = "green bold" if passed == total else "red bold"
            score_strings += text.Text(
                f"{name}: {rate:.0%} passed ({passed}/{total})\n",
                style=style,
            )

        content.add_row(text.Text(""))
        content.add_row(align.Align.left(score_strings))

    panel_content = panel.Panel(
        content,
        title=f"{suite_name} ({nb_items} items, {nb_runs} runs)",
        title_align="left",
        expand=False,
    )

    console_container = console.Console()
    console_container.print(panel_content)
    console_container.print("Uploading results to Opik ... ")


def display_experiment_link(experiment_url: str) -> None:
    console_container = console.Console()

    console_container.print(
        f"View the results [link={experiment_url}]in your Opik dashboard[/link]."
    )


def display_evaluation_scores_statistics(
    dataset_name: str,
    evaluation_results: evaluation_result.EvaluationResult,
) -> None:
    """
    Displays evaluation scores statistics for a given dataset.

    The function generates a summary of evaluation scores including mean, max,
    min, and optionally standard deviation for each metric in the evaluation
    results. The summarized scores are formatted and presented in a table
    within a panel for user clarity.

    Args:
        dataset_name: Name of the dataset for which evaluation statistics are
            being displayed.
        evaluation_results: An object containing evaluation results with
            aggregated scores and statistical data.
    """
    aggregated_view = evaluation_results.aggregate_evaluation_scores()
    if not aggregated_view.aggregated_scores:
        return

    # Create a table for the statistics
    stats_table = table.Table()
    stats_table.add_column("Name", style="cyan", no_wrap=True)
    stats_table.add_column("Mean", justify="right", style="green")
    stats_table.add_column("Min", justify="right", style="yellow")
    stats_table.add_column("Max", justify="right", style="yellow")
    stats_table.add_column("Std", justify="right", style="magenta")

    # Add rows for each metric
    for name, stats in aggregated_view.aggregated_scores.items():
        std_value = f"{stats.std:.4f}" if stats.std is not None else "N/A"
        stats_table.add_row(
            name,
            f"{stats.mean:.4f}",
            f"{stats.min:.4f}",
            f"{stats.max:.4f}",
            std_value,
        )

    # Create a panel with the table inside
    panel_content = panel.Panel(
        stats_table,
        title=f"Evaluation statistics for {dataset_name}",
        title_align="left",
        expand=False,
    )

    # Display results
    console_container = console.Console()
    console_container.print(panel_content)
