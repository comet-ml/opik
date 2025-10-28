"""Prompt export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

import click
from rich.console import Console

import opik
from opik.api_objects.prompt.prompt import Prompt
from opik.cli.export_utils import (
    debug_print,
    prompt_to_csv_rows,
    should_skip_file,
    write_csv_data,
    write_json_data,
    print_export_summary,
)

console = Console()


def export_single_prompt(
    client: opik.Opik,
    prompt: Prompt,
    output_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single prompt."""
    try:
        # Check if already exists and force is not set
        if format.lower() == "csv":
            prompt_file = output_dir / f"prompts_{prompt.name.replace('/', '_')}.csv"
        else:
            prompt_file = output_dir / f"prompt_{prompt.name.replace('/', '_')}.json"

        if should_skip_file(prompt_file, force):
            if debug:
                debug_print(f"Skipping {prompt.name} (already exists)", debug)
            return 0

        # Get prompt history
        prompt_history = client.get_prompt_history(prompt.name)

        # Create prompt data structure
        prompt_data = {
            "name": prompt.name,
            "current_version": {
                "prompt": prompt.prompt,
                "metadata": prompt.metadata,
                "type": prompt.type if prompt.type else None,
                "commit": prompt.commit,
            },
            "history": [
                {
                    "prompt": version.prompt,
                    "metadata": version.metadata,
                    "type": version.type if version.type else None,
                    "commit": version.commit,
                }
                for version in prompt_history
            ],
            "downloaded_at": datetime.now().isoformat(),
        }

        # Save to file using the appropriate format
        if format.lower() == "csv":
            write_csv_data(prompt_data, prompt_file, prompt_to_csv_rows)
        else:
            write_json_data(prompt_data, prompt_file)

        if debug:
            debug_print(f"Exported prompt: {prompt.name}", debug)
        return 1

    except Exception as e:
        console.print(f"[red]Error exporting prompt {prompt.name}: {e}[/red]")
        return 0


