"""Export all workspace data."""

import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Iterator, Optional, TypeVar, Union

import httpx
import pydantic
import click
from rich.progress import (
    BarColumn,
    Progress,
    SpinnerColumn,
    TaskProgressColumn,
    TextColumn,
)

import opik
from opik.api_objects.prompt import Prompt, ChatPrompt
from .dataset import export_single_dataset
from .experiment import (
    export_experiment_by_id,
    export_collected_trace_ids,
)
from .project import export_single_project
from .prompt import export_single_prompt
from .utils import console, debug_print, no_attachments_option, print_export_summary
from ..include_validation import validate_include

PAGE_SIZE = 500

T = TypeVar("T")


def _paginate(list_fn: Any, **kwargs: Any) -> Iterator:
    """Iterate through all pages of a paginated list API."""
    page = 1
    while True:
        resp = list_fn(page=page, size=PAGE_SIZE, **kwargs)
        items = resp.content or []
        if not items:
            break
        yield from items
        total = resp.total or 0
        if page * PAGE_SIZE >= total:
            break
        page += 1


def _fetch_experiments_page_raw(client: opik.Opik, page: int) -> tuple[list, int]:
    """Fetch one page of experiments via raw HTTP, tolerating missing fields.

    Used as a fallback when the typed client fails pydantic validation because
    some experiments lack required fields (e.g. dataset_name).
    Returns (items, total) where items are SimpleNamespace objects with id and name.
    """
    httpx_client = (
        client.rest_client.experiments._raw_client._client_wrapper.httpx_client
    )
    try:
        response = httpx_client.request(
            "v1/private/experiments",
            method="GET",
            params={"page": page, "size": PAGE_SIZE},
        )
        response.raise_for_status()
        data = response.json()
    except (httpx.ConnectError, httpx.TimeoutException) as e:
        console.print(
            f"[yellow]Warning: transient network error fetching experiments page {page}: {e}. "
            "Skipping page.[/yellow]"
        )
        return [], 0
    except httpx.HTTPStatusError as e:
        console.print(
            f"[yellow]Warning: HTTP error fetching experiments page {page}: {e}. "
            "Skipping page.[/yellow]"
        )
        return [], 0
    total = data.get("total", 0)
    items = [
        SimpleNamespace(id=raw.get("id"), name=raw.get("name", ""))
        for raw in data.get("content", [])
        if raw.get("id")
    ]
    return items, total


def _paginate_experiments(client: opik.Opik) -> Iterator:
    """Paginate experiments, falling back to raw HTTP on pydantic validation errors."""
    page = 1
    while True:
        try:
            resp = client.rest_client.experiments.find_experiments(
                page=page, size=PAGE_SIZE
            )
            items = resp.content or []
            total = resp.total or 0
        except pydantic.ValidationError:
            console.print(
                f"[yellow]Warning: page {page} of experiments failed validation "
                f"(some experiments may be missing fields). Falling back to raw fetch.[/yellow]"
            )
            items, total = _fetch_experiments_page_raw(client, page)

        if not items:
            break
        yield from items
        if page * PAGE_SIZE >= total:
            break
        page += 1


