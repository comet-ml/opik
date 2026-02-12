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
    episode_results = test_runs_storage.TEST_RUNS_EPISODES

    table = rich.table.Table(show_header=False, show_lines=False, padding=(0, 1))
    table.add_column(justify="left")
    table.add_column(justify="left")

    table.add_row(
        "Passed:", f"{passed_tests}", style="green" if passed_tests > 0 else None
    )
    table.add_row(
        "Failed:", f"{failed_tests}", style="red" if failed_tests > 0 else "dim"
    )
    table.add_row("Total:", f"{total_tests}")

    episode_failed = 0
    if episode_results:
        episode_passed = 0
        failed_scenarios = []
        episode_total = 0
        reports_by_nodeid = {report.nodeid: report for report in reports}
        for nodeid, episode in episode_results.items():
            report = reports_by_nodeid.get(nodeid)
            if report is None:
                continue
            episode_total += 1
            report_passed = bool(report.passed)
            is_passing = episode.is_passing() and report_passed
            if is_passing:
                episode_passed += 1
            else:
                failed_scenarios.append(episode.scenario_id)
                episode_failed += 1

        table.add_row()
        table.add_row("Episodes:", f"{episode_total}")
        table.add_row("Episodes passed:", f"{episode_passed}", style="green")
        table.add_row(
            "Episodes failed:",
            f"{episode_failed}",
            style="red" if episode_failed > 0 else "dim",
        )
        if failed_scenarios:
            preview = ", ".join(failed_scenarios[:5])
            if len(failed_scenarios) > 5:
                preview += ", ..."
            table.add_row("Failed scenarios:", preview)

    overall_failed = failed_tests + episode_failed
    table.add_row()
    table.add_row(
        "Status:",
        "FAIL" if overall_failed > 0 else "PASS",
        style="red" if overall_failed > 0 else "green",
    )

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