def export_prompt_by_name(
    name: str,
    workspace: str,
    output_path: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a prompt by exact name."""
    try:
        if debug:
            debug_print(f"Exporting prompt: {name}", debug)

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "prompts"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get prompt by exact name
        try:
            prompt = client.get_prompt(name)
            if not prompt:
                console.print(f"[red]Prompt '{name}' not found[/red]")
                return

            if debug:
                debug_print(f"Found prompt by direct lookup: {prompt.name}", debug)
        except Exception as e:
            console.print(f"[red]Prompt '{name}' not found: {e}[/red]")
            return

        # Export the prompt
        exported_count = export_single_prompt(
            client, prompt, output_dir, max_results, force, debug, format
        )

        # Collect statistics for summary
        stats = {
            "prompts": 1 if exported_count > 0 else 0,
            "prompts_skipped": 0 if exported_count > 0 else 1,
        }

        # Show export summary
        print_export_summary(stats, format)

        if exported_count > 0:
            console.print(
                f"[green]Successfully exported prompt '{name}' to {output_dir}[/green]"
            )
        else:
            console.print(
                f"[yellow]Prompt '{name}' already exists (use --force to re-download)[/yellow]"
            )

    except Exception as e:
        console.print(f"[red]Error exporting prompt: {e}[/red]")
        sys.exit(1)


def export_experiment_prompts(
    client: opik.Opik,
    experiment: Any,
    output_dir: Path,
    force: bool,
    debug: bool,
    format: str = "json",
) -> int:
    """Export prompts referenced by an experiment."""
    try:
        if not experiment.prompt_versions:
            return 0

        prompts_dir = output_dir.parent / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)

        exported_count = 0
        for prompt_version in experiment.prompt_versions:
            try:
                debug_print(f"Exporting prompt: {prompt_version.prompt_id}", debug)

                # Get the prompt
                prompt = client.get_prompt(prompt_version.prompt_id)
                if not prompt:
                    if debug:
                        console.print(
                            f"[yellow]Warning: Prompt {prompt_version.prompt_id} not found[/yellow]"
                        )
                    continue

                # Get prompt history
                prompt_history = client.get_prompt_history(prompt_version.prompt_id)

                # Create prompt data structure
                prompt_data = {
                    "prompt": {
                        "id": getattr(prompt, "id", None),
                        "name": prompt.name,
                        "description": getattr(prompt, "description", None),
                        "created_at": (
                            created_at.isoformat()
                            if (created_at := getattr(prompt, "created_at", None))
                            else None
                        ),
                        "last_updated_at": (
                            last_updated_at.isoformat()
                            if (
                                last_updated_at := getattr(
                                    prompt, "last_updated_at", None
                                )
                            )
                            else None
                        ),
                    },
                    "current_version": {
                        "prompt": prompt.prompt,
                        "metadata": prompt.metadata,
                        "type": prompt.type if prompt.type else None,
                        "commit": prompt.commit,
                    },
                    "history": [
                        {
                            "prompt": version.prompt,
                            "metadata": version.metadata,
                            "type": version.type if version.type else None,
                            "commit": version.commit,
                        }
                        for version in prompt_history
                    ],
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save prompt data using the appropriate format
                if format.lower() == "csv":
                    prompt_file = (
                        prompts_dir
                        / f"prompts_{prompt.name or getattr(prompt, 'id', 'unknown')}.csv"
                    )
                else:
                    prompt_file = (
                        prompts_dir
                        / f"prompt_{prompt.name or getattr(prompt, 'id', 'unknown')}.json"
                    )
                if not prompt_file.exists() or force:
                    if format.lower() == "csv":
                        write_csv_data(prompt_data, prompt_file, prompt_to_csv_rows)
                    else:
                        write_json_data(prompt_data, prompt_file)

                    if debug:
                        console.print(
                            f"[green]Exported prompt: {prompt.name or getattr(prompt, 'id', 'unknown')}[/green]"
                        )
                    exported_count += 1
                else:
                    debug_print(
                        f"Skipping prompt {prompt.name} (already exists)", debug
                    )
                    exported_count += 1  # Count as exported even if skipped

            except Exception as e:
                if debug:
                    console.print(
                        f"[yellow]Warning: Could not export prompt {prompt_version.prompt_id}: {e}[/yellow]"
                    )
                continue

        return exported_count

    except Exception as e:
        if debug:
            console.print(
                f"[yellow]Warning: Could not export experiment prompts: {e}[/yellow]"
            )
        return 0


def export_related_prompts_by_name(
    client: opik.Opik,
    experiment: Any,
    output_dir: Path,
    force: bool,
    debug: bool,
    format: str = "json",
) -> int:
    """Export prompts that might be related to the experiment by name or content."""
    try:
        prompts_dir = output_dir.parent / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)

        # Get all prompts in the workspace
        all_prompts = client.search_prompts()
        debug_print(f"Found {len(all_prompts)} total prompts in workspace", debug)

        # Look for prompts that might be related to this experiment
        related_prompts = []
        experiment_name = experiment.name or ""
        experiment_id = experiment.id or ""

        for prompt in all_prompts:
            prompt_name = prompt.name.lower()
            is_related = False

            # Check if prompt name contains experiment keywords
            if any(
                keyword in prompt_name
                for keyword in ["mcp", "evaluation", "experiment"]
            ):
                is_related = True
            elif any(
                keyword in prompt_name for keyword in experiment_name.lower().split("-")
            ):
                is_related = True
            elif any(
                keyword in prompt_name for keyword in experiment_id.lower().split("-")
            ):
                is_related = True

            if is_related:
                related_prompts.append(prompt)
                if debug:
                    console.print(
                        f"[blue]Found potentially related prompt: {prompt.name}[/blue]"
                    )

        if not related_prompts:
            debug_print("No related prompts found by name matching", debug)
            return 0

        console.print(
            f"[blue]Exporting {len(related_prompts)} potentially related prompts...[/blue]"
        )

        exported_count = 0
        # Export each related prompt
        for prompt in related_prompts:
            try:
                debug_print(f"Exporting related prompt: {prompt.name}", debug)

                # Get prompt history
                prompt_history = client.get_prompt_history(prompt.name)

                # Create prompt data structure
                prompt_data = {
                    "prompt": {
                        "id": getattr(prompt, "__internal_api__prompt_id__", None),
                        "name": prompt.name,
                        "description": getattr(prompt, "description", None),
                        "created_at": getattr(prompt, "created_at", None),
                        "last_updated_at": getattr(prompt, "last_updated_at", None),
                    },
                    "current_version": {
                        "prompt": prompt.prompt,
                        "metadata": prompt.metadata,
                        "type": prompt.type if prompt.type else None,
                        "commit": prompt.commit,
                    },
                    "history": [
                        {
                            "prompt": version.prompt,
                            "metadata": version.metadata,
                            "type": version.type if version.type else None,
                            "commit": version.commit,
                        }
                        for version in prompt_history
                    ],
                    "downloaded_at": datetime.now().isoformat(),
                    "related_to_experiment": experiment.name or experiment.id,
                }

                # Save prompt data using the appropriate format
                if format.lower() == "csv":
                    prompt_file = prompts_dir / f"prompts_{prompt.name}.csv"
                else:
                    prompt_file = prompts_dir / f"prompt_{prompt.name}.json"
                if not prompt_file.exists() or force:
                    if format.lower() == "csv":
                        write_csv_data(prompt_data, prompt_file, prompt_to_csv_rows)
                    else:
                        write_json_data(prompt_data, prompt_file)

                    console.print(
                        f"[green]Exported related prompt: {prompt.name}[/green]"
                    )
                    exported_count += 1
                else:
                    debug_print(
                        f"Skipping prompt {prompt.name} (already exists)", debug
                    )
                    exported_count += 1  # Count as exported even if skipped

            except Exception as e:
                if debug:
                    console.print(
                        f"[yellow]Warning: Could not export related prompt {prompt.name}: {e}[/yellow]"
                    )
                continue

        return exported_count

    except Exception as e:
        if debug:
            console.print(
                f"[yellow]Warning: Could not export related prompts: {e}[/yellow]"
            )
        return 0


@click.command(name="prompt")
@click.argument("name", type=str)
@click.option(
    "--max-results",
    type=int,
    help="Maximum number of prompts to export. Limits the total number of prompts downloaded.",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Re-download items even if they already exist locally.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
)
@click.pass_context
def export_prompt_command(
    ctx: click.Context,
    name: str,
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a prompt by exact name to workspace/prompts."""
    # Get workspace from context
    workspace = ctx.obj["workspace"]
    export_prompt_by_name(name, workspace, path, max_results, force, debug, format)
