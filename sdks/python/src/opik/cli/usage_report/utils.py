"""Utility functions for usage report module."""

import datetime
from collections import defaultdict
from typing import Any, Callable, Dict, List, Optional, Tuple


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


def parse_and_normalize_datetime(
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


def normalize_timezone_for_comparison(
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


def extract_metric_data(
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


def process_experiment_for_stats(
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
        exp_date = parse_and_normalize_datetime(created_at_str, reference_tz)

        if exp_date is None:
            without_date = 1
        else:
            # Normalize timezones for comparison
            exp_date, start_date_aware, end_date_aware = (
                normalize_timezone_for_comparison(
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
