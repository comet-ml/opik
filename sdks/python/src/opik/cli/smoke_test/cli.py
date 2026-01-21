"""Smoke test command for Opik CLI."""

import random
import string
import tempfile
from pathlib import Path
from typing import Optional
import os

import click
from rich.console import Console

import opik
from opik import Attachment, opik_context, track


console = Console()


def generate_random_string(length: int = 100) -> str:
    """Generate a random string of specified length."""
    return "".join(random.choices(string.ascii_letters + string.digits, k=length))


def create_opik_logo_image() -> Path:
    """
    Create the Opik logo image from embedded byte array.
    Returns a Path to the temporary image file.
    """
    from .opik_logo import OPIK_LOGO_PNG

    with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as tmp:
        tmp.write(OPIK_LOGO_PNG)
        temp_path = Path(tmp.name)
    return temp_path


@click.command(name="smoke-test")
@click.argument("workspace", type=str)
@click.option(
    "--project-name",
    type=str,
    default="smoke-test-project",
    help="Project name for the smoke test. Defaults to 'smoke-test-project'.",
)
@click.pass_context
def smoke_test(
    ctx: click.Context,
    workspace: str,
    project_name: str,
) -> None:
    """
    Run a smoke test to verify Opik integration is working correctly.

    This command performs a basic sanity test by:
    - Creating a trace with random data
    - Adding a tracked LLM completion span
    - Attaching a dynamically generated test image
    - Verifying data is sent to Opik

    WORKSPACE: The workspace name to use for the smoke test.

    Examples:

      Run smoke test with default project name:

        opik smoke-test my-workspace

      Run smoke test with custom project name:

        opik smoke-test my-workspace --project-name my-test-project
    """
    temp_image_path: Optional[Path] = None
    try:
        # Get API key from context
        api_key = ctx.obj.get("api_key") if ctx.obj else None

        console.print("[green]Starting Opik smoke test...[/green]")

        # Create Opik client
        os.environ["OPIK_PROJECT_NAME"] = project_name
        client = opik.Opik(
            project_name=project_name,
            workspace=workspace,
            api_key=api_key,
        )

        console.print(f"[cyan]Workspace:[/cyan] {workspace}")
        console.print(f"[cyan]Project:[/cyan] {project_name}")

        # Create a trace using context manager
        with opik.start_as_current_trace(name="smoke-test", project_name=project_name):
            console.print("[green]✓[/green] Created trace")

            # Add random data to trace
            random_data = {f"key_{i}": generate_random_string() for i in range(10)}
            opik_context.update_current_trace(input=random_data)
            console.print("[green]✓[/green] Updated trace with random data")

            # Create Opik logo-like image dynamically
            temp_image_path = create_opik_logo_image()
            console.print("[green]✓[/green] Created Opik logo image")

            # Define a tracked LLM function
            @track(name="llm_completion", type="llm")
            def my_llm_agent(input_text: str) -> str:
                """A simple LLM agent function for testing."""
                # Add the dynamically created image as attachment
                opik_context.update_current_trace(
                    attachments=[
                        Attachment(
                            data=str(temp_image_path),
                            content_type="image/png",
                        )
                    ]
                )
                console.print("[green]✓[/green] Added image attachment")
                return "And this is test output"

            # Call the tracked function
            result = my_llm_agent("This is test input")
            console.print(
                f"[green]✓[/green] Called tracked LLM function, result: {result}"
            )

        console.print("[green]✓[/green] Ended trace")

        # End the client (flush remaining data)
        client.end()
        console.print("[green]✓[/green] Flushed data to Opik")

        console.print("\n[bold green]Smoke test completed successfully![/bold green]")
        console.print(
            f"[dim]Check your Opik dashboard at workspace '{workspace}' "
            f"and project '{project_name}' to verify the trace was created.[/dim]"
        )

        # Note: Temporary image file is not deleted here to ensure it remains
        # available during async upload. The OS will clean it up automatically.

    except Exception as e:
        console.print(f"[red]Error during smoke test:[/red] {e}")
        if ctx.obj and ctx.obj.get("debug", False):
            import traceback

            console.print("[red]Traceback:[/red]")
            console.print(traceback.format_exc())
        raise click.ClickException(f"Smoke test failed: {e}")
