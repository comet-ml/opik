"""Smoke test functionality for Opik CLI."""

import os
import random
import string
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Optional
from unittest.mock import patch

import click
from rich.console import Console

import opik
from opik import Attachment, opik_context, track

from .opik_logo import OPIK_LOGO_PNG

console = Console()


def generate_random_string(length: int = 100) -> str:
    """Generate a random string of specified length."""
    return "".join(random.choices(string.ascii_letters + string.digits, k=length))


def create_opik_logo_image() -> Path:
    """
    Create the Opik logo image from embedded byte array.
    Returns a Path to the temporary image file.
    """
    with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as tmp:
        tmp.write(OPIK_LOGO_PNG)
        temp_path = Path(tmp.name)
    return temp_path


@contextmanager
def _temporary_client_context(client: opik.Opik) -> Iterator[None]:
    """
    Context manager that temporarily replaces the cached client with the provided client.

    This ensures that opik.start_as_current_trace and other functions that use
    get_client_cached() will use the explicitly created client instance.

    Args:
        client: The Opik client instance to use temporarily

    Yields:
        None
    """
    import opik.api_objects.opik_client as opik_client_module

    # Create a function that always returns our client
    def get_client_cached_replacement() -> opik.Opik:
        return client

    # Clear the lru_cache to ensure we get a fresh client
    opik_client_module.get_client_cached.cache_clear()

    # Patch get_client_cached in the opik.api_objects.opik_client module
    # This will affect all modules that import get_client_cached from there
    with patch(
        "opik.api_objects.opik_client.get_client_cached", get_client_cached_replacement
    ):
        try:
            yield
        finally:
            # Restore is handled by the patch context manager
            pass


def run_smoke_test(
    workspace: str,
    project_name: str = "smoke-test-project",
    api_key: Optional[str] = None,
    debug: bool = False,
) -> None:
    """
    Run a smoke test to verify Opik integration is working correctly.

    This function performs a basic sanity test by:
    - Creating a trace with random data
    - Adding a tracked LLM completion span
    - Attaching a dynamically generated test image
    - Verifying data is sent to Opik

    Args:
        workspace: The workspace name to use for the smoke test.
        project_name: Project name for the smoke test. Defaults to 'smoke-test-project'.
        api_key: Optional API key. If not provided, will use environment variable or configuration.
        debug: Whether to print debug information on errors.

    Raises:
        click.ClickException: If the smoke test failed.
    """
    temp_image_path: Optional[Path] = None
    client: Optional[opik.Opik] = None
    original_exception: Optional[Exception] = None

    try:
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

        # Temporarily replace the cached client with our explicit client
        # This ensures start_as_current_trace uses the same client instance
        with _temporary_client_context(client):
            # Create a trace using context manager
            # Use flush=True to ensure the client flushes when trace ends
            with opik.start_as_current_trace(
                name="smoke-test", project_name=project_name, flush=True
            ):
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
                    opik_context.update_current_span(
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

        console.print("\n[bold green]Smoke test completed successfully![/bold green]")
        console.print(
            f"[dim]Check your Opik dashboard at workspace '{workspace}' "
            f"and project '{project_name}' to verify the trace was created.[/dim]"
        )

        # Note: Temporary image file is not deleted here to ensure it remains
        # available during async upload. The OS will clean it up automatically.

    except Exception as e:
        original_exception = e
        console.print(f"[red]Error during smoke test:[/red] {e}")
        if debug:
            import traceback

            console.print("[red]Traceback:[/red]")
            console.print(traceback.format_exc())
    finally:
        # Ensure client cleanup always runs, even if an exception occurred
        if client is not None:
            try:
                client.flush()
                client.end()
                if original_exception is None:
                    console.print("[green]✓[/green] Flushed data to Opik")
            except Exception as cleanup_error:
                if debug:
                    console.print(
                        f"[yellow]Warning: Error during client cleanup:[/yellow] {cleanup_error}"
                    )
                    import traceback

                    console.print("[yellow]Cleanup traceback:[/yellow]")
                    console.print(traceback.format_exc())

    # Re-raise the original exception if one occurred
    if original_exception is not None:
        raise click.ClickException(f"Smoke test failed: {original_exception}")
