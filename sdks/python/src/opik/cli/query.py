"""Agent-friendly query commands for Opik CLI."""

from __future__ import annotations

import json
from typing import Any, Optional

import click
import opik


QUERY_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


def _to_jsonable(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump(by_alias=True)
    if isinstance(value, dict):
        return {str(k): _to_jsonable(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_to_jsonable(v) for v in value]
    if isinstance(value, tuple):
        return [_to_jsonable(v) for v in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if hasattr(value, "__dict__"):
        return {k: _to_jsonable(v) for k, v in vars(value).items() if not k.startswith("_")}
    return str(value)


def _emit(event: str, payload: dict[str, Any], as_json: bool) -> None:
    if as_json:
        click.echo(
            json.dumps(
                {
                    "event": event,
                    "payload": _to_jsonable(payload),
                },
                default=str,
            )
        )
        return

    click.echo(f"[{event}]")
    for key, value in payload.items():
        click.echo(f"- {key}: {value}")


def _client(ctx: click.Context) -> opik.Opik:
    api_key = None
    if ctx.obj:
        api_key = ctx.obj.get("api_key")
    return opik.Opik(api_key=api_key)


@click.group(name="query", context_settings=QUERY_CONTEXT_SETTINGS)
def query_group() -> None:
    """Query prompts, datasets, projects, traces, and spans from terminal workflows."""


@query_group.command("projects")
@click.option("--name", type=str, default=None, help="Optional name filter")
@click.option("--limit", type=int, default=25, show_default=True)
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_projects(
    ctx: click.Context,
    name: Optional[str],
    limit: int,
    as_json: bool,
) -> None:
    client = _client(ctx)
    page = client.rest_client.projects.find_projects(size=limit, name=name)
    items = page.content or []
    _emit("projects", {"count": len(items), "items": items}, as_json)


@query_group.command("datasets")
@click.option("--name", type=str, default=None, help="Optional name filter")
@click.option("--limit", type=int, default=25, show_default=True)
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_datasets(
    ctx: click.Context,
    name: Optional[str],
    limit: int,
    as_json: bool,
) -> None:
    client = _client(ctx)
    page = client.rest_client.datasets.find_datasets(size=limit, name=name)
    items = page.content or []
    _emit("datasets", {"count": len(items), "items": items}, as_json)


@query_group.command("dataset")
@click.option("--name", type=str, required=True, help="Dataset name")
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_dataset(ctx: click.Context, name: str, as_json: bool) -> None:
    client = _client(ctx)
    dataset = client.rest_client.datasets.get_dataset_by_identifier(dataset_name=name)
    _emit("dataset", {"name": name, "dataset": dataset}, as_json)


@query_group.command("prompts")
@click.option("--name", type=str, default=None, help="Optional prompt name filter")
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_prompts(ctx: click.Context, name: Optional[str], as_json: bool) -> None:
    client = _client(ctx)
    prompt_client = client.get_prompts_client()
    items = prompt_client.search_prompts(name=name)
    _emit("prompts", {"count": len(items), "items": items}, as_json)


@query_group.command("prompt")
@click.option("--name", type=str, required=True, help="Prompt name")
@click.option("--commit", type=str, default=None, help="Optional commit hash")
@click.option("--chat", is_flag=True, default=False, help="Fetch as chat prompt")
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_prompt(
    ctx: click.Context,
    name: str,
    commit: Optional[str],
    chat: bool,
    as_json: bool,
) -> None:
    client = _client(ctx)
    if chat:
        prompt = client.get_chat_prompt(name=name, commit=commit)
        event = "chat_prompt"
    else:
        prompt = client.get_prompt(name=name, commit=commit)
        event = "prompt"

    _emit(event, {"name": name, "commit": commit, "result": prompt}, as_json)


@query_group.command("traces")
@click.option("--project-name", type=str, default=None)
@click.option("--filter", "filter_string", type=str, default=None)
@click.option("--limit", type=int, default=100, show_default=True)
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_traces(
    ctx: click.Context,
    project_name: Optional[str],
    filter_string: Optional[str],
    limit: int,
    as_json: bool,
) -> None:
    client = _client(ctx)
    items = client.search_traces(
        project_name=project_name,
        filter_string=filter_string,
        max_results=limit,
    )
    _emit("traces", {"count": len(items), "items": items}, as_json)


@query_group.command("spans")
@click.option("--project-name", type=str, default=None)
@click.option("--trace-id", type=str, default=None)
@click.option("--filter", "filter_string", type=str, default=None)
@click.option("--limit", type=int, default=100, show_default=True)
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit JSON events")
@click.pass_context
def query_spans(
    ctx: click.Context,
    project_name: Optional[str],
    trace_id: Optional[str],
    filter_string: Optional[str],
    limit: int,
    as_json: bool,
) -> None:
    client = _client(ctx)
    items = client.search_spans(
        project_name=project_name,
        trace_id=trace_id,
        filter_string=filter_string,
        max_results=limit,
    )
    _emit("spans", {"count": len(items), "items": items}, as_json)


@query_group.command("completion")
@click.option(
    "--shell",
    type=click.Choice(["bash", "zsh", "fish"], case_sensitive=False),
    required=True,
)
def query_completion(shell: str) -> None:
    """Print shell completion setup snippet for `opik`."""
    shell_normalized = shell.lower()
    if shell_normalized == "bash":
        click.echo('eval "$(env _OPIK_COMPLETE=bash_source opik)"')
        return
    if shell_normalized == "zsh":
        click.echo('eval "$(env _OPIK_COMPLETE=zsh_source opik)"')
        return
    click.echo("env _OPIK_COMPLETE=fish_source opik | source")
