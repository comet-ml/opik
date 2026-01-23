"""Smoke test functionality for Opik CLI."""

import os
import random
import string
import tempfile
import time
import uuid
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


def verify_trace_ingested(
    client: opik.Opik,
    trace_name: str,
    project_name: str,
    timeout_seconds: int = 20,
    debug: bool = False,
) -> bool:
    """
    Verify that a trace was successfully ingested into Opik by polling the backend.

    Args:
        client: The Opik client instance to use for querying.
        trace_name: The name of the trace to search for.
        project_name: The project name where the trace should be found.
        timeout_seconds: Maximum time to wait for the trace (default: 20 seconds).
        debug: Whether to print debug information.

    Returns:
        True if the trace was found, False otherwise.
    """
    try:
        start_time = time.time()
        poll_interval = 0.5  # Start with 0.5 second intervals
        max_poll_interval = 2.0  # Maximum interval between polls

        while time.time() - start_time < timeout_seconds:
            try:
                traces = client.search_traces(
                    project_name=project_name,
                    filter_string=f'name = "{trace_name}"',
                    max_results=10,
                )

                if traces:
                    if debug:
                        console.print(
                            f"[dim]Found {len(traces)} trace(s) matching name '{trace_name}'[/dim]"
                        )
                    return True

                # Exponential backoff: increase poll interval up to max
                time.sleep(min(poll_interval, max_poll_interval))
                poll_interval *= 1.5

            except Exception as e:
                if debug:
                    console.print(
                        f"[yellow]Warning: Error querying traces: {e}[/yellow]"
                    )
                # If querying fails, we can't verify, so return False
                return False

        # Timeout reached without finding trace
        return False
    except Exception as e:
        # Catch any exception that might occur when calling search_traces (e.g., AttributeError
        # if client is a mock without proper setup). Return False to indicate verification failed.
        if debug:
            console.print(
                f"[yellow]Warning: Could not verify trace ingestion: {e}[/yellow]"
            )
        return False


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

    # Generate a unique trace name for this run to avoid false positives
    # from older traces with the same name. This ensures deterministic usage
    # throughout the function execution.
    trace_name = f"smoke-test-{uuid.uuid4().hex[:8]}"

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
                name=trace_name, project_name=project_name, flush=True
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
                # Only print success messages after flush/end complete successfully
                if original_exception is None:
                    console.print("[green]✓[/green] Flushed data to Opik")

                    # Verify trace was ingested by querying the backend
                    # trace_name was generated at the start of the function to ensure uniqueness
                    try:
                        console.print("[dim]Verifying trace ingestion...[/dim]")
                        trace_found = verify_trace_ingested(
                            client=client,
                            trace_name=trace_name,
                            project_name=project_name,
                            timeout_seconds=20,
                            debug=debug,
                        )

                        if trace_found:
                            console.print(
                                "\n[bold green]Smoke test completed successfully![/bold green]"
                            )
                            console.print(
                                f"[dim]Trace '{trace_name}' verified in workspace '{workspace}' "
                                f"and project '{project_name}'.[/dim]"
                            )
                        else:
                            console.print(
                                "\n[yellow]Smoke test data flushed to Opik, but trace verification timed out.[/yellow]"
                            )
                            console.print(
                                f"[dim]Data may still be processing. Please verify in the Opik dashboard "
                                f"at workspace '{workspace}' and project '{project_name}'.[/dim]"
                            )
                    except Exception as verification_error:
                        # If verification fails (e.g., API errors), print advisory message
                        if debug:
                            console.print(
                                f"[yellow]Warning: Could not verify trace ingestion: {verification_error}[/yellow]"
                            )
                        console.print(
                            "\n[yellow]Smoke test data flushed to Opik — please verify in the dashboard.[/yellow]"
                        )
                        console.print(
                            f"[dim]Check your Opik dashboard at workspace '{workspace}' "
                            f"and project '{project_name}' to verify the trace was created.[/dim]"
                        )
            except Exception as cleanup_error:
                # Treat flush/end failures as test failures
                if original_exception is None:
                    original_exception = cleanup_error
                console.print(
                    f"[red]Error flushing data to Opik:[/red] {cleanup_error}"
                )
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
