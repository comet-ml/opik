"""Usage report command for Opik CLI."""

import datetime
import json
import os
import sys
import traceback
import webbrowser
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import click
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter
from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch
from reportlab.platypus import (
    Image,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)
from rich.console import Console
from tqdm import tqdm

import opik

console = Console()

# Constants
# MAX_PAGINATION_PAGES sets a safety limit to avoid infinite loops in pagination.
# With a page size of 1000, this allows for up to 1 million items. For large workspaces, you can override
# this limit by setting the OPIK_MAX_PAGINATION_PAGES environment variable to a higher value.
try:
    MAX_PAGINATION_PAGES = int(os.environ.get("OPIK_MAX_PAGINATION_PAGES", "1000"))
except ValueError:
    MAX_PAGINATION_PAGES = 1000


def aggregate_by_unit(metrics_response: Any, unit: str = "month") -> Dict[str, float]:
    """
    Aggregate metrics by specified time unit.

    Args:
        metrics_response: ProjectMetricResponsePublic object from get_project_metrics
        unit: Time unit for aggregation - "month", "week", "day", or "hour"

    Returns:
        Dictionary mapping time period key to total value
    """
    unit_data: Dict[str, float] = defaultdict(float)

    if metrics_response.results:
        for result in metrics_response.results:
            if result.data:
                for data_point in result.data:
                    if data_point.value is not None:
                        # Generate key based on unit
                        if unit == "month":
                            key = data_point.time.strftime("%Y-%m")
                        elif unit == "week":
                            # ISO week format: YYYY-Www
                            year, week, _ = data_point.time.isocalendar()
                            key = f"{year}-W{week:02d}"
                        elif unit == "day":
                            key = data_point.time.strftime("%Y-%m-%d")
                        elif unit == "hour":
                            key = data_point.time.strftime("%Y-%m-%d-%H")
                        else:
                            raise ValueError(f"Unsupported unit: {unit}")
                        unit_data[key] += data_point.value

    return dict(unit_data)


def format_datetime_key(dt: datetime.datetime, unit: str) -> str:
    """
    Format a datetime object to a key string based on the specified unit.

    Args:
        dt: Datetime object to format
        unit: Time unit - "month", "week", "day", or "hour"

    Returns:
        Formatted key string
    """
    if unit == "month":
        return dt.strftime("%Y-%m")
    elif unit == "week":
        year, week, _ = dt.isocalendar()
        return f"{year}-W{week:02d}"
    elif unit == "day":
        return dt.strftime("%Y-%m-%d")
    elif unit == "hour":
        return dt.strftime("%Y-%m-%d-%H")
    else:
        raise ValueError(f"Unsupported unit: {unit}")


def _parse_and_normalize_datetime(
    dt_str: Any, reference_tz: Optional[datetime.tzinfo]
) -> Optional[datetime.datetime]:
    """
    Parse a datetime string and normalize it with respect to a reference timezone.

    Args:
        dt_str: Datetime string or datetime object to parse
        reference_tz: Reference timezone to use for naive datetimes

    Returns:
        Parsed datetime object, or None if parsing fails
    """
    if not dt_str:
        return None

    try:
        # If already a datetime object, return it
        if isinstance(dt_str, datetime.datetime):
            return dt_str

        # Parse ISO format datetime string
        if isinstance(dt_str, str):
            # Handle with or without timezone
            if "T" in dt_str:
                if dt_str.endswith("Z"):
                    dt = datetime.datetime.fromisoformat(dt_str.replace("Z", "+00:00"))
                elif "+" in dt_str or dt_str.count("-") > 2:
                    dt = datetime.datetime.fromisoformat(dt_str)
                else:
                    # Naive datetime
                    dt = datetime.datetime.fromisoformat(dt_str)
                    if reference_tz is not None:
                        # Make naive date timezone-aware
                        dt = dt.replace(tzinfo=reference_tz)
                return dt
            else:
                # Not a valid datetime string
                return None
        else:
            return dt_str
    except (ValueError, TypeError):
        return None


def _normalize_timezone_for_comparison(
    dt: datetime.datetime,
    query_start_date: datetime.datetime,
    query_end_date: datetime.datetime,
) -> Tuple[datetime.datetime, datetime.datetime, datetime.datetime]:
    """
    Normalize timezones for date comparison.

    Args:
        dt: Datetime to normalize
        query_start_date: Start date for comparison
        query_end_date: End date for comparison

    Returns:
        Tuple of (normalized_dt, normalized_start_date, normalized_end_date)
    """
    # Handle timezone differences
    if dt.tzinfo is None and query_start_date.tzinfo is not None:
        dt = dt.replace(tzinfo=query_start_date.tzinfo)
        start_date_aware = query_start_date
        end_date_aware = query_end_date
    elif dt.tzinfo is not None and query_start_date.tzinfo is None:
        start_date_aware = query_start_date.replace(tzinfo=dt.tzinfo)
        end_date_aware = query_end_date.replace(tzinfo=dt.tzinfo)
    else:
        start_date_aware = query_start_date
        end_date_aware = query_end_date

    return dt, start_date_aware, end_date_aware


def _extract_metric_data(
    projects: List[Dict[str, Any]],
    all_periods: List[str],
    metric_key: str,
    aggregation_fn: Optional[Callable[[Any], float]] = None,
) -> List[List[float]]:
    """
    Extract metric data from projects for all periods.

    Args:
        projects: List of project dictionaries with metrics_by_unit
        all_periods: List of period keys (e.g., "2024-01", "2024-02")
        metric_key: Key to extract from period_metrics (e.g., "trace_count", "cost")
        aggregation_fn: Optional function to aggregate metric values.
                       If None, uses default: sum dict values or use scalar value.

    Returns:
        List of lists, where each inner list contains metric values for one period
        across all projects
    """
    metric_data = []
    for period in all_periods:
        period_values = []
        for project in projects:
            period_metrics = project["metrics_by_unit"].get(period, {})
            metric_value = period_metrics.get(metric_key, 0)

            if aggregation_fn:
                metric_value = aggregation_fn(metric_value)
            elif isinstance(metric_value, dict):
                # Default: sum all dict values
                metric_value = sum(metric_value.values()) if metric_value else 0

            period_values.append(float(metric_value) if metric_value else 0.0)
        metric_data.append(period_values)

    return metric_data


