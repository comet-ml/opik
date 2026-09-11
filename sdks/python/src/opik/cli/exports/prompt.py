"""Prompt export functionality."""

import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, List, Optional, Union

import click

import opik
from opik.api_objects.prompt import Prompt, ChatPrompt
from .utils import (
    console,
    debug_print,
    prepare_project_export_dir,
    prompt_to_csv_rows,
    should_skip_file,
    write_csv_data,
    write_json_data,
    print_export_summary,
)

LOGGER = logging.getLogger(__name__)


def _get_prompt_content(prompt: Any) -> Any:
    """Extract prompt content based on prompt type.

    Args:
        prompt: A Prompt or ChatPrompt instance

    Returns:
        For Prompt: the prompt string
        For ChatPrompt: the template (list of message dicts)
        Otherwise: None
    """
    if isinstance(prompt, Prompt):
        return prompt.prompt
    elif isinstance(prompt, ChatPrompt):
        return prompt.template
    return None


def _get_template_structure(prompt: Any) -> str:
    """Get template_structure based on prompt type.

    Args:
        prompt: A Prompt or ChatPrompt instance

    Returns:
        "text" for Prompt, "chat" for ChatPrompt, "text" as default
    """
    if isinstance(prompt, ChatPrompt):
        return "chat"
    elif isinstance(prompt, Prompt):
        return "text"
    return "text"


def _get_prompt_type_string(prompt: Any) -> Optional[str]:
    """Get prompt type as uppercase string.

    Args:
        prompt: A Prompt or ChatPrompt instance

    Returns:
        Uppercase type string (e.g., "JINJA2", "MUSTACHE") or None
    """
    prompt_type = getattr(prompt, "type", None)
    if prompt_type is None:
        return None

    # If it's an enum, get the value and convert to uppercase
    if hasattr(prompt_type, "value"):
        return prompt_type.value.upper()
    # If it's already a string, convert to uppercase
    if isinstance(prompt_type, str):
        return prompt_type.upper()
    # Otherwise, convert to string and uppercase
    return str(prompt_type).upper()


def _build_version_data(prompt: Any) -> dict:
    """Build the serialized per-version dict shared by every prompt export path.

    Keeping this in one place ensures ``current_version`` and each ``history``
    entry stay in sync when the exported metadata changes.
    """
    return {
        "prompt": _get_prompt_content(prompt),
        "metadata": getattr(prompt, "metadata", None),
        "type": _get_prompt_type_string(prompt),
        "commit": getattr(prompt, "commit", None),
        "template_structure": _get_template_structure(prompt),
        "tags": getattr(prompt, "tags", None),
    }


def _resolve_prompt_tags(
    client: opik.Opik,
    prompt: Union[Prompt, ChatPrompt],
    project_name: str,
) -> Optional[List[str]]:
    """Return the prompt's container-level tags.

    Tags live on the prompt container, and ``get_prompt`` /
    ``retrieve_prompt_version`` do not surface them (they come back empty).
    ``search_prompts`` injects the container tags, so fall back to it whenever
    the directly-fetched prompt object carries none.
    """
    tags = getattr(prompt, "tags", None)
    if tags:
        return tags

    name = getattr(prompt, "name", None)
    if not name:
        return tags

    try:
        candidates = client.search_prompts(
            filter_string=f'name = "{name}"', project_name=project_name
        )
    except Exception:
        # Fall back to an unfiltered scan if the name filter can't be parsed
        # (e.g. names with characters the query language rejects). Log with a
        # stack trace so the failure is diagnosable rather than silent.
        LOGGER.debug(
            "Filtered search_prompts failed for prompt %r; retrying unfiltered",
            name,
            exc_info=True,
        )
        try:
            candidates = client.search_prompts(project_name=project_name)
        except Exception:
            LOGGER.warning(
                "Could not resolve container-level tags for prompt %r via "
                "search_prompts; exported tags may be incomplete",
                name,
                exc_info=True,
            )
            return tags

    for candidate in candidates:
        if getattr(candidate, "name", None) == name:
            return getattr(candidate, "tags", None) or tags
    return tags


