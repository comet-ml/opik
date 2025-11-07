"""Chart creation functions for usage report module."""

import datetime
import os
import time
import traceback
from typing import Any, Dict, List, Optional, Tuple

from rich.console import Console

from .utils import extract_metric_data

console = Console()


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
    try:
        import matplotlib.colors as mcolors
        import matplotlib.pyplot as plt
    except ImportError:
        raise ImportError(
            "matplotlib is required for chart generation. "
            "Please install it with: pip install matplotlib"
        )

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

    Note: This function creates charts in memory but does not save them to disk.
    Charts are generated and immediately closed. For saving charts, use create_individual_chart()
    which is used by the PDF report generation.

    Args:
        data: The extracted data dictionary
        output_dir: Directory parameter (kept for backward compatibility, not used)
    """
    try:
        import matplotlib.pyplot as plt
        from matplotlib.ticker import FuncFormatter
    except ImportError:
        raise ImportError(
            "matplotlib is required for chart generation. "
            "Please install it with: pip install matplotlib"
        )

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
    trace_data = extract_metric_data(projects, all_periods, "trace_count")
    token_data = extract_metric_data(
        projects, all_periods, "token_count", aggregate_token_count
    )
    cost_data = extract_metric_data(projects, all_periods, "cost")
    span_data = extract_metric_data(projects, all_periods, "span_count")

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
    try:
        import matplotlib.pyplot as plt
        from matplotlib.ticker import FuncFormatter
    except ImportError:
        raise ImportError(
            "matplotlib is required for chart generation. "
            "Please install it with: pip install matplotlib"
        )

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
        trace_data = extract_metric_data(projects, all_periods, "trace_count")

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
        token_data = extract_metric_data(
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
        cost_data = extract_metric_data(projects, all_periods, "cost")

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
        span_data = extract_metric_data(projects, all_periods, "span_count")

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

        # Ensure file is fully written to disk using file system sync operations
        # Retry loop to handle cases where file system hasn't fully flushed
        max_retries = 10
        retry_delay = 0.1
        file_ready = False

        for attempt in range(max_retries):
            if os.path.exists(chart_filename):
                try:
                    # Try to open the file to ensure it's accessible
                    with open(chart_filename, "rb") as f:
                        # Force file system sync
                        f.flush()
                        os.fsync(f.fileno())

                    # Verify file has content (size > 0)
                    if os.path.getsize(chart_filename) > 0:
                        # Verify file is readable
                        if os.access(chart_filename, os.R_OK):
                            file_ready = True
                            break
                except (OSError, IOError):
                    # File may still be writing, wait and retry
                    pass

            if attempt < max_retries - 1:
                time.sleep(retry_delay)

        if not file_ready:
            console.print(
                f"[yellow]Warning: Chart file was not ready after {max_retries} attempts: {chart_filename}[/yellow]"
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
