"""Dashboard validation invariants.

Structural invariants (always enforced, on every write) keep the config in a
state the frontend can render. Semantic checks (enforced only when constructing
or mutating a widget) mirror the frontend's product rules. Reads never validate —
the SDK must always be able to load dashboards created by a newer frontend.
"""

import logging
from typing import Any, Dict, List, Optional, Union

from opik import exceptions

from . import types

LOGGER = logging.getLogger(__name__)

_WIDGET_TYPES_BY_DASHBOARD_TYPE = {
    types.DashboardType.MULTI_PROJECT.value: {
        types.WidgetType.PROJECT_METRICS.value,
        types.WidgetType.PROJECT_STATS_CARD.value,
        types.WidgetType.TEXT_MARKDOWN.value,
    },
    types.DashboardType.EXPERIMENTS.value: {
        types.WidgetType.EXPERIMENTS_FEEDBACK_SCORES.value,
        types.WidgetType.EXPERIMENT_LEADERBOARD.value,
        types.WidgetType.TEXT_MARKDOWN.value,
    },
}

_PROJECT_METRIC_TYPES = {m.value for m in types.ProjectMetricType}
_STATS_CARD_METRICS = {m.value for m in types.StatsCardMetric}
_PROJECT_SCOPED_WIDGET_TYPES = {
    types.WidgetType.PROJECT_METRICS.value,
    types.WidgetType.PROJECT_STATS_CARD.value,
}


def inject_project_id(widget_dict: Dict[str, Any], project_id: Optional[str]) -> None:
    """Inject projectId into a project-scoped widget config.

    Raises DashboardValidationError if the widget is project-scoped but the dashboard
    has no project_id — callers should ensure the dashboard was created with a project.
    """
    if widget_dict.get("type") not in _PROJECT_SCOPED_WIDGET_TYPES:
        return
    if not project_id:
        raise exceptions.DashboardValidationError(
            f"Widget type {widget_dict.get('type')!r} requires a project-scoped dashboard. "
            "Pass project_name or project_id to create_dashboard."
        )
    widget_dict.setdefault("config", {})["projectId"] = project_id


def validate_structure(state: Dict[str, Any]) -> None:
    """Enforce widget/layout cross-references and id uniqueness across the dashboard."""
    seen_section_ids: set = set()
    seen_widget_ids: set = set()

    for section in state.get("sections", []):
        section_id = section.get("id")
        if section_id in seen_section_ids:
            raise exceptions.DashboardValidationError(
                f"Duplicate section id: {section_id!r}"
            )
        seen_section_ids.add(section_id)

        widget_ids = []
        for widget in section.get("widgets", []):
            widget_id = widget.get("id")
            if widget_id in seen_widget_ids:
                raise exceptions.DashboardValidationError(
                    f"Duplicate widget id: {widget_id!r}"
                )
            seen_widget_ids.add(widget_id)
            widget_ids.append(widget_id)

        widget_id_set = set(widget_ids)
        layout_ids = [item.get("i") for item in section.get("layout", [])]
        layout_id_set = set(layout_ids)

        missing_layout = widget_id_set - layout_id_set
        if missing_layout:
            raise exceptions.DashboardValidationError(
                f"Widgets without a layout item in section {section_id!r}: {missing_layout}"
            )

        orphan_layout = layout_id_set - widget_id_set
        if orphan_layout:
            raise exceptions.DashboardValidationError(
                f"Layout items referencing missing widgets in section {section_id!r}: {orphan_layout}"
            )


def validate_widget_for_dashboard(
    widget: Dict[str, Any], dashboard_type: Optional[str]
) -> None:
    """Construct-time semantic checks for a single widget being added/updated."""
    widget_type = widget.get("type")

    if dashboard_type is not None:
        allowed = _WIDGET_TYPES_BY_DASHBOARD_TYPE.get(dashboard_type)
        if allowed is not None and widget_type not in allowed:
            raise exceptions.DashboardValidationError(
                f"Widget type {widget_type!r} is not supported on a {dashboard_type!r} "
                f"dashboard. Allowed types: {sorted(allowed)}"
            )

    _warn_on_unknown_metric(widget_type, widget.get("config", {}))


def _warn_on_unknown_metric(widget_type: Optional[str], config: Dict[str, Any]) -> None:
    if widget_type == types.WidgetType.PROJECT_METRICS.value:
        metric = config.get("metricType")
        if metric is not None and metric not in _PROJECT_METRIC_TYPES:
            LOGGER.warning(
                "Unknown project_metrics metricType %r. Expected one of the ALL-CAPS "
                "ids in opik.dashboard.ProjectMetricType (e.g. 'TRACE_COUNT', 'DURATION').",
                metric,
            )
    elif widget_type == types.WidgetType.PROJECT_STATS_CARD.value:
        metric = config.get("metric")
        if (
            metric is not None
            and metric not in _STATS_CARD_METRICS
            and not str(metric).startswith(types.FEEDBACK_SCORES_PREFIX)
        ):
            LOGGER.warning(
                "Unknown project_stats_card metric %r. Expected one of the "
                "lowercase-dotted ids in opik.dashboard.StatsCardMetric (e.g. "
                "'trace_count', 'duration.p50') or a 'feedback_scores.<name>' id.",
                metric,
            )


def validate_writable_version(version: Optional[int]) -> None:
    """Refuse to write back a config whose schema version the SDK does not know.

    The SDK does not run the frontend migration chain, so re-stamping a config of
    an unknown version risks silently corrupting it.
    """
    if version is not None and version != types.DASHBOARD_VERSION:
        raise exceptions.DashboardValidationError(
            f"Refusing to write a dashboard with schema version {version}; this SDK "
            f"only understands version {types.DASHBOARD_VERSION}. Upgrade the Opik SDK "
            f"to modify this dashboard."
        )


def as_widget_dict(
    widget: Union[types.DashboardWidget, Dict[str, Any]],
) -> Dict[str, Any]:
    """Coerce a DashboardWidget model or a raw dict into a plain config dict."""
    if isinstance(widget, types.DashboardWidget):
        return widget.to_jsonable()
    if isinstance(widget, dict):
        return widget
    raise exceptions.DashboardValidationError(
        f"Expected a DashboardWidget or dict, got {type(widget).__name__}"
    )


def as_section_dicts(
    sections: List[Union[types.DashboardSection, Dict[str, Any]]],
) -> List[Dict[str, Any]]:
    """Coerce a list of DashboardSection models or raw dicts into plain dicts."""
    result: List[Dict[str, Any]] = []
    for section in sections:
        if isinstance(section, types.DashboardSection):
            result.append(section.to_jsonable())
        elif isinstance(section, dict):
            result.append(section)
        else:
            raise exceptions.DashboardValidationError(
                f"Expected a DashboardSection or dict, got {type(section).__name__}"
            )
    return result
