"""End-to-end contract and connectivity tests for the dashboard wrapper.

These intentionally cover only the happy-path lifecycle against a real backend —
that the SDK can create, read, mutate, find, and delete a dashboard, and that the
config blob round-trips through the backend with the schema the frontend expects.
Exhaustive validation/serialization cases live in the unit tests.
"""

import opik
from opik import dashboard
from opik.rest_api.core.api_error import ApiError
from ..testlib import generate_project_name
import pytest

PROJECT_NAME = generate_project_name("e2e", __name__)


@pytest.fixture
def project_id(opik_client: opik.Opik) -> str:
    """Ensure the module project exists and return its id (idempotent)."""
    try:
        opik_client.rest_client.projects.create_project(name=PROJECT_NAME)
    except ApiError:
        pass  # already exists
    return opik_client.rest_client.projects.retrieve_project(name=PROJECT_NAME).id


@pytest.fixture
def created_dashboards(opik_client: opik.Opik):
    """Track created dashboards and clean them up even if an assertion fails."""
    ids = []
    yield ids
    for dashboard_id in ids:
        try:
            opik_client.delete_dashboard(dashboard_id)
        except ApiError:
            pass


def test_dashboard_lifecycle__happyflow(
    opik_client: opik.Opik, project_id: str, created_dashboards: list
):
    dash = opik_client.create_dashboard(
        name=f"e2e-dashboard-{PROJECT_NAME}",
        type=dashboard.DashboardType.MULTI_PROJECT,
        description="E2E test dashboard",
    )
    created_dashboards.append(dash.id)

    assert dash.id is not None
    assert dash.type == "multi_project"
    section_id = dash.sections[0].id

    # Stats card uses the lowercase-dotted metric namespace.
    dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.PROJECT_STATS_CARD,
            title="Total traces",
            config=dashboard.ProjectStatsCardConfig(
                project_id=project_id,
                metric=dashboard.StatsCardMetric.TRACE_COUNT,
            ),
        ),
    )
    # Time-series uses the ALL-CAPS metricType namespace, plus a breakdown.
    dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.PROJECT_METRICS,
            title="Duration by model",
            config=dashboard.ProjectMetricsConfig(
                project_id=project_id,
                metric_type=dashboard.ProjectMetricType.DURATION,
                breakdown=dashboard.BreakdownConfig(
                    field=dashboard.BreakdownField.MODEL
                ),
            ),
        ),
    )

    # Re-fetch from the backend and assert the persisted config contract.
    fetched = opik_client.get_dashboard(dash.id)
    config = fetched.config
    assert config["version"] == dashboard.DashboardState().version
    assert len(config["sections"]) == 1

    widgets = config["sections"][0]["widgets"]
    configs_by_type = {w["type"]: w["config"] for w in widgets}
    assert configs_by_type["project_stats_card"]["metric"] == "trace_count"
    assert configs_by_type["project_metrics"]["metricType"] == "DURATION"
    assert configs_by_type["project_metrics"]["breakdown"] == {"field": "model"}

    # Every widget has a matching layout item and vice versa.
    widget_ids = {w["id"] for w in widgets}
    layout_ids = {item["i"] for item in config["sections"][0]["layout"]}
    assert widget_ids == layout_ids

    # The dashboard is discoverable via find.
    found = opik_client.find_dashboards(
        name=f"e2e-dashboard-{PROJECT_NAME}", max_results=10
    )
    assert any(found_dashboard.id == dash.id for found_dashboard in found)

    # Delete removes it.
    opik_client.delete_dashboard(dash.id)
    with pytest.raises(ApiError) as exc_info:
        opik_client.get_dashboard(dash.id)
    assert exc_info.value.status_code == 404


def test_update_and_remove_widget__persisted(
    opik_client: opik.Opik, created_dashboards: list
):
    dash = opik_client.create_dashboard(
        name=f"e2e-dashboard-mutate-{PROJECT_NAME}",
        type=dashboard.DashboardType.MULTI_PROJECT,
    )
    created_dashboards.append(dash.id)
    section_id = dash.sections[0].id

    widget_id = dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.TEXT_MARKDOWN,
            title="Notes",
            config=dashboard.TextMarkdownConfig(content="initial"),
        ),
    )

    dash.update_widget(widget_id, config={"content": "updated"})
    dash.rename(f"e2e-dashboard-renamed-{PROJECT_NAME}")

    fetched = opik_client.get_dashboard(dash.id)
    assert fetched.name == f"e2e-dashboard-renamed-{PROJECT_NAME}"
    widget = fetched.config["sections"][0]["widgets"][0]
    assert widget["config"]["content"] == "updated"

    fetched.remove_widget(widget_id)
    refetched = opik_client.get_dashboard(dash.id)
    assert refetched.config["sections"][0]["widgets"] == []
