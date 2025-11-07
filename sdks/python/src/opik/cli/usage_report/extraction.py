"""Data extraction functions for usage report module."""

import datetime
import json
import os
import traceback
from collections import defaultdict
from datetime import timezone
from typing import Any, Dict, List, Optional

import opik
from rich.console import Console
from tqdm import tqdm

from .constants import MAX_PAGINATION_PAGES, MAX_TRACE_RESULTS
from .utils import (
    aggregate_by_unit,
    format_datetime_key,
    normalize_timezone_for_comparison,
    process_experiment_for_stats,
)

console = Console()


def extract_project_data(
    workspace: str,
    api_key: Optional[str] = None,
    start_date: Optional[datetime.datetime] = None,
    end_date: Optional[datetime.datetime] = None,
    unit: str = "month",
) -> Dict[str, Any]:
    """
    Extract project data from Opik for a specific workspace.

    Args:
        workspace: Workspace name
        api_key: Opik API key (optional, will use environment/config if not provided)
        start_date: Start date for data extraction (None to auto-detect from data)
        end_date: End date for data extraction (None to auto-detect from data)
        unit: Time unit for aggregation - "month", "week", "day", or "hour". Defaults to "month".

    Returns:
        Dictionary containing all extracted data
    """
    # If dates are None, we'll collect all data and determine the range afterwards
    auto_detect_start = start_date is None
    auto_detect_end = end_date is None

    # Use wide date ranges to capture all data when auto-detecting
    query_start_date = start_date
    if query_start_date is None:
        # Use environment variable OPIK_DEFAULT_START_DATE if set, else use start of current year
        env_start_date = os.environ.get("OPIK_DEFAULT_START_DATE")
        if env_start_date:
            try:
                query_start_date = datetime.datetime.strptime(
                    env_start_date, "%Y-%m-%d"
                )
            except ValueError:
                console.print(
                    "[yellow]Warning: Invalid OPIK_DEFAULT_START_DATE format. Using start of current year.[/yellow]"
                )
                query_start_date = datetime.datetime(datetime.datetime.now().year, 1, 1)
        else:
            query_start_date = datetime.datetime(datetime.datetime.now().year, 1, 1)

    query_end_date = end_date
    if query_end_date is None:
        # Use a future date to ensure we get all data
        query_end_date = datetime.datetime.now() + datetime.timedelta(days=1)

    console.print(f"[blue]Workspace: {workspace}[/blue]")
    if auto_detect_start or auto_detect_end:
        date_msg = "Date range will be auto-detected from collected data"
        if auto_detect_start and not auto_detect_end and end_date:
            date_msg += f" (end date: {end_date.strftime('%Y-%m-%d')})"
        elif not auto_detect_start and auto_detect_end and start_date:
            date_msg += f" (start date: {start_date.strftime('%Y-%m-%d')})"
        console.print(f"[blue]{date_msg}[/blue]")
    else:
        if start_date and end_date:
            console.print(
                f"[blue]Extracting data from {start_date.strftime('%Y-%m-%d')} to {end_date.strftime('%Y-%m-%d')}[/blue]"
            )
    console.print(f"[blue]Aggregating by: {unit}[/blue]\n")

    # Initialize client for the workspace
    if api_key:
        client = opik.Opik(api_key=api_key, workspace=workspace)
    else:
        client = opik.Opik(workspace=workspace)

    # Get projects for this workspace
    console.print("[blue]Getting projects...[/blue]")
    with tqdm(total=1, desc="Fetching projects", unit="page", leave=False) as pbar:
        projects_page = client.rest_client.projects.find_projects(size=1000)
        projects = projects_page.content or []
        pbar.update(1)
    console.print(f"[blue]Found {len(projects)} project(s)[/blue]\n")

    # Track all dates collected for auto-detection
    all_dates: List[datetime.datetime] = []

    all_data: Dict[str, Any] = {
        "workspace": workspace,
        "extraction_date": datetime.datetime.now().isoformat(),
        "date_range": {"start": None, "end": None},
        "unit": unit,
        "experiments_by_unit": {},
        "datasets_by_unit": {},
        "total_datasets": 0,
        "projects": [],
    }

    # Get experiment counts by unit (workspace-level)
    experiment_by_unit: Dict[str, int] = defaultdict(int)
    total_experiments_processed = 0
    total_experiments_in_range = 0
    experiments_without_date = 0
    experiments_outside_range = 0

    # Get dataset counts (workspace-level)
    dataset_by_unit: Dict[str, int] = defaultdict(int)
    total_datasets_processed = 0
    total_datasets_in_range = 0
    datasets_without_date = 0
    datasets_outside_range = 0

    try:
        page = 1  # API uses 1-indexed pagination
        total_datasets = None

        # First, get total count to set up progress bar
        datasets_page = client.rest_client.datasets.find_datasets(page=1, size=1000)
        total_datasets = datasets_page.total or 0

        # Reset page to 1 for the main loop
        page = 1

        with tqdm(
            total=total_datasets,
            desc="Processing datasets",
            unit="dataset",
            leave=False,
        ) as pbar:
            while True:
                datasets_page = client.rest_client.datasets.find_datasets(
                    page=page, size=1000
                )

                datasets_list = datasets_page.content or []

                if not datasets_list or len(datasets_list) == 0:
                    break

                # Count datasets by month based on created_at
                for dataset in datasets_list:
                    total_datasets_processed += 1

                    if dataset.created_at:
                        dataset_date = dataset.created_at

                        # Normalize timezones for comparison
                        dataset_date, start_date_aware, end_date_aware = (
                            normalize_timezone_for_comparison(
                                dataset_date, query_start_date, query_end_date
                            )
                        )

                        # Check if within date range
                        if dataset_date.tzinfo is not None:
                            date_check = (
                                start_date_aware <= dataset_date <= end_date_aware
                            )
                        else:
                            date_check = (
                                query_start_date <= dataset_date <= query_end_date
                            )

                        if date_check:
                            total_datasets_in_range += 1
                            all_dates.append(dataset_date)
                            unit_key = format_datetime_key(dataset_date, unit)
                            dataset_by_unit[unit_key] += 1
                        else:
                            datasets_outside_range += 1
                    else:
                        datasets_without_date += 1

                    # Update progress bar
                    pbar.update(1)

                # Check if there are more pages
                if total_datasets and page * 1000 >= total_datasets:
                    break
                if len(datasets_list) == 0:
                    break

                page += 1

                # Safety check to avoid infinite loops
                if page > MAX_PAGINATION_PAGES:
                    console.print(
                        f"[yellow]    Warning: Stopped pagination after {MAX_PAGINATION_PAGES} pages to avoid infinite loop[/yellow]"
                    )
                    break

    except Exception as e:
        console.print(f"[yellow]Warning: Could not get dataset counts: {e}[/yellow]")
        traceback.print_exc()

    all_data["datasets_by_unit"] = dict(dataset_by_unit)
    all_data["total_datasets"] = total_datasets_processed

    # Get all existing (non-deleted) dataset names for filtering experiments
    # The UI only shows experiments whose datasets still exist
    console.print("[blue]Getting existing datasets for filtering...[/blue]")
    existing_dataset_names = set()
    try:
        datasets_page = client.rest_client.datasets.find_datasets(page=1, size=1000)
        existing_dataset_names = {ds.name for ds in (datasets_page.content or [])}
        page = 2
        while datasets_page.content and len(datasets_page.content) > 0:
            datasets_page = client.rest_client.datasets.find_datasets(
                page=page, size=1000
            )
            if datasets_page.content:
                existing_dataset_names.update({ds.name for ds in datasets_page.content})
            if not datasets_page.content or len(datasets_page.content) < 1000:
                break
            page += 1
        console.print(
            f"[blue]Found {len(existing_dataset_names)} existing dataset(s)[/blue]\n"
        )
    except Exception as e:
        console.print(
            f"[yellow]Warning: Could not get datasets for filtering: {e}[/yellow]"
        )
        console.print(
            "[yellow]Will count all experiments (may include those with deleted datasets)[/yellow]\n"
        )

    # Get experiment counts by unit (workspace-level)
    try:
        # Use REST client method (handles parameters correctly)
        # Filter by type="regular" to match UI behavior (UI only shows regular experiments)
        # Note: types parameter needs to be JSON-encoded array string
        try:
            test_page = client.rest_client.experiments.find_experiments(
                page=1,
                size=1000,
                types=json.dumps(
                    ["regular"]
                ),  # Filter to only regular experiments (matches UI)
                dataset_deleted=False,  # Filter out experiments with deleted datasets
            )
            total_experiments = test_page.total or 0
        except Exception as api_error:
            # Handle Pydantic validation errors from malformed API responses
            error_str = str(api_error)
            if "dataset_name" in error_str and (
                "Field required" in error_str or "missing" in error_str.lower()
            ):
                # Try to get raw response to get total count
                try:
                    httpx_client = client.rest_client._client_wrapper.httpx_client
                    response = httpx_client.request(
                        "v1/private/experiments",
                        method="GET",
                        params={
                            "page": 1,
                            "size": 1000,
                            "types": json.dumps(["regular"]),
                            "dataset_deleted": False,
                        },
                    )
                    if response.status_code >= 200 and response.status_code < 300:
                        response_data = response.json()
                        total_experiments = response_data.get("total", 0)
                    else:
                        total_experiments = 0
                except Exception:
                    total_experiments = 0
            else:
                # Re-raise other errors
                raise api_error

        page = 1  # API uses 1-indexed pagination

        # Note: total_experiments should now match UI count since we filter by type="regular"
        # We also filter client-side for deleted datasets as a safety measure
        with tqdm(
            total=total_experiments,
            desc="Processing experiments (regular type, filtering deleted datasets)",
            unit="experiment",
            leave=False,
        ) as pbar:
            while True:
                # Use REST client method (handles parameters correctly)
                try:
                    experiments_page = client.rest_client.experiments.find_experiments(
                        page=page,
                        size=1000,
                        types=json.dumps(
                            ["regular"]
                        ),  # Filter to only regular experiments (matches UI)
                        dataset_deleted=False,  # Filter out experiments with deleted datasets
                    )
                    experiments_list = experiments_page.content or []
                except Exception as api_error:
                    # Handle Pydantic validation errors from malformed API responses
                    # Some experiments may be missing required fields like dataset_name
                    error_str = str(api_error)
                    if "dataset_name" in error_str and (
                        "Field required" in error_str or "missing" in error_str.lower()
                    ):
                        # Try to get raw response and manually filter out invalid experiments
                        try:
                            httpx_client = (
                                client.rest_client._client_wrapper.httpx_client
                            )
                            response = httpx_client.request(
                                "v1/private/experiments",
                                method="GET",
                                params={
                                    "page": page,
                                    "size": 1000,
                                    "types": json.dumps(["regular"]),
                                    "dataset_deleted": False,
                                },
                            )
                            if (
                                response.status_code >= 200
                                and response.status_code < 300
                            ):
                                response_data = response.json()
                                experiments_list = response_data.get("content", [])
                                # Note: We process experiments even if they're missing dataset_name
                                # since process_experiment_for_stats only needs created_at
                            else:
                                # If raw request also fails, try with smaller page size as fallback
                                console.print(
                                    f"[yellow]    Warning: Could not fetch page {page} (HTTP {response.status_code}). Trying smaller page size...[/yellow]"
                                )
                                try:
                                    # Try with smaller page size to potentially avoid the problematic experiment
                                    small_response = httpx_client.request(
                                        "v1/private/experiments",
                                        method="GET",
                                        params={
                                            "page": page,
                                            "size": 100,  # Smaller page size
                                            "types": json.dumps(["regular"]),
                                            "dataset_deleted": False,
                                        },
                                    )
                                    if (
                                        small_response.status_code >= 200
                                        and small_response.status_code < 300
                                    ):
                                        small_response_data = small_response.json()
                                        experiments_list = small_response_data.get(
                                            "content", []
                                        )
                                        console.print(
                                            f"[yellow]    Successfully fetched page {page} with smaller page size. Got {len(experiments_list)} experiment(s).[/yellow]"
                                        )
                                    else:
                                        # If smaller page size also fails, skip this page
                                        console.print(
                                            f"[yellow]    Warning: Could not fetch page {page} even with smaller page size. Skipping page (may lose some experiments).[/yellow]"
                                        )
                                        experiments_list = []
                                        page += 1
                                        continue
                                except Exception:
                                    # If smaller page size request fails, skip this page
                                    console.print(
                                        f"[yellow]    Warning: Could not fetch page {page} even with smaller page size. Skipping page (may lose some experiments).[/yellow]"
                                    )
                                    experiments_list = []
                                    page += 1
                                    continue
                        except Exception as raw_error:
                            # If raw request fails, try smaller page size as last resort
                            console.print(
                                f"[yellow]    Warning: Could not fetch page {page} due to error: {raw_error}. Trying smaller page size...[/yellow]"
                            )
                            try:
                                httpx_client = (
                                    client.rest_client._client_wrapper.httpx_client
                                )
                                small_response = httpx_client.request(
                                    "v1/private/experiments",
                                    method="GET",
                                    params={
                                        "page": page,
                                        "size": 100,  # Smaller page size
                                        "types": json.dumps(["regular"]),
                                        "dataset_deleted": False,
                                    },
                                )
                                if (
                                    small_response.status_code >= 200
                                    and small_response.status_code < 300
                                ):
                                    small_response_data = small_response.json()
                                    experiments_list = small_response_data.get(
                                        "content", []
                                    )
                                    console.print(
                                        f"[yellow]    Successfully fetched page {page} with smaller page size. Got {len(experiments_list)} experiment(s).[/yellow]"
                                    )
                                else:
                                    # If smaller page size also fails, skip this page
                                    console.print(
                                        f"[yellow]    Warning: Could not fetch page {page} even with smaller page size. Skipping page (may lose some experiments).[/yellow]"
                                    )
                                    experiments_list = []
                                    page += 1
                                    continue
                            except Exception:
                                # If smaller page size request also fails, skip this page
                                console.print(
                                    f"[yellow]    Warning: Could not fetch page {page} even with smaller page size. Skipping page (may lose some experiments).[/yellow]"
                                )
                                experiments_list = []
                                page += 1
                                continue
                    else:
                        # Re-raise other errors
                        raise api_error

                # Convert to dict format for processing
                experiments_dict_list = []
                for exp in experiments_list:
                    try:
                        if hasattr(exp, "model_dump"):
                            # Use mode='python' to get native Python types and exclude_unset to avoid validation issues
                            exp_dict = exp.model_dump(mode="python", exclude_unset=True)
                        elif hasattr(exp, "dict"):
                            exp_dict = exp.dict(exclude_unset=True)
                        else:
                            # Already a dict
                            exp_dict = exp  # type: ignore[assignment]
                        experiments_dict_list.append(exp_dict)
                    except Exception as e:
                        # Skip experiments that can't be converted (e.g., missing required fields)
                        console.print(
                            f"[yellow]    Warning: Skipping experiment due to conversion error: {e}[/yellow]"
                        )
                        continue
                experiments_list = experiments_dict_list

                if not experiments_list or len(experiments_list) == 0:
                    break

                # Filter experiments to only include those with existing (non-deleted) datasets
                # This matches the UI behavior - UI only shows experiments whose datasets still exist
                # Note: We still process experiments without dataset_name since process_experiment_for_stats
                # only needs created_at, but we filter out experiments whose datasets don't exist
                filtered_experiments = []
                skipped_count = 0
                for experiment_dict in experiments_list:
                    dataset_name = experiment_dict.get("dataset_name")
                    # Skip experiments that have a dataset_name but the dataset doesn't exist
                    # (experiments without dataset_name are still processed)
                    if (
                        dataset_name
                        and existing_dataset_names
                        and dataset_name not in existing_dataset_names
                    ):
                        # Dataset doesn't exist (was deleted)
                        skipped_count += 1
                        continue
                    filtered_experiments.append(experiment_dict)

                # Count experiments by month based on created_at
                # Process all experiments (including those without dataset_name)
                for experiment_dict in filtered_experiments:
                    total_experiments_processed += 1
                    in_range, without_date, outside_range = (
                        process_experiment_for_stats(
                            experiment_dict,
                            experiment_by_unit,
                            all_dates,
                            query_start_date,
                            query_end_date,
                            unit,
                            start_date,
                        )
                    )
                    total_experiments_in_range += in_range
                    experiments_without_date += without_date
                    experiments_outside_range += outside_range
                    pbar.update(1)

                # Check if there are more pages
                # Note: page is 1-indexed, so page 1 = items 0-999, page 2 = items 1000-1999, etc.
                if total_experiments and page * 1000 >= total_experiments:
                    break
                if len(experiments_list) == 0:
                    break

                page += 1

                # Safety check to avoid infinite loops
                if page > MAX_PAGINATION_PAGES:
                    console.print(
                        f"[yellow]    Warning: Stopped pagination after {MAX_PAGINATION_PAGES} pages to avoid infinite loop[/yellow]"
                    )
                    break

    except Exception as e:
        console.print(f"[yellow]Warning: Could not get experiment counts: {e}[/yellow]")
        traceback.print_exc()

    all_data["experiments_by_unit"] = dict(experiment_by_unit)

    # Process each project
    with tqdm(total=len(projects), desc="Processing projects", unit="project") as pbar:
        for project in projects:
            project_id = project.id
            project_name = project.name

            # Pad project name to fixed width to prevent progress bar from jumping
            # Truncate to 30 chars and pad to 30 chars for consistent width
            display_name = (project_name[:30] + " " * 30)[:30]
            pbar.set_description(f"Processing {display_name}")

            project_data = {
                "project_id": project_id,
                "project_name": project_name,
                "metrics_by_unit": {},
            }

            try:
                # Get trace counts
                trace_response = client.rest_client.projects.get_project_metrics(
                    id=project_id,
                    metric_type="TRACE_COUNT",
                    interval="DAILY",
                    interval_start=query_start_date,
                    interval_end=query_end_date,
                )
                trace_by_unit = aggregate_by_unit(trace_response, unit)
                # Track dates from metrics
                if trace_response.results:
                    for result in trace_response.results:
                        if result.data:
                            for data_point in result.data:
                                if data_point.value is not None:
                                    all_dates.append(data_point.time)

                # Get token counts
                token_response = client.rest_client.projects.get_project_metrics(
                    id=project_id,
                    metric_type="TOKEN_USAGE",
                    interval="DAILY",
                    interval_start=query_start_date,
                    interval_end=query_end_date,
                )
                # Token usage has multiple result types (total_tokens, prompt_tokens, etc.)
                # We'll aggregate all of them
                token_by_unit: Dict[str, Dict[str, float]] = defaultdict(
                    lambda: defaultdict(float)
                )
                if token_response.results:
                    for result in token_response.results:
                        token_type = result.name or "unknown"
                        for data_point in result.data or []:
                            if data_point.value is not None:
                                all_dates.append(data_point.time)
                                unit_key = format_datetime_key(data_point.time, unit)
                                token_by_unit[unit_key][token_type] += data_point.value

                # Get cost
                cost_response = client.rest_client.projects.get_project_metrics(
                    id=project_id,
                    metric_type="COST",
                    interval="DAILY",
                    interval_start=query_start_date,
                    interval_end=query_end_date,
                )
                cost_by_unit = aggregate_by_unit(cost_response, unit)
                # Track dates from metrics
                if cost_response.results:
                    for result in cost_response.results:
                        if result.data:
                            for data_point in result.data:
                                if data_point.value is not None:
                                    all_dates.append(data_point.time)

                # Get span counts by getting all traces and using their span_count field
                span_by_unit: Dict[str, int] = defaultdict(int)
                try:
                    # Get all traces for this project within the date range
                    # Use a filter string to limit by date range
                    filter_string = None
                    if query_start_date and query_end_date:
                        # Format dates for filter (ISO 8601 format with timezone)
                        # API expects format like "2024-01-01T00:00:00Z"
                        def format_date_for_filter(dt: datetime.datetime) -> str:
                            """Format datetime for filter string with timezone."""
                            if dt.tzinfo is None:
                                # Naive datetime - assume UTC and add Z
                                return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
                            else:
                                # Timezone-aware - convert to UTC and format
                                utc_dt = dt.astimezone(timezone.utc)
                                return utc_dt.strftime("%Y-%m-%dT%H:%M:%SZ")

                        start_str = format_date_for_filter(query_start_date)
                        end_str = format_date_for_filter(query_end_date)
                        filter_string = (
                            f'start_time >= "{start_str}" AND start_time <= "{end_str}"'
                        )

                    traces = client.search_traces(
                        project_name=project_name,
                        filter_string=filter_string,
                        max_results=MAX_TRACE_RESULTS,
                    )

                    # For each trace, get span count
                    for trace in tqdm(
                        traces,
                        desc=f"  Getting span counts for {project_name[:20]}",
                        leave=False,
                        unit="trace",
                    ):
                        # Try to get span count from trace object first
                        span_count = trace.span_count

                        # If span_count is not available, count spans directly
                        if span_count is None:
                            try:
                                spans = client.search_spans(
                                    trace_id=trace.id,
                                    project_name=project_name,
                                    max_results=10000,
                                )
                                span_count = len(spans)
                            except Exception:
                                # If counting fails, default to 0
                                span_count = 0

                        span_count = span_count or 0

                        # Aggregate by unit based on trace start_time
                        if trace.start_time:
                            trace_date = trace.start_time

                            # Normalize timezones for comparison
                            trace_date, start_date_aware, end_date_aware = (
                                normalize_timezone_for_comparison(
                                    trace_date, query_start_date, query_end_date
                                )
                            )

                            # Check if within date range
                            if trace_date.tzinfo is not None:
                                date_check = (
                                    start_date_aware <= trace_date <= end_date_aware
                                )
                            else:
                                date_check = (
                                    query_start_date <= trace_date <= query_end_date
                                )

                            if date_check:
                                unit_key = format_datetime_key(trace_date, unit)
                                span_by_unit[unit_key] += span_count
                                all_dates.append(trace_date)
                except Exception as e:
                    console.print(
                        f"[yellow]  Warning: Could not get span counts for project {project_name}: {e}[/yellow]"
                    )

                # Combine all metrics by unit
                all_units = set(
                    list(trace_by_unit.keys())
                    + list(token_by_unit.keys())
                    + list(cost_by_unit.keys())
                    + list(span_by_unit.keys())
                )

                for unit_key in sorted(all_units):
                    project_data["metrics_by_unit"][unit_key] = {
                        "trace_count": trace_by_unit.get(unit_key, 0),
                        "token_count": dict(token_by_unit.get(unit_key, {})),
                        "cost": cost_by_unit.get(unit_key, 0.0),
                        "span_count": span_by_unit.get(unit_key, 0),
                    }

            except Exception as e:
                console.print(
                    f"[red]  Error processing project {project_name}: {e}[/red]\n"
                )
                project_data["error"] = str(e)

            all_data["projects"].append(project_data)
            pbar.update(1)

    # Determine actual date range from collected data if auto-detection was requested
    if all_dates:
        actual_start = min(all_dates)
        actual_end = max(all_dates)

        # Use provided dates where available, otherwise use detected dates
        if auto_detect_start:
            all_data["date_range"]["start"] = actual_start.isoformat()
        else:
            if start_date:
                all_data["date_range"]["start"] = start_date.isoformat()

        if auto_detect_end:
            all_data["date_range"]["end"] = actual_end.isoformat()
        else:
            if end_date:
                all_data["date_range"]["end"] = end_date.isoformat()

        if auto_detect_start or auto_detect_end:
            # Format dates nicely for display
            start_str = all_data["date_range"]["start"]
            end_str = all_data["date_range"]["end"]
            try:
                start_dt = datetime.datetime.fromisoformat(
                    start_str.replace("Z", "+00:00")
                )
                end_dt = datetime.datetime.fromisoformat(end_str.replace("Z", "+00:00"))
                start_formatted = start_dt.strftime("%Y-%m-%d")
                end_formatted = end_dt.strftime("%Y-%m-%d")
                console.print(
                    f"[blue]Auto-detected date range: {start_formatted} to {end_formatted}[/blue]\n"
                )
            except (ValueError, AttributeError):
                console.print(
                    f"[blue]Auto-detected date range: {start_str} to {end_str}[/blue]\n"
                )
    else:
        # No data collected, use provided dates or None
        if start_date:
            all_data["date_range"]["start"] = start_date.isoformat()
        if end_date:
            all_data["date_range"]["end"] = end_date.isoformat()

    return all_data
