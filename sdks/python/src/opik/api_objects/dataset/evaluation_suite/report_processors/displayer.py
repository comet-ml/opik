"""Console display for evaluation suite results."""

from __future__ import annotations

from collections import defaultdict
from typing import Dict, Optional, TYPE_CHECKING

from rich import align, console, panel, table, text

from ..evaluation_suite_result import is_score_passed

if TYPE_CHECKING:
    from .. import evaluation_suite_result as _result_mod


def _format_time(seconds: float) -> str:
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{int(hours):02d}:{int(minutes):02d}:{int(seconds):02d}"


def display_suite_results(
    suite_result: _result_mod.EvaluationSuiteResult,
    verbose: int,
    report_path: Optional[str],
) -> None:
    suite_name = suite_result.suite_name or "Evaluation Suite"
    total_time = suite_result.total_time or 0.0
    experiment_url = suite_result.experiment_url
    test_results = [
        test_result
        for item_result in suite_result.item_results.values()
        for test_result in item_result.test_results
    ]
    nb_runs = len(test_results)
    nb_items = suite_result.items_total
    items_passed = suite_result.items_passed
    items_total = suite_result.items_total
    suite_passed = suite_result.all_items_passed

    assertion_passed_count: Dict[str, int] = defaultdict(int)
    assertion_total_count: Dict[str, int] = defaultdict(int)

    for test_result_ in test_results:
        for score in test_result_.score_results:
            assertion_total_count[score.name] += 1
            if is_score_passed(score):
                assertion_passed_count[score.name] += 1

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

    if experiment_url is not None:
        link_text = text.Text.from_markup(
            f"[bold cyan][link={experiment_url}]View results in Opik dashboard[/link][/bold cyan]"
        )
        content.add_row(align.Align.left(link_text))

    if report_path is not None:
        report_text = text.Text.from_markup(
            f"[bold green][link=file://{report_path}]View local detailed report file[/link][/bold green]"
        )
        content.add_row(align.Align.left(report_text))

    if experiment_url is not None or report_path is not None:
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
