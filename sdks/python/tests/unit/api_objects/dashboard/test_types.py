import pytest

from opik import exceptions
from opik.api_objects.dashboard import types
from ....testlib import assert_equal


def test_widget_serialization__uses_camelcase_and_enum_values():
    widget = types.DashboardWidget(
        type=types.WidgetType.PROJECT_STATS_CARD,
        title="Traces",
        config=types.ProjectStatsCardConfig(
            project_id="p1", metric=types.StatsCardMetric.TRACE_COUNT
        ),
    )
    result = widget.to_jsonable()

    assert result["type"] == "project_stats_card"
    assert result["title"] == "Traces"
    assert "id" in result
    assert_equal(
        {
            "projectId": "p1",
            "source": "traces",
            "metric": "trace_count",
        },
        result["config"],
    )


def test_project_metrics_config__defaults_and_breakdown_camelcase():
    config = types.ProjectMetricsConfig(
        metric_type=types.ProjectMetricType.DURATION,
        breakdown=types.BreakdownConfig(
            field=types.BreakdownField.METADATA, metadata_key="provider"
        ),
    )
    result = config.to_jsonable()

    assert result["metricType"] == "DURATION"
    assert result["chartType"] == "line"
    assert_equal({"field": "metadata", "metadataKey": "provider"}, result["breakdown"])


def test_breakdown_config__metadata_field_requires_metadata_key():
    with pytest.raises(exceptions.DashboardValidationError):
        types.BreakdownConfig(field=types.BreakdownField.METADATA)


def test_breakdown_config__non_metadata_field_does_not_require_key():
    config = types.BreakdownConfig(field=types.BreakdownField.TAGS)
    assert config.to_jsonable() == {"field": "tags"}


def test_leaderboard_config__enable_ranking_requires_ranking_metric():
    with pytest.raises(exceptions.DashboardValidationError):
        types.ExperimentLeaderboardConfig(enable_ranking=True)


def test_leaderboard_config__enable_ranking_with_metric_ok():
    config = types.ExperimentLeaderboardConfig(
        enable_ranking=True, ranking_metric="pass_rate"
    )
    assert config.to_jsonable()["rankingMetric"] == "pass_rate"


@pytest.mark.parametrize("value", [0, 101, 500, "0", "200"])
def test_leaderboard_config__max_rows_out_of_range_raises(value):
    with pytest.raises(exceptions.DashboardValidationError):
        types.ExperimentLeaderboardConfig(max_rows=value)


@pytest.mark.parametrize("value", [1, 100, "10", 50])
def test_leaderboard_config__max_rows_in_range_ok(value):
    config = types.ExperimentLeaderboardConfig(max_rows=value)
    assert config.to_jsonable()["maxRows"] == value


@pytest.mark.parametrize("value", [0, 101])
def test_feedback_scores_config__max_experiments_out_of_range_raises(value):
    with pytest.raises(exceptions.DashboardValidationError):
        types.ExperimentsFeedbackScoresConfig(max_experiments_count=value)


def test_dashboard_state__defaults_to_known_version():
    state = types.DashboardState()
    result = state.to_jsonable()
    assert result["version"] == types.DASHBOARD_VERSION
    assert "lastModified" in result
    assert result["sections"] == []


def test_widget_accepts_raw_dict_config():
    widget = types.DashboardWidget(type="future_widget", config={"someCamelField": 1})
    assert widget.to_jsonable()["config"] == {"someCamelField": 1}


def test_models_preserve_unknown_fields_on_parse():
    state = types.DashboardState.model_validate(
        {
            "version": 4,
            "lastModified": 1,
            "sections": [],
            "mysteryField": "keep",
        }
    )
    assert state.to_jsonable()["mysteryField"] == "keep"