def _safe_prompt_history(
    client: opik.Opik,
    prompt: Union[Prompt, ChatPrompt],
    project_name: str,
) -> List[Union[Prompt, ChatPrompt]]:
    try:
        if isinstance(prompt, ChatPrompt):
            return list(
                client.get_chat_prompt_history(prompt.name, project_name=project_name)
            )
        return list(client.get_prompt_history(prompt.name, project_name=project_name))
    except Exception as e:
        console.print(
            f"[yellow]Warning: Could not fetch history for prompt '{prompt.name}': {e}. "
            "Exporting current version only.[/yellow]"
        )
        return []


def _build_prompt_export_data(
    client: opik.Opik,
    prompt: Union[Prompt, ChatPrompt],
    prompt_history: List[Union[Prompt, ChatPrompt]],
    project_name: str,
    base: dict,
    extra: Optional[dict] = None,
) -> dict:
    """Assemble the serialized prompt payload shared by every export path.

    ``base`` carries the path-specific identity fields (a flat ``id``/``name`` or
    a nested ``prompt`` block), ``extra`` any additional top-level keys. The
    shared ``current_version``/``history``/``downloaded_at`` block — including the
    container-level tag resolution — lives here so the exported schema stays in
    sync across paths and tags cannot be dropped on one of them.
    """
    current_version = _build_version_data(prompt)
    current_version["tags"] = _resolve_prompt_tags(client, prompt, project_name)
    prompt_data = {
        **base,
        "current_version": current_version,
        "history": [_build_version_data(version) for version in prompt_history],
        "downloaded_at": datetime.now().isoformat(),
    }
    if extra:
        prompt_data.update(extra)
    return prompt_data


def _write_prompt_export_file(
    prompt_data: dict,
    prompt_file: Path,
    format: str,
) -> None:
    """Write a prompt payload to disk in the requested format."""
    if format.lower() == "csv":
        write_csv_data(prompt_data, prompt_file, prompt_to_csv_rows)
    else:
        write_json_data(prompt_data, prompt_file)


