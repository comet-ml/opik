from rich import console, panel, table, text, align
from typing import List, Dict, Tuple
from collections import defaultdict
from . import test_result
from .. import url_helpers


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


def display_experiment_link(dataset_name: str, experiment_id: str) -> None:
    console_container = console.Console()
    experiment_url = url_helpers.get_experiment_url(
        dataset_name=dataset_name, experiment_id=experiment_id
    )
    console_container.print(
        f"View the results [link={experiment_url}]in your Opik dashboard[/link]."
    )
