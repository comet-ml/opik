from typing import List

import opik
import pytest

import rich.console
import rich.panel
import rich.table
import rich.text

import opik.url_helpers


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
