"""Prompt import functionality."""

import json
from pathlib import Path
from typing import Dict, Optional

import opik
from opik.api_objects.prompt import Prompt, ChatPrompt
from opik.api_objects.prompt.types import PromptType
from rich.console import Console

from .utils import matches_name_pattern

console = Console()


def import_prompts_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
) -> Dict[str, int]:
    """Import prompts from a directory.

    Returns:
        Dictionary with keys: 'prompts', 'prompts_skipped', 'prompts_errors'
    """
    try:
        prompt_files = list(source_dir.glob("prompt_*.json"))

        if not prompt_files:
            console.print("[yellow]No prompt files found in the directory[/yellow]")
            return {"prompts": 0, "prompts_skipped": 0, "prompts_errors": 0}

        imported_count = 0
        skipped_count = 0
        error_count = 0
        for prompt_file in prompt_files:
            try:
                with open(prompt_file, "r", encoding="utf-8") as f:
                    prompt_data = json.load(f)

                # Handle two export formats:
                # 1. {"name": "...", "current_version": {...}, "history": [...]} - from export_single_prompt
                # 2. {"prompt": {"name": "...", ...}, "current_version": {...}, "history": [...]} - from export_experiment_prompts
                prompt_name = prompt_data.get("name") or (
                    prompt_data.get("prompt", {}).get("name")
                    if prompt_data.get("prompt")
                    else None
                )

                # Check if name is missing or empty
                if not prompt_name or (
                    isinstance(prompt_name, str) and not prompt_name.strip()
                ):
                    console.print(
                        f"[yellow]Skipping {prompt_file.name} (no name found)[/yellow]"
                    )
                    skipped_count += 1
                    continue

                # Strip whitespace from name
                prompt_name = prompt_name.strip()

                # Filter by name pattern if specified
                if name_pattern and not matches_name_pattern(prompt_name, name_pattern):
                    if debug:
                        console.print(
                            f"[blue]Skipping prompt {prompt_name} (doesn't match pattern)[/blue]"
                        )
                    skipped_count += 1
                    continue

                if dry_run:
                    console.print(f"[blue]Would import prompt: {prompt_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing prompt: {prompt_name}[/blue]")

                # Get current version data
                current_version = prompt_data.get("current_version", {})
                prompt_content = current_version.get("prompt")
                metadata = current_version.get("metadata")
                prompt_type = current_version.get("type")
                template_structure = current_version.get("template_structure", "text")

                # Validate prompt content exists
                if prompt_content is None:
                    console.print(
                        f"[yellow]Skipping {prompt_name} (no prompt content found)[/yellow]"
                    )
                    skipped_count += 1
                    continue

                # Convert string type to PromptType enum if needed
                if prompt_type and isinstance(prompt_type, str):
                    try:
                        prompt_type_enum = PromptType(prompt_type)
                    except ValueError:
                        console.print(
                            f"[yellow]Unknown prompt type '{prompt_type}', using MUSTACHE[/yellow]"
                        )
                        prompt_type_enum = PromptType.MUSTACHE
                else:
                    prompt_type_enum = PromptType.MUSTACHE

                # Create the prompt based on template_structure
                try:
                    if template_structure == "chat":
                        # ChatPrompt expects a list of message dictionaries
                        if not isinstance(prompt_content, list):
                            console.print(
                                f"[yellow]Skipping {prompt_name} (chat prompt content must be a list of messages)[/yellow]"
                            )
                            skipped_count += 1
                            continue

                        # Create ChatPrompt
                        ChatPrompt(
                            name=prompt_name,
                            messages=prompt_content,
                            metadata=metadata,
                            type=prompt_type_enum,
                        )
                        if debug:
                            console.print(
                                f"[green]Imported chat prompt: {prompt_name}[/green]"
                            )
                    else:
                        # Text Prompt expects a string
                        if not isinstance(prompt_content, str):
                            console.print(
                                f"[yellow]Skipping {prompt_name} (text prompt content must be a string)[/yellow]"
                            )
                            skipped_count += 1
                            continue

                        # Create Prompt
                        Prompt(
                            name=prompt_name,
                            prompt=prompt_content,
                            metadata=metadata,
                            type=prompt_type_enum,
                        )
                        if debug:
                            console.print(
                                f"[green]Imported text prompt: {prompt_name}[/green]"
                            )

                    imported_count += 1

                except Exception as e:
                    console.print(
                        f"[red]Error creating prompt {prompt_name}: {e}[/red]"
                    )
                    error_count += 1
                    continue

            except Exception as e:
                console.print(
                    f"[red]Error importing prompt from {prompt_file}: {e}[/red]"
                )
                error_count += 1
                continue

        return {
            "prompts": imported_count,
            "prompts_skipped": skipped_count,
            "prompts_errors": error_count,
        }

    except Exception as e:
        console.print(f"[red]Error importing prompts: {e}[/red]")
        return {"prompts": 0, "prompts_skipped": 0, "prompts_errors": 1}