def _export_all_datasets(
    client: opik.Opik,
    datasets_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> tuple[int, int]:
    """Export all datasets in the workspace. Returns (exported, skipped)."""
    exported = 0
    skipped = 0

    try:
        all_datasets = list(_paginate(client.rest_client.datasets.find_datasets))
    except Exception as e:
        console.print(f"[red]Error listing datasets: {e}[/red]")
        return 0, 0

    if not all_datasets:
        console.print("[yellow]No datasets found.[/yellow]")
        return 0, 0

    console.print(f"[blue]Found {len(all_datasets)} dataset(s)[/blue]")

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Exporting datasets...", total=len(all_datasets))

        for ds_public in all_datasets:
            progress.update(task, description=f"Dataset: {ds_public.name}")
            try:
                dataset_obj = client.get_dataset(ds_public.name)
                result = export_single_dataset(
                    dataset_obj, datasets_dir, max_results, force, debug, format
                )
                if result:
                    exported += 1
                else:
                    skipped += 1
            except Exception as e:
                console.print(
                    f"[red]Error exporting dataset '{ds_public.name}': {e}[/red]"
                )
            finally:
                progress.advance(task)

    return exported, skipped


def _export_all_prompts(
    client: opik.Opik,
    prompts_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> tuple[int, int]:
    """Export all prompts in the workspace. Returns (exported, skipped)."""
    exported = 0
    skipped = 0

    try:
        all_prompts = list(_paginate(client.rest_client.prompts.get_prompts))
    except Exception as e:
        console.print(f"[red]Error listing prompts: {e}[/red]")
        return 0, 0

    if not all_prompts:
        console.print("[yellow]No prompts found.[/yellow]")
        return 0, 0

    console.print(f"[blue]Found {len(all_prompts)} prompt(s)[/blue]")

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Exporting prompts...", total=len(all_prompts))

        for prompt_public in all_prompts:
            progress.update(task, description=f"Prompt: {prompt_public.name}")
            try:
                # Use template_structure to decide which SDK type to fetch
                prompt_obj: Optional[Union[Prompt, ChatPrompt]] = None
                if prompt_public.template_structure == "chat":
                    try:
                        prompt_obj = client.get_chat_prompt(prompt_public.name)
                    except Exception:
                        pass
                if prompt_obj is None:
                    try:
                        prompt_obj = client.get_prompt(prompt_public.name)
                    except Exception:
                        pass

                if prompt_obj is None:
                    console.print(
                        f"[yellow]Could not retrieve prompt '{prompt_public.name}', skipping.[/yellow]"
                    )
                    skipped += 1
                else:
                    result = export_single_prompt(
                        client,
                        prompt_obj,
                        prompts_dir,
                        max_results,
                        force,
                        debug,
                        format,
                    )
                    if result:
                        exported += 1
                    else:
                        skipped += 1
            except Exception as e:
                console.print(
                    f"[red]Error exporting prompt '{prompt_public.name}': {e}[/red]"
                )
            finally:
                progress.advance(task)

    return exported, skipped


def _export_all_projects(
    client: opik.Opik,
    projects_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    max_workers: int = 5,
    filter_string: Optional[str] = None,
    include_attachments: bool = True,
    page_size: int = 500,
) -> tuple[int, int, int, bool]:
    """Export all projects in the workspace.

    Returns (projects_exported, traces_exported, traces_skipped, had_errors).
    """
    projects_exported = 0
    traces_exported = 0
    traces_skipped = 0
    had_errors = False

    try:
        all_projects = list(_paginate(client.rest_client.projects.find_projects))
    except Exception as e:
        console.print(f"[red]Error listing projects: {e}[/red]")
        return 0, 0, 0, True

    if not all_projects:
        console.print("[yellow]No projects found.[/yellow]")
        return 0, 0, 0, False

    console.print(f"[blue]Found {len(all_projects)} project(s)[/blue]")

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Exporting projects...", total=len(all_projects))

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_project = {
                executor.submit(
                    export_single_project,
                    client=client,
                    project=project,
                    output_dir=projects_dir,
                    filter_string=filter_string,
                    max_results=max_results,
                    force=force,
                    debug=debug,
                    format=format,
                    show_progress=False,  # outer bar tracks progress
                    include_attachments=include_attachments,
                    page_size=page_size,
                ): project
                for project in all_projects
            }
            for future in as_completed(future_to_project):
                project = future_to_project[future]
                try:
                    proj_count, t_exported, t_skipped, proj_had_errors = future.result()
                    projects_exported += proj_count
                    traces_exported += t_exported
                    traces_skipped += t_skipped
                    if proj_had_errors:
                        had_errors = True
                        console.print(
                            f"[yellow]Project '{project.name}' exported with errors — "
                            "some traces may be missing. Run the export again to fill gaps.[/yellow]"
                        )
                except Exception as e:
                    console.print(
                        f"[red]Error exporting project '{project.name}': {e}[/red]"
                    )
                    had_errors = True
                finally:
                    progress.update(
                        task, advance=1, description=f"Project: {project.name}"
                    )

    return projects_exported, traces_exported, traces_skipped, had_errors


def _export_all_experiments(
    client: opik.Opik,
    workspace_root: Path,
    experiments_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    filter_string: Optional[str] = None,
    max_workers: int = 10,
) -> tuple[int, int, int, int, bool]:
    """Export all experiments.

    Returns (exported, skipped, traces_exported, traces_skipped, had_errors).
    """
    exported = 0
    skipped = 0
    had_errors = False
    all_trace_ids: set[str] = set()

    try:
        all_experiments = list(_paginate_experiments(client))
    except Exception as e:
        console.print(f"[red]Error listing experiments: {e}[/red]")
        return 0, 0, 0, 0, True

    if not all_experiments:
        console.print("[yellow]No experiments found.[/yellow]")
        return 0, 0, 0, 0, False

    console.print(f"[blue]Found {len(all_experiments)} experiment(s)[/blue]")

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Exporting experiments...", total=len(all_experiments))

        semaphore = threading.Semaphore(max_workers * 2)

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_exp: dict = {}
            for exp in all_experiments:
                semaphore.acquire()
                future = executor.submit(
                    export_experiment_by_id,
                    client,
                    experiments_dir,
                    exp.id,
                    max_results,
                    force,
                    debug,
                    format,
                    None,  # Don't pass shared set; collect from manifest in main thread
                )
                # Release the semaphore slot as soon as the future completes so
                # the submission loop can proceed even before as_completed drains
                # the queue.  Without this, submitting more than max_workers*2
                # experiments would deadlock: the main thread blocks on acquire()
                # while the as_completed loop (which would call release()) has not
                # started yet because the submission loop never finishes.
                future.add_done_callback(lambda _f: semaphore.release())
                future_to_exp[future] = exp
            for future in as_completed(future_to_exp):
                exp = future_to_exp[future]
                try:
                    exp_stats, file_written, exp_manifest = future.result()
                    if file_written:
                        exported += 1
                    else:
                        skipped += 1
                    # Collect trace IDs from the manifest in the main thread
                    # (safe: worker is done, no concurrent access)
                    if exp_manifest is not None:
                        stored_ids = exp_manifest.get_all_trace_ids()
                        if stored_ids:
                            all_trace_ids.update(stored_ids)
                except Exception as e:
                    console.print(
                        f"[red]Error exporting experiment '{exp.name}': {e}[/red]"
                    )
                    had_errors = True
                finally:
                    progress.update(
                        task, advance=1, description=f"Experiment: {exp.name}"
                    )

    # Batch-export all traces collected from experiments
    traces_exported, traces_skipped = export_collected_trace_ids(
        client,
        workspace_root,
        all_trace_ids,
        max_results,
        format,
        debug,
        force,
        filter_string=filter_string,
    )

    return exported, skipped, traces_exported, traces_skipped, had_errors


def export_all(
    workspace: str,
    output_path: str,
    include: list[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
    filter_string: Optional[str] = None,
    include_attachments: bool = True,
    page_size: int = 500,
) -> None:
    """Export all data from the workspace."""
    try:
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        workspace_root = Path(output_path) / workspace
        workspace_root.mkdir(parents=True, exist_ok=True)

        total_stats: dict = {}
        any_errors = False

        # Phase 1: Datasets
        if "datasets" in include:
            console.print("\n[bold blue]--- Exporting Datasets ---[/bold blue]")
            datasets_dir = workspace_root / "datasets"
            datasets_dir.mkdir(parents=True, exist_ok=True)
            ds_exp, ds_skip = _export_all_datasets(
                client, datasets_dir, max_results, force, debug, format
            )
            total_stats["datasets"] = ds_exp
            total_stats["datasets_skipped"] = ds_skip

        # Phase 2: Prompts
        if "prompts" in include:
            console.print("\n[bold blue]--- Exporting Prompts ---[/bold blue]")
            prompts_dir = workspace_root / "prompts"
            prompts_dir.mkdir(parents=True, exist_ok=True)
            pr_exp, pr_skip = _export_all_prompts(
                client, prompts_dir, max_results, force, debug, format
            )
            total_stats["prompts"] = pr_exp
            total_stats["prompts_skipped"] = pr_skip

        # Phase 3: Projects (traces)
        if "projects" in include:
            console.print("\n[bold blue]--- Exporting Projects ---[/bold blue]")
            projects_dir = workspace_root / "projects"
            projects_dir.mkdir(parents=True, exist_ok=True)
            proj_exp, tr_exp, tr_skip, proj_errors = _export_all_projects(
                client,
                projects_dir,
                max_results,
                force,
                debug,
                format,
                filter_string=filter_string,
                include_attachments=include_attachments,
                page_size=page_size,
            )
            total_stats["projects"] = proj_exp
            # Accumulate traces (experiments may also add traces below)
            total_stats["traces"] = tr_exp
            total_stats["traces_skipped"] = tr_skip
            if proj_errors:
                any_errors = True

        # Phase 4: Experiments (related datasets/prompts skipped via file-existence check)
        if "experiments" in include:
            console.print("\n[bold blue]--- Exporting Experiments ---[/bold blue]")
            experiments_dir = workspace_root / "experiments"
            experiments_dir.mkdir(parents=True, exist_ok=True)
            exp_exp, exp_skip, exp_tr_exp, exp_tr_skip, exp_errors = (
                _export_all_experiments(
                    client,
                    workspace_root,
                    experiments_dir,
                    max_results,
                    force,
                    debug,
                    format,
                    filter_string=filter_string,
                )
            )
            total_stats["experiments"] = exp_exp
            total_stats["experiments_skipped"] = exp_skip
            # Add experiment traces to trace totals
            total_stats["traces"] = total_stats.get("traces", 0) + exp_tr_exp
            total_stats["traces_skipped"] = (
                total_stats.get("traces_skipped", 0) + exp_tr_skip
            )
            if exp_errors:
                any_errors = True

        if any_errors:
            console.print(
                "\n[bold yellow]Export completed with errors — "
                "some projects or experiments could not be exported.[/bold yellow]"
            )
        else:
            console.print("\n[bold green]Export complete.[/bold green]")
        print_export_summary(total_stats, format)
        console.print(f"[green]All data saved to: {workspace_root}[/green]")
        if any_errors:
            sys.exit(1)

    except Exception as e:
        console.print(f"[red]Error during export all: {e}[/red]")
        if debug:
            import traceback

            debug_print(traceback.format_exc(), debug)
        sys.exit(1)


_VALID_INCLUDES = {"datasets", "prompts", "projects", "experiments"}
_DEFAULT_INCLUDE = "datasets,prompts,projects,experiments"


def _validate_include(
    ctx: click.Context, param: click.Parameter, value: str
) -> list[str]:
    return validate_include(value, _VALID_INCLUDES, ctx, param)


@click.command(name="all")
@click.option(
    "--filter",
    type=str,
    help="OQL filter string applied to project and experiment traces (e.g. 'created_at >= \"2024-01-01T00:00:00Z\"').",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="opik_exports",
    help="Directory to save exported data. Defaults to opik_exports.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
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
    "--include",
    default=_DEFAULT_INCLUDE,
    callback=_validate_include,
    help=(
        "Comma-separated list of data types to export. "
        f"Valid values: {', '.join(sorted(_VALID_INCLUDES))}. "
        f"Defaults to all: {_DEFAULT_INCLUDE}."
    ),
)
@click.option(
    "--max-results",
    type=int,
    default=None,
    help="Maximum number of traces/items to export per entity.",
)
@no_attachments_option()
@click.option(
    "--page-size",
    type=click.IntRange(1, 1000),
    default=500,
    show_default=True,
    help="Number of traces to fetch per API request when exporting projects. Larger values reduce round-trips but increase memory usage.",
)
@click.pass_context
def export_all_command(
    ctx: click.Context,
    filter: Optional[str],
    path: str,
    format: str,
    force: bool,
    debug: bool,
    include: list[str],
    max_results: Optional[int],
    no_attachments: bool,
    page_size: int,
) -> None:
    """Export all datasets, prompts, projects, and experiments from the workspace.

    Downloads everything in the workspace in dependency order:
    datasets and prompts first, then projects (traces), then experiments.
    Items already downloaded are skipped unless --force is set.

    \b
    Examples:
        # Export everything
        opik export my-workspace all

        # Export only datasets and prompts
        opik export my-workspace all --include datasets,prompts

        # Re-download everything to a custom path
        opik export my-workspace all --force --path ./backup
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_all(
        workspace,
        path,
        include,
        max_results,
        force,
        debug,
        format,
        api_key,
        filter_string=filter,
        include_attachments=not no_attachments,
        page_size=page_size,
    )
