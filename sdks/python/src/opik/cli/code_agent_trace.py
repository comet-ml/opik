"""CLI command for uploading Agent Trace data to Opik."""

import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, cast

import click
from rich.console import Console
from rich.table import Table

from opik.integrations.code_agent_trace import log_code_agent_turn, flush
from opik.integrations.code_agent_trace.types import TraceRecord

console = Console()

CODE_AGENT_TRACE_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}

DEFAULT_TRACES_FILE = ".agent-trace/traces.jsonl"


def _read_traces_file(path: Path) -> List[Dict[str, Any]]:
    """Read and parse a traces.jsonl file."""
    records: List[Dict[str, Any]] = []
    with open(path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                console.print(
                    f"[yellow]Warning: Skipping invalid JSON on line {line_num}: {e}[/yellow]"
                )
    return records


def _get_conversation_id(record: Dict[str, Any]) -> Optional[str]:
    """Get conversation_id from record (supports both old and new formats)."""
    conv_id = record.get("conversation_id")
    if not conv_id:
        metadata = record.get("metadata") or {}
        conv_id = metadata.get("conversation_id")
    return conv_id


def _get_generation_id(record: Dict[str, Any]) -> Optional[str]:
    """Get generation_id from record (supports both old and new formats)."""
    gen_id = record.get("generation_id")
    if not gen_id:
        metadata = record.get("metadata") or {}
        gen_id = metadata.get("generation_id")
    return gen_id


def _group_by_generation(
    records: List[Dict[str, Any]],
) -> Dict[str, List[Dict[str, Any]]]:
    """Group records by generation_id, including user_message from same conversation.

    Cursor assigns different generation_ids to user messages and assistant responses.
    This function finds the most recent user_message in the same conversation_id
    and includes it with each generation's records.
    """
    # First, collect all user_messages by conversation_id
    user_messages_by_conv: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for record in records:
        if record.get("event") == "user_message":
            conv_id = _get_conversation_id(record)
            if conv_id:
                user_messages_by_conv[conv_id].append(record)

    # Sort user messages by timestamp within each conversation
    for conv_id in user_messages_by_conv:
        user_messages_by_conv[conv_id].sort(key=lambda r: r.get("timestamp", ""))

    # Group non-user-message records by generation_id
    grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for record in records:
        gen_id = _get_generation_id(record)

        if not gen_id:
            gen_id = f"_standalone_{record.get('id', 'unknown')}"

        grouped[gen_id].append(record)

    # For each generation, find and include the most recent user_message
    # from the same conversation that occurred before the first record
    for gen_id, gen_records in grouped.items():
        # Check if this generation already has a user_message
        has_user_message = any(r.get("event") == "user_message" for r in gen_records)
        if has_user_message:
            continue

        # Get conversation_id and earliest timestamp from this generation
        conv_id = None
        earliest_timestamp = None
        for record in gen_records:
            if not conv_id:
                conv_id = _get_conversation_id(record)
            ts = record.get("timestamp", "")
            if earliest_timestamp is None or ts < earliest_timestamp:
                earliest_timestamp = ts

        if not conv_id:
            continue

        # Find the most recent user_message before this generation
        user_messages = user_messages_by_conv.get(conv_id, [])
        best_user_message = None
        for um in user_messages:
            um_ts = um.get("timestamp", "")
            if earliest_timestamp and um_ts < earliest_timestamp:
                best_user_message = um
            elif not earliest_timestamp:
                best_user_message = um

        if best_user_message:
            # Add a copy of the user_message to this generation's records
            gen_records.insert(0, best_user_message.copy())

    return grouped


def _filter_by_conversation(
    grouped: Dict[str, List[Dict[str, Any]]],
    conversation_ids: Optional[Set[str]],
) -> Dict[str, List[Dict[str, Any]]]:
    """Filter groups to only include specified conversation_ids."""
    if conversation_ids is None:
        return grouped

    filtered: Dict[str, List[Dict[str, Any]]] = {}
    for gen_id, records in grouped.items():
        # Check if any record in this group matches the conversation filter
        for record in records:
            conv_id = _get_conversation_id(record)
            if conv_id in conversation_ids:
                filtered[gen_id] = records
                break
    return filtered


def _print_summary_table(
    grouped: Dict[str, List[Dict[str, Any]]],
    dry_run: bool,
) -> None:
    """Print a summary table of turns to be uploaded."""
    table = Table(title="Agent Turns" + (" (dry run)" if dry_run else ""))
    table.add_column("Turn #", style="cyan", justify="right")
    table.add_column("Operations", style="magenta", justify="right")
    table.add_column("Has Input", style="green")
    table.add_column("Generation ID", style="dim")

    for i, (gen_id, records) in enumerate(grouped.items(), 1):
        # Check if this generation has a user_message
        has_user_message = any(r.get("event") == "user_message" for r in records)

        # Truncate IDs for display
        display_gen_id = gen_id[:20] + "..." if len(gen_id) > 23 else gen_id

        table.add_row(
            str(i),
            str(len(records)),
            "Yes" if has_user_message else "No",
            display_gen_id,
        )

    console.print(table)


@click.command(
    name="code-agent-trace", context_settings=CODE_AGENT_TRACE_CONTEXT_SETTINGS
)
@click.option(
    "--file",
    "-f",
    type=click.Path(exists=True, file_okay=True, dir_okay=False, readable=True),
    default=DEFAULT_TRACES_FILE,
    help=f"Path to traces.jsonl file. Defaults to {DEFAULT_TRACES_FILE}",
)
@click.option(
    "--project",
    "-p",
    type=str,
    required=True,
    help="Opik project name to upload traces to.",
)
@click.option(
    "--conversation",
    "-c",
    type=str,
    multiple=True,
    help="Filter by conversation_id. Can be specified multiple times.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be uploaded without actually uploading.",
)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def code_agent_trace(
    ctx: click.Context,
    file: str,
    project: str,
    conversation: tuple,
    dry_run: bool,
    api_key: Optional[str],
) -> None:
    """Upload Agent Trace data to Opik.

    Agent Trace is an open specification for tracking AI-generated code from tools
    like Cursor, Claude Code, and other AI coding agents.

    This command reads a traces.jsonl file, groups operations by generation_id
    (agent turn), and uploads each turn as an Opik trace with spans.

    \b
    Examples:
    \b
        # Upload all traces from default location
        opik code-agent-trace --project "my-agent-traces"
    \b
        # Upload from a specific file
        opik code-agent-trace -f ./my-traces.jsonl -p "my-project"
    \b
        # Filter by conversation
        opik code-agent-trace -p "my-project" -c "conv-123" -c "conv-456"
    \b
        # Preview what would be uploaded
        opik code-agent-trace -p "my-project" --dry-run
    """
    try:
        path = Path(file)

        # Read traces
        console.print(f"[blue]Reading traces from {path}...[/blue]")
        records = _read_traces_file(path)

        if not records:
            console.print("[yellow]No trace records found in file.[/yellow]")
            sys.exit(0)

        console.print(f"[green]Found {len(records)} trace records[/green]")

        # Group by generation_id
        grouped = _group_by_generation(records)
        console.print(f"[green]Grouped into {len(grouped)} agent turns[/green]")

        # Filter by conversation if specified
        if conversation:
            conversation_set = set(conversation)
            grouped = _filter_by_conversation(grouped, conversation_set)
            console.print(
                f"[green]Filtered to {len(grouped)} turns matching conversation filter[/green]"
            )

        if not grouped:
            console.print("[yellow]No turns to upload after filtering.[/yellow]")
            sys.exit(0)

        # Print summary table
        _print_summary_table(grouped, dry_run)

        if dry_run:
            console.print("\n[blue]Dry run complete. No data was uploaded.[/blue]")
            sys.exit(0)

        # Configure API key if provided
        effective_api_key = api_key or (
            ctx.parent.obj.get("api_key") if ctx.parent and ctx.parent.obj else None
        )

        if effective_api_key:
            import os

            os.environ["OPIK_API_KEY"] = effective_api_key

        # Upload each turn
        console.print(f"\n[blue]Uploading to project '{project}'...[/blue]")

        uploaded_count = 0
        total_operations = 0

        for gen_id, turn_records in grouped.items():
            try:
                log_code_agent_turn(
                    cast(List[TraceRecord], turn_records), project_name=project
                )
                uploaded_count += 1
                total_operations += len(turn_records)
            except Exception as e:
                console.print(f"[red]Error uploading turn {gen_id[:20]}...: {e}[/red]")

        # Flush to ensure all data is sent
        flush()

        console.print(
            f"\n[green]Successfully uploaded {uploaded_count} turns "
            f"({total_operations} operations) to project '{project}'[/green]"
        )

    except FileNotFoundError:
        console.print(f"[red]File not found: {file}[/red]")
        console.print(
            f"[dim]Hint: The default location is {DEFAULT_TRACES_FILE}. "
            f"Use --file to specify a different path.[/dim]"
        )
        sys.exit(1)
    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
