from collections import defaultdict
from typing import Dict, List, Tuple

from rich import align, console, panel, table, text


from . import test_result, evaluation_result


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
    dataset_name: str, total_time: float, test_results: List[test_result.TestResult]
) -> None:
    average_scores, failed_scores = _compute_average_scores(test_results)
    nb_items = len(test_results)

    time_text = text.Text(f"Total time:        {_format_time(total_time)}")
    time_text.stylize("bold", 0, 18)
    time_text = align.Align.left(time_text)

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

    aligned_test_results = align.Align.left(score_strings)

    # Combine table, time text, and test results
    content = table.Table.grid()
    content.add_row(text.Text(""))  # Empty space
    content.add_row(time_text)
    content.add_row(nb_samples_text)
    content.add_row(text.Text(""))
    content.add_row(aligned_test_results)

    # Create panel with content inside
    panel_content = panel.Panel(
        content,
        title=f"{dataset_name} ({nb_items} samples)",
        title_align="left",
        expand=False,
    )

    # Display results
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
