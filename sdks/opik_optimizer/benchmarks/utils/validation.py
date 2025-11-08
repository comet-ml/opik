import sys

from rich.console import Console
from rich.style import Style

console = Console(
    width=120,
    style=Style(color="white"),
    highlight=True,
    soft_wrap=True,
)


def ask_for_input_confirmation(
    demo_datasets: list[str] | None,
    optimizers: list[str] | None,
    test_mode: bool,
    retry_failed_run_id: str | None,
    resume_run_id: str | None,
) -> None:
    are_default_values = all(
        [
            demo_datasets is None,
            optimizers is None,
            test_mode is False,
            retry_failed_run_id is None,
            resume_run_id is None,
        ]
    )

    # Only prompt if interactive
    if are_default_values and sys.stdin.isatty():
        console.print(
            "\n[bold yellow]No specific benchmark parameters or resume flag provided.[/bold yellow]"
        )
        console.print("This will run ALL datasets and ALL optimizers in full mode.")
        try:
            if (
                input("Are you sure you want to continue? (y/N): ").strip().lower()
                != "y"
            ):
                console.print("Exiting.")
                sys.exit(0)
        except KeyboardInterrupt:
            console.print("\nExiting due to user interruption.")
            sys.exit(0)