def _process_experiment_for_stats(
    experiment_dict: Dict[str, Any],
    experiment_by_unit: Dict[str, int],
    all_dates: List[datetime.datetime],
    query_start_date: datetime.datetime,
    query_end_date: datetime.datetime,
    unit: str,
    start_date: Optional[datetime.datetime],
) -> Tuple[int, int, int]:
    """
    Process a single experiment dictionary and update statistics.

    Returns:
        Tuple of (in_range_count, without_date_count, outside_range_count)
    """
    in_range = 0
    without_date = 0
    outside_range = 0

    # Extract created_at from raw dict (handles missing fields gracefully)
    created_at_str = experiment_dict.get("created_at")
    if created_at_str:
        # Parse datetime using helper function
        reference_tz = start_date.tzinfo if start_date else None
        exp_date = _parse_and_normalize_datetime(created_at_str, reference_tz)

        if exp_date is None:
            without_date = 1
        else:
            # Normalize timezones for comparison
            exp_date, start_date_aware, end_date_aware = (
                _normalize_timezone_for_comparison(
                    exp_date, query_start_date, query_end_date
                )
            )

            # Check if within date range
            if exp_date.tzinfo is not None:
                date_check = start_date_aware <= exp_date <= end_date_aware
            else:
                date_check = query_start_date <= exp_date <= query_end_date

            if date_check:
                in_range = 1
                all_dates.append(exp_date)
                unit_key = format_datetime_key(exp_date, unit)
                experiment_by_unit[unit_key] += 1
            else:
                outside_range = 1
    else:
        without_date = 1

    return (in_range, without_date, outside_range)


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
                query_start_date = datetime.datetime.strptime(env_start_date, "%Y-%m-%d")
            except ValueError:
                console.print(f"[yellow]Warning: Invalid OPIK_DEFAULT_START_DATE format. Using start of current year.[/yellow]")
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
                            _normalize_timezone_for_comparison(
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
        # Try using REST client method first (handles parameters correctly)
        # If that fails due to validation errors, fall back to raw HTTP
        use_rest_client = True
        total_experiments = None
        httpx_client = client.rest_client._client_wrapper.httpx_client

        try:
            # Test if REST client works by getting first page
            # Filter by type="regular" to match UI behavior (UI only shows regular experiments)
            # Note: types parameter needs to be JSON-encoded array string
            test_page = client.rest_client.experiments.find_experiments(
                page=1,
                size=1000,
                types=json.dumps(
                    ["regular"]
                ),  # Filter to only regular experiments (matches UI)
                dataset_deleted=False,  # Filter out experiments with deleted datasets
            )
            total_experiments = test_page.total or 0
        except Exception:
            # Fall back to raw HTTP if REST client has validation issues
            use_rest_client = False
            # Get total count from raw HTTP request
            # Filter by type="regular" to match UI behavior
            # Note: types parameter needs to be JSON-encoded array string
            response = httpx_client.request(
                "v1/private/experiments",
                method="GET",
                params={
                    "page": 1,
                    "size": 1000,
                    "types": json.dumps(
                        ["regular"]
                    ),  # Filter to only regular experiments (matches UI)
                    "dataset_deleted": False,
                },
            )
            if response.status_code >= 200 and response.status_code < 300:
                response_data = response.json()
                total_experiments = response_data.get("total", 0)

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
                experiments_list: List[Dict[str, Any]] = []

                if use_rest_client:
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
                        # Convert to dict format for processing
                        experiments_dict_list = []
                        for exp in experiments_list:
                            if hasattr(exp, "model_dump"):
                                exp_dict = exp.model_dump()
                            elif hasattr(exp, "dict"):
                                exp_dict = exp.dict()
                            else:
                                # Already a dict
                                exp_dict = exp  # type: ignore[assignment]
                            experiments_dict_list.append(exp_dict)
                        experiments_list = experiments_dict_list
                    except Exception:
                        # If validation fails, switch to raw HTTP for remaining pages
                        use_rest_client = False
                        httpx_client = client.rest_client._client_wrapper.httpx_client
                        # Fall through to fetch using raw HTTP

                if not use_rest_client:
                    # Make direct HTTP request to bypass Pydantic validation
                    # Filter by type="regular" and exclude deleted datasets to match UI behavior
                    # Note: types parameter needs to be JSON-encoded array string
                    response = httpx_client.request(
                        "v1/private/experiments",
                        method="GET",
                        params={
                            "page": page,
                            "size": 1000,
                            "types": json.dumps(
                                ["regular"]
                            ),  # Filter to only regular experiments (matches UI)
                            "dataset_deleted": False,  # Boolean should be handled by encode_query
                        },
                    )

                    # Check for HTTP errors
                    if response.status_code < 200 or response.status_code >= 300:
                        console.print(
                            f"[yellow]    Warning: HTTP {response.status_code} when fetching experiments[/yellow]"
                        )
                        break

                    # Parse JSON directly to avoid Pydantic validation issues
                    response_data = response.json()
                    experiments_list = response_data.get("content", [])

                if not experiments_list or len(experiments_list) == 0:
                    break

                # Filter experiments to only include those with existing (non-deleted) datasets
                # This matches the UI behavior - UI only shows experiments whose datasets still exist
                # Experiments with deleted datasets may have missing or None dataset_name fields
                filtered_experiments = []
                skipped_count = 0
                for experiment_dict in experiments_list:
                    dataset_name = experiment_dict.get("dataset_name")
                    # Skip experiments that:
                    # 1. Have no dataset_name (None or missing) - these are from deleted datasets
                    # 2. Have a dataset_name that doesn't exist in our list of existing datasets
                    if not dataset_name:
                        # Missing dataset_name indicates deleted dataset
                        skipped_count += 1
                        continue
                    if (
                        existing_dataset_names
                        and dataset_name not in existing_dataset_names
                    ):
                        # Dataset doesn't exist (was deleted)
                        skipped_count += 1
                        continue
                    filtered_experiments.append(experiment_dict)

                # Count experiments by month based on created_at
                # Only process filtered experiments (those with existing datasets)
                for experiment_dict in filtered_experiments:
                    total_experiments_processed += 1
                    in_range, without_date, outside_range = (
                        _process_experiment_for_stats(
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
                                from datetime import timezone

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
                        max_results=10000,  # Adjust if needed
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
                                _normalize_timezone_for_comparison(
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


def _get_top_projects_and_others(
    projects: List[Dict[str, Any]],
    project_names: List[str],
    metric_data: List[List[float]],
    top_n: int = 12,
) -> Tuple[List[int], List[float], List[str], List]:
    """
    Identify top N projects by total usage and group the rest as "Others".

    Args:
        projects: List of project dictionaries
        project_names: List of project names
        metric_data: List of lists, where each inner list contains values for one period
        top_n: Number of top projects to show individually

    Returns:
        Tuple of (top_project_indices, others_data, labels, colors)
        - top_project_indices: List of indices for top projects
        - others_data: List of aggregated values for "Others" per period
        - labels: List of labels (top project names + "Others")
        - colors: List of colors for top projects + "Others"
    """
    # Calculate total usage per project across all periods
    project_totals = []
    for i in range(len(project_names)):
        total = sum(metric_data[j][i] for j in range(len(metric_data)))
        project_totals.append((i, total))

    # Sort by total (descending) and get top N
    project_totals.sort(key=lambda x: x[1], reverse=True)
    top_indices = [idx for idx, _ in project_totals[:top_n]]
    others_indices = [idx for idx, _ in project_totals[top_n:]]

    # Create labels
    labels = [project_names[i] for i in top_indices]
    if others_indices:
        labels.append(f"Others ({len(others_indices)} projects)")

    # Aggregate "Others" data
    others_data = []
    if others_indices:
        for period_idx in range(len(metric_data)):
            others_total = sum(metric_data[period_idx][i] for i in others_indices)
            others_data.append(others_total)
    else:
        others_data = [0.0] * len(metric_data)

    # Generate colors for top projects + Others
    import matplotlib.colors as mcolors

    colors_list = []
    colormaps = [
        plt.cm.tab20,
        plt.cm.tab20b,
        plt.cm.Set3,
        plt.cm.Pastel1,
        plt.cm.Pastel2,
        plt.cm.Set1,
        plt.cm.Set2,
    ]

    for i in range(len(top_indices)):
        if i < 20:
            colors_list.append(colormaps[0](i))
        elif i < 40:
            colors_list.append(colormaps[1](i - 20))
        elif i < 52:
            colors_list.append(colormaps[2]((i - 40) % 12))
        elif i < 61:
            colors_list.append(colormaps[3]((i - 52) % 9))
        elif i < 69:
            colors_list.append(colormaps[4]((i - 61) % 8))
        elif i < 78:
            colors_list.append(colormaps[5]((i - 69) % 9))
        elif i < 86:
            colors_list.append(colormaps[6]((i - 78) % 8))
        else:
            hue = (i * 0.618033988749895) % 1.0
            saturation = 0.6 + (i % 3) * 0.1
            value = 0.85 + (i % 2) * 0.1
            colors_list.append(mcolors.hsv_to_rgb([hue, saturation, value]))

    # Add gray color for "Others"
    if others_indices:
        colors_list.append("#808080")  # Gray for Others

    return top_indices, others_data, labels, colors_list


def create_charts(data: Dict[str, Any], output_dir: str = ".") -> None:
    """
    Create stacked bar charts for trace count, token count, cost, experiment count, and dataset count.

    Args:
        data: The extracted data dictionary
        output_dir: Directory to save charts (default: current directory)
    """
    # Get unit from data (default to month for backward compatibility)
    unit = data.get("unit", "month")

    # Prepare data for charts
    projects = [
        p for p in data["projects"] if "metrics_by_unit" in p and "error" not in p
    ]
    if not projects:
        console.print("[yellow]No project data available for charting.[/yellow]")
        return

    # Collect all time periods across all projects
    all_periods_set = set()
    for project in projects:
        all_periods_set.update(project["metrics_by_unit"].keys())
    all_periods: List[str] = sorted(all_periods_set)

    if not all_periods:
        console.print(f"[yellow]No {unit}ly data available for charting.[/yellow]")
        return

    # Prepare data arrays for each metric
    project_names = [p["project_name"] for p in projects]
    n_periods = len(all_periods)

    # Helper function for token count aggregation
    def aggregate_token_count(token_count: Any) -> float:
        """Aggregate token count: use total_tokens if available, otherwise sum all values."""
        if isinstance(token_count, dict):
            if "total_tokens" in token_count:
                return float(token_count["total_tokens"])
            else:
                return (
                    sum(float(v) for v in token_count.values()) if token_count else 0.0
                )
        else:
            return float(token_count) if token_count else 0.0

    # Extract metric data using helper function
    trace_data = _extract_metric_data(projects, all_periods, "trace_count")
    token_data = _extract_metric_data(
        projects, all_periods, "token_count", aggregate_token_count
    )
    cost_data = _extract_metric_data(projects, all_periods, "cost")
    span_data = _extract_metric_data(projects, all_periods, "span_count")

    # Format period labels for display based on unit
    period_labels = []
    for period in all_periods:
        if unit == "month":
            period_labels.append(
                datetime.datetime.strptime(period, "%Y-%m").strftime("%b %Y")
            )
        elif unit == "week":
            # Parse ISO week format: YYYY-Www
            try:
                if "-W" in period:
                    year, week = period.split("-W", 1)
                    period_labels.append(f"Week {week}, {year}")
                else:
                    period_labels.append(period)
            except (ValueError, IndexError):
                period_labels.append(period)
        elif unit == "day":
            period_labels.append(
                datetime.datetime.strptime(period, "%Y-%m-%d").strftime("%b %d, %Y")
            )
        elif unit == "hour":
            period_labels.append(
                datetime.datetime.strptime(period, "%Y-%m-%d-%H").strftime(
                    "%b %d, %Y %H:00"
                )
            )
        else:
            period_labels.append(period)

    # Get experiment data (workspace-level)
    experiment_data = []
    for period in all_periods:
        experiment_count = data.get("experiments_by_unit", {}).get(period, 0)
        experiment_data.append(float(experiment_count) if experiment_count else 0.0)

    # Get dataset data (workspace-level)
    dataset_data = []
    for period in all_periods:
        dataset_count = data.get("datasets_by_unit", {}).get(period, 0)
        dataset_data.append(float(dataset_count) if dataset_count else 0.0)

    # Create figure with 6 subplots
    # Increase height to give more room for charts (less space needed for legend now)
    fig, axes = plt.subplots(6, 1, figsize=(14, 20))
    unit_label = unit.capitalize()
    fig.suptitle(
        f'Opik Usage Metrics - {data["workspace"]} (by {unit_label})',
        fontsize=16,
        fontweight="bold",
    )

    # Chart 1: Trace Count - use top projects only
    ax1 = axes[0]
    x = range(n_periods)
    width = 0.8

    # Get top projects for trace count
    top_indices, others_data, trace_labels, trace_colors = _get_top_projects_and_others(
        projects, project_names, trace_data, top_n=18
    )

    bottom = [0] * n_periods
    for idx, (project_idx, label) in enumerate(
        zip(top_indices, trace_labels[: len(top_indices)])
    ):
        values: List[float] = [trace_data[j][project_idx] for j in range(n_periods)]
        ax1.bar(x, values, width, label=label, bottom=bottom, color=trace_colors[idx])
        bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

    # Add "Others" if present
    if others_data and any(v > 0 for v in others_data):
        ax1.bar(
            x,
            others_data,
            width,
            label=trace_labels[-1],
            bottom=bottom,
            color=trace_colors[-1],
        )

    ax1.set_xlabel(unit_label)
    ax1.set_ylabel("Trace Count")
    ax1.set_title(f"Trace Count by {unit_label} (Stacked by Project)")
    ax1.set_xticks(x)
    ax1.set_xticklabels(period_labels, rotation=45, ha="right")
    ax1.legend(
        bbox_to_anchor=(0.5, -0.20),
        loc="upper center",
        ncol=4,
        fontsize=7,
        frameon=True,
    )
    ax1.grid(axis="y", alpha=0.3)

    # Chart 2: Token Count - use top projects only
    ax2 = axes[1]

    # Get top projects for token count
    top_indices, others_data, token_labels, token_colors = _get_top_projects_and_others(
        projects, project_names, token_data, top_n=18
    )

    bottom = [0] * n_periods
    for idx, (project_idx, label) in enumerate(
        zip(top_indices, token_labels[: len(top_indices)])
    ):
        values: List[float] = [token_data[j][project_idx] for j in range(n_periods)]  # type: ignore[no-redef]
        ax2.bar(x, values, width, label=label, bottom=bottom, color=token_colors[idx])
        bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

    # Add "Others" if present
    if others_data and any(v > 0 for v in others_data):
        ax2.bar(
            x,
            others_data,
            width,
            label=token_labels[-1],
            bottom=bottom,
            color=token_colors[-1],
        )

    ax2.set_xlabel(unit_label)
    ax2.set_ylabel("Token Count")
    ax2.set_title(f"Token Count by {unit_label} (Stacked by Project)")
    ax2.set_xticks(x)
    ax2.set_xticklabels(period_labels, rotation=45, ha="right")
    ax2.legend(
        bbox_to_anchor=(0.5, -0.20),
        loc="upper center",
        ncol=4,
        fontsize=7,
        frameon=True,
    )
    ax2.grid(axis="y", alpha=0.3)
    # Format y-axis to show in thousands/millions
    ax2.yaxis.set_major_formatter(
        FuncFormatter(
            lambda x, p: (
                f"{x/1e6:.2f}M"
                if x >= 1e6
                else f"{x/1e3:.0f}K"
                if x >= 1e3
                else f"{x:.0f}"
            )
        )
    )

    # Chart 3: Cost - use top projects only
    ax3 = axes[2]

    # Get top projects for cost
    top_indices, others_data, cost_labels, cost_colors = _get_top_projects_and_others(
        projects, project_names, cost_data, top_n=18
    )

    bottom = [0] * n_periods
    for idx, (project_idx, label) in enumerate(
        zip(top_indices, cost_labels[: len(top_indices)])
    ):
        values: List[float] = [cost_data[j][project_idx] for j in range(n_periods)]  # type: ignore[no-redef]
        ax3.bar(x, values, width, label=label, bottom=bottom, color=cost_colors[idx])
        bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

    # Add "Others" if present
    if others_data and any(v > 0 for v in others_data):
        ax3.bar(
            x,
            others_data,
            width,
            label=cost_labels[-1],
            bottom=bottom,
            color=cost_colors[-1],
        )

    ax3.set_xlabel(unit_label)
    ax3.set_ylabel("Cost ($)")
    ax3.set_title(f"Cost by {unit_label} (Stacked by Project)")
    ax3.set_xticks(x)
    ax3.set_xticklabels(period_labels, rotation=45, ha="right")
    ax3.legend(
        bbox_to_anchor=(0.5, -0.20),
        loc="upper center",
        ncol=4,
        fontsize=7,
        frameon=True,
    )
    ax3.grid(axis="y", alpha=0.3)
    # Format y-axis for currency
    ax3.yaxis.set_major_formatter(FuncFormatter(lambda x, p: f"${x:.2f}"))

    # Chart 4: Experiment Count (workspace-level, not stacked)
    ax4 = axes[3]
    ax4.bar(x, experiment_data, width, color="steelblue", alpha=0.7)

    ax4.set_xlabel(unit_label)
    ax4.set_ylabel("Experiment Count")
    ax4.set_title(f"Experiment Count by {unit_label} (Workspace Total)")
    ax4.set_xticks(x)
    ax4.set_xticklabels(period_labels, rotation=45, ha="right")
    ax4.grid(axis="y", alpha=0.3)

    # Chart 5: Dataset Count (workspace-level, not stacked)
    ax5 = axes[4]
    ax5.bar(x, dataset_data, width, color="darkgreen", alpha=0.7)

    ax5.set_xlabel(unit_label)
    ax5.set_ylabel("Dataset Count")
    ax5.set_title(f"Dataset Count by {unit_label} (Workspace Total)")
    ax5.set_xticks(x)
    ax5.set_xticklabels(period_labels, rotation=45, ha="right")
    ax5.grid(axis="y", alpha=0.3)

    # Chart 6: Span Count - use top projects only
    ax6 = axes[5]

    # Get top projects for span count
    top_indices, others_data, span_labels, span_colors = _get_top_projects_and_others(
        projects, project_names, span_data, top_n=18
    )

    bottom = [0] * n_periods
    for idx, (project_idx, label) in enumerate(
        zip(top_indices, span_labels[: len(top_indices)])
    ):
        values: List[float] = [span_data[j][project_idx] for j in range(n_periods)]  # type: ignore[no-redef]
        ax6.bar(x, values, width, label=label, bottom=bottom, color=span_colors[idx])
        bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

    # Add "Others" if present
    if others_data and any(v > 0 for v in others_data):
        ax6.bar(
            x,
            others_data,
            width,
            label=span_labels[-1],
            bottom=bottom,
            color=span_colors[-1],
        )

    ax6.set_xlabel(unit_label)
    ax6.set_ylabel("Span Count")
    ax6.set_title(f"Span Count by {unit_label} (Stacked by Project)")
    ax6.set_xticks(x)
    ax6.set_xticklabels(period_labels, rotation=45, ha="right")
    ax6.legend(
        bbox_to_anchor=(0.5, -0.20),
        loc="upper center",
        ncol=4,
        fontsize=7,
        frameon=True,
    )
    ax6.grid(axis="y", alpha=0.3)

    # Use rect parameter to make room for legends below charts (more space for lower legends)
    plt.tight_layout(rect=[0, 0.0, 1, 0.98])

    # Save chart
    chart_filename = os.path.join(
        output_dir, f"opik_usage_charts_{data['workspace']}.png"
    )
    plt.savefig(chart_filename, dpi=300, bbox_inches="tight")
    console.print(f"[green]Charts saved to {chart_filename}[/green]")

    plt.close()


def create_individual_chart(
    data: Dict[str, Any],
    chart_type: str,
    output_dir: str = ".",
) -> Optional[str]:
    """
    Create an individual chart figure for a specific chart type.

    Args:
        data: The extracted data dictionary
        chart_type: Type of chart - "trace_count", "token_count", "cost", "experiment_count", "dataset_count", "span_count"
        output_dir: Directory to save chart (default: current directory)

    Returns:
        Path to saved chart image file, or None if creation failed
    """
    # Get unit from data (default to month for backward compatibility)
    unit = data.get("unit", "month")

    # Prepare data for charts
    projects = [
        p for p in data["projects"] if "metrics_by_unit" in p and "error" not in p
    ]
    if not projects:
        return None

    # Collect all time periods across all projects
    all_periods_set = set()
    for project in projects:
        all_periods_set.update(project["metrics_by_unit"].keys())
    all_periods: List[str] = sorted(all_periods_set)

    if not all_periods:
        return None

    # Prepare data arrays for each metric
    project_names = [p["project_name"] for p in projects]
    n_periods = len(all_periods)

    # Format period labels for display based on unit
    period_labels = []
    for period in all_periods:
        if unit == "month":
            period_labels.append(
                datetime.datetime.strptime(period, "%Y-%m").strftime("%b %Y")
            )
        elif unit == "week":
            try:
                if "-W" in period:
                    year, week = period.split("-W", 1)
                    period_labels.append(f"Week {week}, {year}")
                else:
                    period_labels.append(period)
            except (ValueError, IndexError):
                period_labels.append(period)
        elif unit == "day":
            period_labels.append(
                datetime.datetime.strptime(period, "%Y-%m-%d").strftime("%b %d, %Y")
            )
        elif unit == "hour":
            period_labels.append(
                datetime.datetime.strptime(period, "%Y-%m-%d-%H").strftime(
                    "%b %d, %Y %H:00"
                )
            )
        else:
            period_labels.append(period)

    # Create figure - use same size as reference file for consistency
    fig, ax = plt.subplots(figsize=(14, 8))
    unit_label = unit.capitalize()
    x = range(n_periods)
    width = 0.8

    if chart_type == "trace_count":
        # Trace count data
        trace_data = _extract_metric_data(projects, all_periods, "trace_count")

        # Get top projects for trace count
        top_indices, others_data, labels, colors = _get_top_projects_and_others(
            projects, project_names, trace_data, top_n=18
        )

        bottom = [0] * n_periods
        for idx, (project_idx, label) in enumerate(
            zip(top_indices, labels[: len(top_indices)])
        ):
            values: List[float] = [trace_data[j][project_idx] for j in range(n_periods)]
            ax.bar(x, values, width, label=label, bottom=bottom, color=colors[idx])
            bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

        # Add "Others" if present
        if others_data and any(v > 0 for v in others_data):
            ax.bar(
                x, others_data, width, label=labels[-1], bottom=bottom, color=colors[-1]
            )

        ax.set_ylabel("Trace Count")
        ax.set_title(f"Trace Count by {unit_label} (Stacked by Project)")

    elif chart_type == "token_count":
        # Helper function for token count aggregation
        def aggregate_token_count(token_count: Any) -> float:
            """Aggregate token count: use total_tokens if available, otherwise sum all values."""
            if isinstance(token_count, dict):
                if "total_tokens" in token_count:
                    return float(token_count["total_tokens"])
                else:
                    return (
                        sum(float(v) for v in token_count.values())
                        if token_count
                        else 0.0
                    )
            else:
                return float(token_count) if token_count else 0.0

        # Token count data
        token_data = _extract_metric_data(
            projects, all_periods, "token_count", aggregate_token_count
        )

        # Get top projects for token count
        top_indices, others_data, labels, colors = _get_top_projects_and_others(
            projects, project_names, token_data, top_n=18
        )

        bottom = [0] * n_periods
        for idx, (project_idx, label) in enumerate(
            zip(top_indices, labels[: len(top_indices)])
        ):
            values: List[float] = [token_data[j][project_idx] for j in range(n_periods)]  # type: ignore[no-redef]
            ax.bar(x, values, width, label=label, bottom=bottom, color=colors[idx])
            bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

        # Add "Others" if present
        if others_data and any(v > 0 for v in others_data):
            ax.bar(
                x, others_data, width, label=labels[-1], bottom=bottom, color=colors[-1]
            )

        ax.set_ylabel("Token Count")
        ax.set_title(f"Token Count by {unit_label} (Stacked by Project)")
        ax.yaxis.set_major_formatter(
            FuncFormatter(
                lambda x, p: (
                    f"{x/1e6:.2f}M"
                    if x >= 1e6
                    else f"{x/1e3:.0f}K"
                    if x >= 1e3
                    else f"{x:.0f}"
                )
            )
        )

    elif chart_type == "cost":
        # Cost data
        cost_data = _extract_metric_data(projects, all_periods, "cost")

        # Get top projects for cost
        top_indices, others_data, labels, colors = _get_top_projects_and_others(
            projects, project_names, cost_data, top_n=18
        )

        bottom = [0] * n_periods
        for idx, (project_idx, label) in enumerate(
            zip(top_indices, labels[: len(top_indices)])
        ):
            values: List[float] = [cost_data[j][project_idx] for j in range(n_periods)]  # type: ignore[no-redef]
            ax.bar(x, values, width, label=label, bottom=bottom, color=colors[idx])
            bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

        # Add "Others" if present
        if others_data and any(v > 0 for v in others_data):
            ax.bar(
                x, others_data, width, label=labels[-1], bottom=bottom, color=colors[-1]
            )

        ax.set_ylabel("Cost ($)")
        ax.set_title(f"Cost by {unit_label} (Stacked by Project)")
        ax.yaxis.set_major_formatter(FuncFormatter(lambda x, p: f"${x:.2f}"))

    elif chart_type == "experiment_count":
        # Experiment data (workspace-level)
        experiment_data = []
        for period in all_periods:
            experiment_count = data.get("experiments_by_unit", {}).get(period, 0)
            experiment_data.append(float(experiment_count) if experiment_count else 0.0)

        ax.bar(x, experiment_data, width, color="steelblue", alpha=0.7)
        ax.set_ylabel("Experiment Count")
        ax.set_title(f"Experiment Count by {unit_label} (Workspace Total)")

    elif chart_type == "dataset_count":
        # Dataset data (workspace-level)
        dataset_data = []
        for period in all_periods:
            dataset_count = data.get("datasets_by_unit", {}).get(period, 0)
            dataset_data.append(float(dataset_count) if dataset_count else 0.0)

        ax.bar(x, dataset_data, width, color="darkgreen", alpha=0.7)
        ax.set_ylabel("Dataset Count")
        ax.set_title(f"Dataset Count by {unit_label} (Workspace Total)")

    elif chart_type == "span_count":
        # Span count data
        span_data = _extract_metric_data(projects, all_periods, "span_count")

        # Get top projects for span count
        top_indices, others_data, labels, colors = _get_top_projects_and_others(
            projects, project_names, span_data, top_n=18
        )

        bottom = [0] * n_periods
        for idx, (project_idx, label) in enumerate(
            zip(top_indices, labels[: len(top_indices)])
        ):
            values: List[float] = [span_data[j][project_idx] for j in range(n_periods)]  # type: ignore[no-redef]
            ax.bar(x, values, width, label=label, bottom=bottom, color=colors[idx])
            bottom = [float(bottom[j] + values[j]) for j in range(n_periods)]  # type: ignore[misc]

        # Add "Others" if present
        if others_data and any(v > 0 for v in others_data):
            ax.bar(
                x, others_data, width, label=labels[-1], bottom=bottom, color=colors[-1]
            )

        ax.set_ylabel("Span Count")
        ax.set_title(f"Span Count by {unit_label} (Stacked by Project)")

    else:
        plt.close()
        return None

    ax.set_xlabel(unit_label)
    ax.set_xticks(x)
    ax.set_xticklabels(period_labels, rotation=45, ha="right")
    if chart_type in ["trace_count", "token_count", "cost", "span_count"]:
        # Use compact legend with top projects only (max 19 items: 18 top + Others)
        ax.legend(
            bbox_to_anchor=(0.5, -0.25),
            loc="upper center",
            ncol=4,
            fontsize=9,
            framealpha=0.9,
        )
    ax.grid(axis="y", alpha=0.3)

    # Use rect parameter to make room for legends below charts (more space for lower legends)
    plt.tight_layout(rect=[0, 0.05, 1, 1])

    # Save chart to temporary file (use absolute path)
    chart_filename = os.path.join(
        output_dir, f"opik_chart_{chart_type}_{data['workspace']}.png"
    )
    chart_filename = os.path.abspath(chart_filename)

    # Ensure output directory exists
    chart_dir = os.path.dirname(chart_filename)
    if chart_dir and not os.path.exists(chart_dir):
        os.makedirs(chart_dir, exist_ok=True)

    try:
        plt.savefig(chart_filename, dpi=300, bbox_inches="tight")
        plt.close()

        # Small delay to ensure file is fully written to disk
        import time

        time.sleep(0.1)

        # Verify file was actually created and is readable
        if not os.path.exists(chart_filename):
            console.print(
                f"[yellow]Warning: Chart file was not created: {chart_filename}[/yellow]"
            )
            return None

        if not os.access(chart_filename, os.R_OK):
            console.print(
                f"[yellow]Warning: Chart file is not readable: {chart_filename}[/yellow]"
            )
            return None

        # Verify file has content (size > 0)
        if os.path.getsize(chart_filename) == 0:
            console.print(
                f"[yellow]Warning: Chart file is empty: {chart_filename}[/yellow]"
            )
            return None

        return chart_filename
    except Exception as e:
        plt.close()
        console.print(
            f"[yellow]Warning: Could not save chart {chart_type}: {e}[/yellow]"
        )
        traceback.print_exc()
        return None


def calculate_statistics(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Calculate summary statistics from the usage data.

    Args:
        data: The extracted data dictionary

    Returns:
        Dictionary containing calculated statistics
    """
    stats = {
        "workspace": data.get("workspace", "Unknown"),
        "extraction_date": data.get("extraction_date", ""),
        "date_range": data.get("date_range", {}),
        "unit": data.get("unit", "month"),
        "total_projects": len(data.get("projects", [])),
        "projects_with_data": 0,
        "total_experiments": 0,
        "total_datasets": data.get("total_datasets", 0),
        "total_traces": 0.0,
        "total_spans": 0.0,
        "total_tokens": 0.0,
        "total_cost": 0.0,
        "periods_with_data": 0,
    }

    projects = data.get("projects", [])
    all_periods_set = set()

    for project in projects:
        if "metrics_by_unit" in project and "error" not in project:
            stats["projects_with_data"] += 1
            all_periods_set.update(project["metrics_by_unit"].keys())

            for period_metrics in project["metrics_by_unit"].values():
                # Trace count
                trace_count = period_metrics.get("trace_count", 0)
                if isinstance(trace_count, dict):
                    trace_count = sum(trace_count.values()) if trace_count else 0
                stats["total_traces"] += float(trace_count) if trace_count else 0.0

                # Span count
                span_count = period_metrics.get("span_count", 0)
                if isinstance(span_count, dict):
                    span_count = sum(span_count.values()) if span_count else 0
                stats["total_spans"] += float(span_count) if span_count else 0.0

                # Token count
                token_count = period_metrics.get("token_count", {})
                if isinstance(token_count, dict):
                    if "total_tokens" in token_count:
                        stats["total_tokens"] += float(token_count["total_tokens"])
                    else:
                        stats["total_tokens"] += (
                            sum(float(v) for v in token_count.values())
                            if token_count
                            else 0.0
                        )
                else:
                    stats["total_tokens"] += float(token_count) if token_count else 0.0

                # Cost
                cost = period_metrics.get("cost", 0)
                if isinstance(cost, dict):
                    cost = sum(cost.values()) if cost else 0
                stats["total_cost"] += float(cost) if cost else 0.0

    # Experiment count
    experiments_by_unit = data.get("experiments_by_unit", {})
    stats["total_experiments"] = sum(experiments_by_unit.values())
    stats["periods_with_data"] = len(all_periods_set)

    return stats


def create_pdf_report(data: Dict[str, Any], output_dir: str = ".") -> str:
    """
    Create a PDF report with statistics page and individual chart pages.

    Args:
        data: The extracted data dictionary
        output_dir: Directory to save PDF (default: current directory)

    Returns:
        Path to saved PDF file
    """
    # Calculate statistics
    stats = calculate_statistics(data)

    # Create PDF
    pdf_filename = os.path.join(
        output_dir, f"opik_usage_report_{data['workspace']}.pdf"
    )
    doc = SimpleDocTemplate(pdf_filename, pagesize=letter)
    story = []

    # Get styles
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "CustomTitle",
        parent=styles["Heading1"],
        fontSize=24,
        textColor=colors.HexColor("#1a1a1a"),
        spaceAfter=30,
        alignment=1,  # Center alignment
    )
    heading_style = ParagraphStyle(
        "CustomHeading",
        parent=styles["Heading2"],
        fontSize=16,
        textColor=colors.HexColor("#2c3e50"),
        spaceAfter=12,
    )

    # Title page / First page with statistics
    story.append(Paragraph("Opik Usage Report", title_style))
    story.append(Spacer(1, 0.3 * inch))

    # Statistics section
    story.append(Paragraph("Summary Statistics", heading_style))
    story.append(Spacer(1, 0.1 * inch))

    # Format dates for display
    extraction_date_str = "N/A"
    if stats["extraction_date"]:
        try:
            extraction_date_str = stats["extraction_date"][:10]
        except (TypeError, IndexError):
            extraction_date_str = (
                str(stats["extraction_date"])[:10]
                if stats["extraction_date"]
                else "N/A"
            )

    start_date_str = "N/A"
    end_date_str = "N/A"
    if stats["date_range"].get("start"):
        try:
            start_date_str = stats["date_range"]["start"][:10]
        except (TypeError, IndexError):
            start_date_str = (
                str(stats["date_range"]["start"])[:10]
                if stats["date_range"]["start"]
                else "N/A"
            )
    if stats["date_range"].get("end"):
        try:
            end_date_str = stats["date_range"]["end"][:10]
        except (TypeError, IndexError):
            end_date_str = (
                str(stats["date_range"]["end"])[:10]
                if stats["date_range"]["end"]
                else "N/A"
            )

    # Create statistics table
    stats_data = [
        ["Workspace", stats["workspace"]],
        ["Extraction Date", extraction_date_str],
        ["Date Range", f"{start_date_str} to {end_date_str}"],
        ["Aggregation Unit", stats["unit"].capitalize()],
        ["", ""],  # Separator row
        ["Total Projects", str(stats["total_projects"])],
        ["Projects with Data", str(stats["projects_with_data"])],
        ["Periods with Data", str(stats["periods_with_data"])],
        ["", ""],  # Separator row
        ["Total Experiments", f"{stats['total_experiments']:,}"],
        ["Total Datasets", f"{stats['total_datasets']:,}"],
        ["Total Traces", f"{stats['total_traces']:,.0f}"],
        ["Total Spans", f"{stats['total_spans']:,.0f}"],
        ["Total Tokens", f"{stats['total_tokens']:,.0f}"],
        ["Total Cost", f"${stats['total_cost']:,.2f}"],
    ]

    stats_table = Table(stats_data, colWidths=[2.5 * inch, 4 * inch])
    stats_table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#ecf0f1")),
                ("TEXTCOLOR", (0, 0), (-1, -1), colors.HexColor("#2c3e50")),
                ("ALIGN", (0, 0), (-1, -1), "LEFT"),
                ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
                ("FONTNAME", (1, 0), (1, -1), "Helvetica"),
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                ("TOPPADDING", (0, 0), (-1, -1), 8),
                ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#bdc3c7")),
            ]
        )
    )

    story.append(stats_table)
    story.append(PageBreak())

    # Create individual charts and add to PDF
    chart_types = [
        ("trace_count", "Trace Count"),
        ("span_count", "Span Count"),
        ("token_count", "Token Count"),
        ("cost", "Cost"),
        ("experiment_count", "Experiment Count"),
        ("dataset_count", "Dataset Count"),
    ]

    chart_files_to_cleanup = []  # Keep track of files to delete after PDF is built

    for chart_type, chart_title in chart_types:
        try:
            chart_path = create_individual_chart(data, chart_type, output_dir)
            if chart_path:
                # Ensure path is absolute
                chart_path = os.path.abspath(chart_path)
                # Double-check file exists and is readable
                if os.path.exists(chart_path) and os.access(chart_path, os.R_OK):
                    # Add chart title
                    story.append(Paragraph(chart_title, heading_style))
                    story.append(Spacer(1, 0.1 * inch))

                    # Add chart image (legend is already included in the chart image below the chart)
                    try:
                        # Use absolute path and verify file is readable
                        if not os.path.exists(chart_path):
                            console.print(
                                f"[yellow]Warning: Chart file disappeared: {chart_path}[/yellow]"
                            )
                            continue

                        img = Image(chart_path, width=7 * inch, height=4.5 * inch)
                        story.append(img)
                        story.append(Spacer(1, 0.1 * inch))
                        story.append(PageBreak())

                        # Track file for cleanup after PDF is built
                        chart_files_to_cleanup.append(chart_path)
                    except Exception as img_error:
                        console.print(
                            f"[yellow]Warning: Could not add chart image {chart_title}: {img_error}[/yellow]"
                        )
                        # Try to clean up the file if we couldn't use it
                        try:
                            if os.path.exists(chart_path):
                                os.remove(chart_path)
                        except Exception:
                            pass
                else:
                    console.print(
                        f"[yellow]Warning: Chart file not found or not readable: {chart_path}[/yellow]"
                    )
            else:
                console.print(
                    f"[yellow]Warning: Could not create chart: {chart_title}[/yellow]"
                )
        except Exception as chart_error:
            console.print(
                f"[yellow]Warning: Error creating chart {chart_title}: {chart_error}[/yellow]"
            )
            traceback.print_exc()
            continue  # Skip this chart and continue with others

    # Build PDF (this is when reportlab actually reads the image files)
    try:
        doc.build(story)
    finally:
        # Clean up temporary chart files after PDF is built
        for chart_path in chart_files_to_cleanup:
            try:
                if os.path.exists(chart_path):
                    os.remove(chart_path)
            except Exception:
                pass  # Ignore cleanup errors

    return pdf_filename


@click.command(name="usage-report")
@click.argument("workspace", type=str)
@click.option(
    "--start-date",
    type=str,
    help="Start date (YYYY-MM-DD). Defaults to None (auto-detect from data).",
)
@click.option(
    "--end-date",
    type=str,
    help="End date (YYYY-MM-DD). Defaults to None (auto-detect from data).",
)
@click.option(
    "--unit",
    type=click.Choice(["month", "week", "day", "hour"], case_sensitive=False),
    default="month",
    help="Time unit for aggregation (month, week, day, or hour). Defaults to 'month'.",
)
@click.option(
    "--output",
    "-o",
    type=click.Path(file_okay=True, dir_okay=False, writable=True),
    default="opik_usage_report.json",
    help="Output JSON file path. Defaults to opik_usage_report.json.",
)
@click.option(
    "--no-charts",
    is_flag=True,
    help="Skip generating charts.",
)
@click.option(
    "--open",
    is_flag=True,
    help="Automatically open the generated PDF report in the default viewer.",
)
@click.pass_context
def usage_report(
    ctx: click.Context,
    workspace: str,
    start_date: Optional[str],
    end_date: Optional[str],
    unit: str,
    output: str,
    no_charts: bool,
    open_pdf: bool,
) -> None:
    """
    Extract Opik usage data for a workspace.

    This command extracts project-level metrics from Opik for a specific workspace:
    - Loops through all projects in the workspace
    - Gets trace count, cost, and token count
    - Gets experiment and dataset counts (workspace-level)
    - Aggregates data by the specified time unit (month, week, day, or hour)

    Note: The API supports HOURLY, DAILY, and WEEKLY intervals, but not MONTHLY.
    We use DAILY interval and aggregate by the specified unit.

    WORKSPACE: Workspace name to extract data from.

    Examples:

      Extract data with auto-detected date range, aggregated by month (default):

        opik usage-report my-workspace

      Extract data aggregated by week:

        opik usage-report my-workspace --unit week

      Extract data for specific date range, aggregated by day:

        opik usage-report my-workspace --start-date 2024-01-01 --end-date 2024-12-31 --unit day
    """
    try:
        # Get API key from context (set by main CLI)
        api_key = ctx.obj.get("api_key") if ctx.obj else None

        # Parse dates if provided, otherwise None
        start_date_obj = None
        if start_date:
            start_date_obj = datetime.datetime.strptime(start_date, "%Y-%m-%d")

        end_date_obj = None
        if end_date:
            end_date_obj = datetime.datetime.strptime(end_date, "%Y-%m-%d")

        console.print("[green]Starting Opik data extraction...[/green]\n")

        data = extract_project_data(
            workspace, api_key, start_date_obj, end_date_obj, unit
        )

        # Calculate and add summary statistics to the data
        console.print("[blue]Calculating summary statistics...[/blue]")
        stats = calculate_statistics(data)
        data["statistics"] = stats

        # Save to JSON file
        console.print(f"\n[cyan]{'='*60}[/cyan]")
        console.print(f"[blue]Saving data to {output}...[/blue]")
        with open(output, "w") as f:
            json.dump(data, f, indent=2, default=str)

        console.print(
            f"[green]Data extraction complete! Results saved to {output}[/green]"
        )

        # Generate charts and PDF
        if not no_charts:
            console.print(f"\n[cyan]{'='*60}[/cyan]")
            console.print("[blue]Generating charts...[/blue]")
            try:
                output_path = Path(output)
                output_dir = (
                    output_path.parent if output_path.parent != Path(".") else "."
                )
                create_charts(data, output_dir=str(output_dir))
            except Exception as e:
                console.print(
                    f"[yellow]Warning: Could not generate charts: {e}[/yellow]"
                )

            # Generate PDF report
            console.print(f"\n[cyan]{'='*60}[/cyan]")
            console.print("[blue]Generating PDF report...[/blue]")
            try:
                output_path = Path(output)
                output_dir = (
                    output_path.parent if output_path.parent != Path(".") else "."
                )
                pdf_filename = create_pdf_report(data, output_dir=str(output_dir))
                console.print(f"[green]PDF report saved to {pdf_filename}[/green]")

                # Open PDF if --open flag is set
                if open_pdf:
                    pdf_path = os.path.abspath(pdf_filename)
                    if os.path.exists(pdf_path):
                        webbrowser.open(f"file://{pdf_path}")
                        console.print("[green]Opened PDF in default viewer[/green]")
                    else:
                        console.print(
                            f"[yellow]Warning: PDF file not found: {pdf_path}[/yellow]"
                        )
            except Exception as e:
                console.print(
                    f"[yellow]Warning: Could not generate PDF report: {e}[/yellow]"
                )
                traceback.print_exc()

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
