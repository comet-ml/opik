"""Statistics calculation functions for usage report module."""

from typing import Any, Dict


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
