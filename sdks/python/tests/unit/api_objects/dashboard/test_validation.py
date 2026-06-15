from unittest import mock

import pytest

from opik import exceptions
from opik.api_objects.dashboard import validation


def _section(section_id, widgets, layout_ids):
    return {
        "id": section_id,
        "title": "t",
        "widgets": [{"id": w, "type": "text_markdown", "config": {}} for w in widgets],
        "layout": [{"i": i, "x": 0, "y": 0, "w": 1, "h": 1} for i in layout_ids],
    }


def test_validate_structure__valid_passes():
    state = {"version": 4, "sections": [_section("s1", ["w1"], ["w1"])]}
    validation.validate_structure(state)


def test_validate_structure__widget_without_layout_raises():
    state = {"version": 4, "sections": [_section("s1", ["w1"], [])]}
    with pytest.raises(exceptions.DashboardValidationError, match="without a layout"):
        validation.validate_structure(state)


def test_validate_structure__orphan_layout_item_raises():
    state = {"version": 4, "sections": [_section("s1", [], ["ghost"])]}
    with pytest.raises(exceptions.DashboardValidationError, match="missing widgets"):
        validation.validate_structure(state)


def test_validate_structure__duplicate_widget_ids_across_sections_raises():
    state = {
        "version": 4,
        "sections": [
            _section("s1", ["dup"], ["dup"]),
            _section("s2", ["dup"], ["dup"]),
        ],
    }
    with pytest.raises(exceptions.DashboardValidationError, match="Duplicate widget"):
        validation.validate_structure(state)


def test_validate_structure__duplicate_section_ids_raises():
    state = {
        "version": 4,
        "sections": [_section("s1", ["w1"], ["w1"]), _section("s1", ["w2"], ["w2"])],
    }
    with pytest.raises(exceptions.DashboardValidationError, match="Duplicate section"):
        validation.validate_structure(state)


def test_validate_widget_for_dashboard__incompatible_type_raises():
    widget = {"type": "experiment_leaderboard", "config": {}}
    with pytest.raises(exceptions.DashboardValidationError, match="not supported"):
        validation.validate_widget_for_dashboard(widget, "multi_project")


def test_validate_widget_for_dashboard__compatible_type_ok():
    widget = {"type": "project_metrics", "config": {"metricType": "TRACE_COUNT"}}
    validation.validate_widget_for_dashboard(widget, "multi_project")


def test_validate_widget_for_dashboard__no_dashboard_type_skips_compatibility():
    widget = {"type": "experiment_leaderboard", "config": {}}
    validation.validate_widget_for_dashboard(widget, None)


def test_validate_widget_for_dashboard__unknown_metric_warns_not_raises():
    widget = {"type": "project_metrics", "config": {"metricType": "not_a_metric"}}
    with mock.patch.object(validation.LOGGER, "warning") as mock_warning:
        validation.validate_widget_for_dashboard(widget, "multi_project")
    mock_warning.assert_called_once()
    assert "Unknown project_metrics metricType" in mock_warning.call_args[0][0]


def test_validate_widget_for_dashboard__dynamic_feedback_metric_no_warning():
    widget = {
        "type": "project_stats_card",
        "config": {"metric": "feedback_scores.helpfulness"},
    }
    with mock.patch.object(validation.LOGGER, "warning") as mock_warning:
        validation.validate_widget_for_dashboard(widget, "multi_project")
    mock_warning.assert_not_called()


def test_validate_writable_version__known_version_ok():
    validation.validate_writable_version(4)
    validation.validate_writable_version(None)


def test_validate_writable_version__unknown_version_raises():
    with pytest.raises(exceptions.DashboardValidationError, match="schema version 5"):
        validation.validate_writable_version(5)
