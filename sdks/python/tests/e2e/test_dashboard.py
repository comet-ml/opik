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
from . import verifiers
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
    verifiers.verify_dashboard(
        opik_client,
        dashboard_id=dash.id,
        name=f"e2e-dashboard-{PROJECT_NAME}",
        type="multi_project",
        version=dashboard.DashboardState().version,
        section_count=1,
        expected_widget_configs={
            "project_stats_card": {"metric": "trace_count"},
            "project_metrics": {
                "metricType": "DURATION",
                "breakdown": {"field": "model"},
            },
        },
    )

    # The dashboard is discoverable via find.
    found = opik_client.get_dashboards(
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

    verifiers.verify_dashboard(
        opik_client,
        dashboard_id=dash.id,
        name=f"e2e-dashboard-renamed-{PROJECT_NAME}",
        type="multi_project",
        expected_widget_configs={"text_markdown": {"content": "updated"}},
    )

    dash.remove_widget(widget_id)
    verifiers.verify_dashboard(
        opik_client,
        dashboard_id=dash.id,
        section_count=1,
        expected_widget_configs={},
    )
    refetched = opik_client.get_dashboard(dash.id)
    assert refetched.sections[0].widgets == []


def test_experiments_dashboard_widgets__all_types(
    opik_client: opik.Opik, created_dashboards: list
):
    dash = opik_client.create_dashboard(
        name=f"e2e-dashboard-experiments-{PROJECT_NAME}",
        type=dashboard.DashboardType.EXPERIMENTS,
    )
    created_dashboards.append(dash.id)
    section_id = dash.sections[0].id

    dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.EXPERIMENTS_FEEDBACK_SCORES,
            title="Feedback scores",
            config=dashboard.ExperimentsFeedbackScoresConfig(
                chart_type=dashboard.ChartType.BAR,
                max_experiments_count=5,
            ),
        ),
    )
    dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.EXPERIMENT_LEADERBOARD,
            title="Leaderboard",
            config=dashboard.ExperimentLeaderboardConfig(enable_ranking=False),
        ),
    )

    verifiers.verify_dashboard(
        opik_client,
        dashboard_id=dash.id,
        type="experiments",
        section_count=1,
        expected_widget_configs={
            "experiments_feedback_scores": {"chartType": "bar"},
            "experiment_leaderboard": {"enableRanking": False},
        },
    )


def test_move_widget_position__persisted(
    opik_client: opik.Opik, project_id: str, created_dashboards: list
):
    dash = opik_client.create_dashboard(
        name=f"e2e-dashboard-move-{PROJECT_NAME}",
        type=dashboard.DashboardType.MULTI_PROJECT,
    )
    created_dashboards.append(dash.id)
    section_id = dash.sections[0].id

    # Two stats-card widgets; auto-layout places them at x=0 and x=1 (w=1 each).
    w1_id = dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.PROJECT_STATS_CARD,
            title="Widget A",
            config=dashboard.ProjectStatsCardConfig(
                project_id=project_id,
                metric=dashboard.StatsCardMetric.TRACE_COUNT,
            ),
        ),
    )
    dash.add_widget(
        section_id,
        dashboard.DashboardWidget(
            type=dashboard.WidgetType.PROJECT_STATS_CARD,
            title="Widget B",
            config=dashboard.ProjectStatsCardConfig(
                project_id=project_id,
                metric=dashboard.StatsCardMetric.ERROR_COUNT,
            ),
        ),
    )

    # Move w1 to the far right (x=5) via replace_sections.
    sections = dash.sections
    for item in sections[0].layout:
        if item.id == w1_id:
            item.x = 5
            item.y = 0
    dash.replace_sections(sections)

    verifiers.verify_dashboard(
        opik_client,
        dashboard_id=dash.id,
        section_count=1,
        expected_widget_positions={w1_id: {"x": 5, "y": 0}},
    )