def export_single_prompt(
    client: opik.Opik,
    prompt: Union[Prompt, ChatPrompt],
    output_dir: Path,
    project_name: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single prompt. The file is named by prompt ID; the human name
    is stored inside the file."""
    try:
        # File is keyed by prompt ID (the name lives inside the file).
        prompt_id = prompt.id
        ext = "csv" if format.lower() == "csv" else "json"
        prompt_file = output_dir / f"prompt_{prompt_id}.{ext}"

        if should_skip_file(prompt_file, force):
            if debug:
                debug_print(f"Skipping {prompt.name} (already exists)", debug)
            return 0

        # Get prompt history (scoped to the prompt's project)
        prompt_history = _safe_prompt_history(client, prompt, project_name)

        # Create prompt data structure. Tags are container-level and not
        # surfaced by the direct lookup; the shared helper resolves them.
        prompt_data = _build_prompt_export_data(
            client,
            prompt,
            prompt_history,
            project_name,
            base={"id": prompt_id, "name": prompt.name},
        )

        # Save to file using the appropriate format
        _write_prompt_export_file(prompt_data, prompt_file, format)

        if debug:
            debug_print(f"Exported prompt: {prompt.name}", debug)
        return 1

    except Exception as e:
        console.print(f"[red]Error exporting prompt {prompt.name}: {e}[/red]")
        return 0


def export_prompt_by_name(
    name: str,
    workspace: str,
    project_name: str,
    output_path: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
) -> None:
    """Export a prompt by exact name from the given project."""
    try:
        if debug:
            debug_print(f"Exporting prompt: {name}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Resolve the project to its ID and create projects/<id>/prompts.
        _project_id, project_dir = prepare_project_export_dir(
            client, output_path, workspace, project_name
        )
        output_dir = project_dir / "prompts"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get prompt by exact name within the project
        # Try ChatPrompt first, then regular Prompt
        prompt: Optional[Union[Prompt, ChatPrompt]] = None
        try:
            prompt = client.get_chat_prompt(name, project_name=project_name)
            if debug and prompt:
                debug_print(f"Found ChatPrompt by direct lookup: {prompt.name}", debug)
        except Exception:
            # Not a ChatPrompt, try regular Prompt
            try:
                prompt = client.get_prompt(name, project_name=project_name)
                if not prompt:
                    console.print(f"[red]Prompt '{name}' not found[/red]")
                    return
                if debug:
                    debug_print(f"Found Prompt by direct lookup: {prompt.name}", debug)
            except Exception as e:
                console.print(f"[red]Prompt '{name}' not found: {e}[/red]")
                return

        if prompt is None:
            console.print(f"[red]Prompt '{name}' not found[/red]")
            return

        # Export the prompt
        exported_count = export_single_prompt(
            client, prompt, output_dir, project_name, max_results, force, debug, format
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


def export_prompts_by_ids(
    client: opik.Opik,
    prompt_ids: set[str],
    prompts_dir: Path,
    project_name: str,
    format: str,
    debug: bool,
    force: bool,
) -> tuple[int, int, int]:
    """Export prompts by their IDs.

    Args:
        client: Opik client instance
        prompt_ids: Set of prompt IDs to export
        prompts_dir: Directory to save prompts
        project_name: Project the prompts belong to
        format: Export format ('json' or 'csv')
        debug: Enable debug output
        force: Re-download prompts even if they already exist locally

    Returns:
        Tuple of (exported_count, skipped_count, error_count)
    """
    exported_count = 0
    skipped_count = 0
    error_count = 0

    for prompt_id in prompt_ids:
        try:
            # Get the prompt - try ChatPrompt first, then regular Prompt
            prompt: Optional[Union[Prompt, ChatPrompt]] = None
            try:
                prompt = client.get_chat_prompt(prompt_id, project_name=project_name)
            except Exception:
                # Not a ChatPrompt, try regular Prompt
                prompt = client.get_prompt(prompt_id, project_name=project_name)

            if not prompt:
                if debug:
                    console.print(
                        f"[yellow]Warning: Prompt {prompt_id} not found[/yellow]"
                    )
                continue

            # File is keyed by prompt ID (the name lives inside the file).
            resolved_prompt_id = prompt.id or prompt_id
            ext = "csv" if format.lower() == "csv" else "json"
            prompt_file = prompts_dir / f"prompt_{resolved_prompt_id}.{ext}"

            # Check if file already exists and should be skipped
            if should_skip_file(prompt_file, force):
                if debug:
                    debug_print(
                        f"Skipping prompt {prompt.name or prompt_id} (already exists)",
                        debug,
                    )
                else:
                    console.print(
                        f"[yellow]Skipping prompt: {prompt.name or prompt_id} (already exists)[/yellow]"
                    )
                skipped_count += 1
                continue

            # Get prompt history - use appropriate method based on prompt type
            prompt_history: List[Union[Prompt, ChatPrompt]]
            if isinstance(prompt, ChatPrompt):
                prompt_history = list(
                    client.get_chat_prompt_history(
                        prompt.name, project_name=project_name
                    )
                )
            else:
                prompt_history = list(
                    client.get_prompt_history(prompt_id, project_name=project_name)
                )

            # Create prompt data structure. Tags are container-level and not
            # surfaced by the direct lookup; the shared helper resolves them.
            prompt_data = _build_prompt_export_data(
                client,
                prompt,
                prompt_history,
                project_name,
                base={
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
                },
            )

            # Save prompt data using the appropriate format
            _write_prompt_export_file(prompt_data, prompt_file, format)

            console.print(f"[green]Exported prompt: {prompt.name or prompt_id}[/green]")
            exported_count += 1

        except Exception as e:
            error_count += 1
            if debug:
                console.print(
                    f"[yellow]Warning: Could not export prompt {prompt_id}: {e}[/yellow]"
                )
            else:
                console.print(f"[red]Error exporting prompt {prompt_id}: {e}[/red]")
            continue

    return exported_count, skipped_count, error_count


def export_related_prompts_by_name(
    client: opik.Opik,
    experiment: Any,
    output_dir: Path,
    project_name: str,
    force: bool,
    debug: bool,
    format: str = "json",
) -> int:
    """Export prompts explicitly related to the experiment from experiment metadata."""
    try:
        prompts_dir = output_dir.parent / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)

        # Get experiment data to access metadata
        experiment_data = experiment.get_experiment_data()
        if not experiment_data:
            debug_print("Could not get experiment data", debug)
            return 0

        # Extract prompt names from experiment metadata
        prompt_names = []
        metadata = experiment_data.metadata

        if metadata:
            # Metadata can be a dict, list, or string (JsonListStringPublic)
            # Parse if it's a string, otherwise use directly
            if isinstance(metadata, str):
                try:
                    metadata = json.loads(metadata)
                except (json.JSONDecodeError, Exception) as e:
                    if debug:
                        debug_print(f"Could not parse metadata as JSON: {e}", debug)
                    metadata = None

            # Check if metadata is a dict and has "prompts" key
            if isinstance(metadata, dict) and "prompts" in metadata:
                prompts_dict = metadata["prompts"]
                if isinstance(prompts_dict, dict):
                    # Prompts are stored as a dict with prompt names as keys
                    prompt_names = list(prompts_dict.keys())
                    if debug:
                        debug_print(
                            f"Found {len(prompt_names)} prompt(s) in experiment metadata: {prompt_names}",
                            debug,
                        )
                else:
                    if debug:
                        debug_print(
                            f"Metadata 'prompts' is not a dict, got: {type(prompts_dict)}",
                            debug,
                        )
            else:
                if debug:
                    debug_print("No 'prompts' key found in experiment metadata", debug)

        if not prompt_names:
            debug_print("No prompts found in experiment metadata", debug)
            return 0

        console.print(
            f"[blue]Exporting {len(prompt_names)} prompt(s) from experiment metadata...[/blue]"
        )

        exported_count = 0
        # Export each prompt by name from metadata
        for prompt_name in prompt_names:
            try:
                debug_print(f"Exporting prompt: {prompt_name}", debug)

                # Try to get the prompt - try ChatPrompt first, then regular Prompt
                prompt: Optional[Union[Prompt, ChatPrompt]] = None
                try:
                    prompt = client.get_chat_prompt(
                        prompt_name, project_name=project_name
                    )
                except Exception:
                    # Not a ChatPrompt, try regular Prompt
                    try:
                        prompt = client.get_prompt(
                            prompt_name, project_name=project_name
                        )
                    except Exception as e:
                        if debug:
                            console.print(
                                f"[yellow]Warning: Could not get prompt '{prompt_name}': {e}[/yellow]"
                            )
                        continue

                if not prompt:
                    if debug:
                        console.print(
                            f"[yellow]Warning: Prompt '{prompt_name}' not found[/yellow]"
                        )
                    continue

                # Get prompt history - use appropriate method based on prompt type
                prompt_history = _safe_prompt_history(client, prompt, project_name)

                # Create prompt data structure. Tags are container-level and not
                # surfaced by the direct lookup; the shared helper resolves them.
                prompt_data = _build_prompt_export_data(
                    client,
                    prompt,
                    prompt_history,
                    project_name,
                    base={
                        "prompt": {
                            "id": getattr(prompt, "__internal_api__prompt_id__", None),
                            "name": prompt.name,
                            "description": getattr(prompt, "description", None),
                            "created_at": getattr(prompt, "created_at", None),
                            "last_updated_at": getattr(prompt, "last_updated_at", None),
                        },
                    },
                    extra={"related_to_experiment": experiment.name or experiment.id},
                )

                # File is keyed by prompt ID (the name lives inside the file).
                ext = "csv" if format.lower() == "csv" else "json"
                prompt_file = prompts_dir / f"prompt_{prompt.id}.{ext}"

                # Check if file should be skipped using the standard utility
                if should_skip_file(prompt_file, force):
                    debug_print(
                        f"Skipping prompt {prompt.name} (already exists)", debug
                    )
                    continue

                # File doesn't exist or force is set, so export it
                _write_prompt_export_file(prompt_data, prompt_file, format)

                console.print(f"[green]Exported related prompt: {prompt.name}[/green]")
                exported_count += 1

            except Exception as e:
                if debug:
                    prompt_display_name = prompt.name if prompt else prompt_name
                    console.print(
                        f"[yellow]Warning: Could not export related prompt {prompt_display_name}: {e}[/yellow]"
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
    default="opik_exports",
    help="Directory to save exported data. Defaults to opik_exports.",
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
    """Export a prompt by exact name to projects/<project>/prompts."""
    # Get workspace, project, and API key from context
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_prompt_by_name(
        name, workspace, project_name, path, max_results, force, debug, format, api_key
    )
