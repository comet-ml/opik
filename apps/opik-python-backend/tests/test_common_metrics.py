"""Tests for the common_metrics module."""

import pytest
from opik_backend import create_app
from opik_backend.common_metrics import (
    get_common_metrics_list,
    instantiate_metric,
    COMMON_METRICS_REGISTRY,
)


@pytest.fixture
def app():
    """Create application for testing."""
    app = create_app(test_config={"TESTING": True}, should_init_executor=False)
    return app


@pytest.fixture
def client(app):
    """Create test client."""
    return app.test_client()


class TestGetCommonMetricsList:
    """Tests for get_common_metrics_list function."""

    def test_returns_list_of_metrics(self, app):
        """Should return a non-empty list of metrics."""
        with app.app_context():
            metrics = get_common_metrics_list()
            assert isinstance(metrics, list)
            assert len(metrics) > 0

    def test_each_metric_has_required_fields(self, app):
        """Each metric should have id, name, description, init_parameters, and score_parameters."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                assert "id" in metric
                assert "name" in metric
                assert "description" in metric
                assert "init_parameters" in metric
                assert "score_parameters" in metric
                assert isinstance(metric["init_parameters"], list)
                assert isinstance(metric["score_parameters"], list)

    def test_contains_metric_has_expected_parameters(self, app):
        """Contains metric should have case_sensitive and reference init parameters."""
        with app.app_context():
            metrics = get_common_metrics_list()
            contains_metric = next((m for m in metrics if m["id"] == "contains"), None)
            assert contains_metric is not None
            
            init_param_names = [p["name"] for p in contains_metric["init_parameters"]]
            assert "case_sensitive" in init_param_names
            assert "reference" in init_param_names
            
            score_param_names = [p["name"] for p in contains_metric["score_parameters"]]
            assert "output" in score_param_names
            assert "reference" in score_param_names

    def test_excluded_params_not_in_init_parameters(self, app):
        """Internal parameters like 'name', 'track', 'project_name' should be excluded."""
        with app.app_context():
            metrics = get_common_metrics_list()
            for metric in metrics:
                init_param_names = [p["name"] for p in metric["init_parameters"]]
                assert "self" not in init_param_names
                assert "name" not in init_param_names
                assert "track" not in init_param_names
                assert "project_name" not in init_param_names


class TestInstantiateMetric:
    """Tests for instantiate_metric function."""

    def test_instantiate_contains_metric(self, app):
        """Should instantiate Contains metric with default config."""
        with app.app_context():
            metric = instantiate_metric("contains")
            assert metric is not None
            assert metric.__class__.__name__ == "Contains"

    def test_instantiate_contains_with_config(self, app):
        """Should instantiate Contains metric with custom config."""
        with app.app_context():
            metric = instantiate_metric("contains", {"case_sensitive": True, "reference": "test"})
            assert metric is not None
            assert metric._case_sensitive is True
            assert metric._default_reference == "test"

    def test_instantiate_unknown_metric_raises(self, app):
        """Should raise ValueError for unknown metric."""
        with app.app_context():
            with pytest.raises(ValueError, match="Unknown metric"):
                instantiate_metric("unknown_metric")

    def test_tracking_is_disabled(self, app):
        """Instantiated metrics should have tracking disabled."""
        with app.app_context():
            metric = instantiate_metric("contains")
            assert metric.track is False


class TestListCommonMetricsEndpoint:
    """Tests for the /common-metrics endpoint."""

    def test_list_metrics_returns_200(self, client):
        """GET /common-metrics should return 200."""
        response = client.get("/v1/private/evaluators/common-metrics")
        assert response.status_code == 200

    def test_list_metrics_returns_content(self, client):
        """GET /common-metrics should return a content array."""
        response = client.get("/v1/private/evaluators/common-metrics")
        data = response.get_json()
        assert "content" in data
        assert isinstance(data["content"], list)
        assert len(data["content"]) > 0


class TestExecuteCommonMetricEndpoint:
    """Tests for the /common-metrics/<metric_id>/score endpoint."""

    def test_execute_contains_metric(self, client):
        """POST /common-metrics/contains/score should execute the metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "hello"},
                "data": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        data = response.get_json()
        assert "scores" in data
        assert len(data["scores"]) == 1
        assert data["scores"][0]["value"] == 1.0

    def test_execute_contains_metric_no_match(self, client):
        """POST /common-metrics/contains/score should return 0 when no match."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "goodbye"},
                "data": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        data = response.get_json()
        assert data["scores"][0]["value"] == 0.0

    def test_execute_contains_case_sensitive(self, client):
        """POST /common-metrics/contains/score with case_sensitive=True."""
        # Case insensitive (default) - should match
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "HELLO"},
                "data": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        assert response.get_json()["scores"][0]["value"] == 1.0

        # Case sensitive - should not match
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"case_sensitive": True, "reference": "HELLO"},
                "data": {"output": "hello world"}
            }
        )
        assert response.status_code == 200
        assert response.get_json()["scores"][0]["value"] == 0.0

    def test_execute_unknown_metric_returns_404(self, client):
        """POST /common-metrics/unknown/score should return 404."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/unknown/score",
            json={"data": {"output": "test"}}
        )
        assert response.status_code == 404

    def test_execute_missing_data_returns_400(self, client):
        """POST /common-metrics/contains/score without data should return 400."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={"init_config": {"reference": "test"}}
        )
        assert response.status_code == 400

    def test_execute_equals_metric(self, client):
        """POST /common-metrics/equals/score should execute the metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/equals/score",
            json={
                "data": {"output": "hello", "reference": "hello"}
            }
        )
        assert response.status_code == 200
        data = response.get_json()
        assert data["scores"][0]["value"] == 1.0

    def test_execute_is_json_metric(self, client):
        """POST /common-metrics/is_json/score should execute the metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/is_json/score",
            json={
                "data": {"output": '{"key": "value"}'}
            }
        )
        assert response.status_code == 200
        data = response.get_json()
        assert data["scores"][0]["value"] == 1.0

        # Invalid JSON
        response = client.post(
            "/v1/private/evaluators/common-metrics/is_json/score",
            json={
                "data": {"output": "not json"}
            }
        )
        assert response.status_code == 200
        assert response.get_json()["scores"][0]["value"] == 0.0
