from typing import List

import opik
import pytest

import rich.console
import rich.panel
import rich.table
import rich.text

import opik.url_helpers
from . import test_runs_storage


def print(reports: List[pytest.TestReport]) -> None:
    opik_ui_url = opik.url_helpers.get_ui_url()

    console = rich.console.Console()

    total_tests = len(reports)
    passed_tests = len([report for report in reports if report.passed])
    failed_tests = len([report for report in reports if report.failed])

    table = rich.table.Table(show_header=False, show_lines=False, padding=(0, 1))
    table.add_column(justify="left")
    table.add_column(justify="left")

    table.add_row("Passed:", f"{passed_tests}", style="green")
    table.add_row("Failed:", f"{failed_tests}", style="red")
    table.add_row("Total:", f"{total_tests}")

    episode_results = test_runs_storage.TEST_RUNS_EPISODES
    if episode_results:
        episode_passed = 0
        failed_scenarios = []
        reports_by_nodeid = {report.nodeid: report for report in reports}
        for nodeid, episode in episode_results.items():
            report = reports_by_nodeid.get(nodeid)
            report_passed = bool(report.passed) if report is not None else False
            is_passing = episode.is_passing() and report_passed
            if is_passing:
                episode_passed += 1
            else:
                failed_scenarios.append(episode.scenario_id)

        table.add_row()
        table.add_row("Episodes:", f"{len(episode_results)}")
        table.add_row("Episodes passed:", f"{episode_passed}", style="green")
        table.add_row(
            "Episodes failed:",
            f"{len(episode_results) - episode_passed}",
            style="red",
        )
        if failed_scenarios:
            preview = ", ".join(failed_scenarios[:5])
            if len(failed_scenarios) > 5:
                preview += ", ..."
            table.add_row("Failed scenarios:", preview)

    table.add_row()
    table.add_row("See the results:", f"[underline blue]{opik_ui_url}[/]")

    panel = rich.panel.Panel(
        table,
        title="[bold]Opik: LLM Test Results[/bold]",
        title_align="left",
        expand=False,
    )

    console.print("\n")
    console.print(panel)
