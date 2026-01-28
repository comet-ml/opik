"""
Tests for common metric execution through both ProcessExecutor and DockerExecutor.

This test suite verifies that the COMMON_METRIC payload type works correctly
in both execution environments.
"""

import pytest
from opik_backend.executor_docker import DockerExecutor
from opik_backend.executor_process import ProcessExecutor
from opik_backend.payload_types import PayloadType


@pytest.fixture(
    params=[
        pytest.param("process", id="ProcessExecutor"),
        pytest.param("docker", id="DockerExecutor")
    ]
)
def executor(request):
    """Parametrized fixture that provides both ProcessExecutor and DockerExecutor."""
    if request.param == "process":
        executor_instance = ProcessExecutor()
    else:  # docker
        executor_instance = DockerExecutor()
    
    if hasattr(executor_instance, 'start_services'):
        executor_instance.start_services()
    try:
        yield executor_instance
    finally:
        if hasattr(executor_instance, 'stop_services'):
            executor_instance.stop_services()
        executor_instance.cleanup()


@pytest.fixture
def process_executor():
    """Fixture for ProcessExecutor only."""
    executor_instance = ProcessExecutor()
    executor_instance.start_services()
    try:
        yield executor_instance
    finally:
        executor_instance.cleanup()


class TestCommonMetricExecutionBasics:
    """Basic happy path tests for common metric execution."""

    def test_execute_contains_metric_match(self, executor):
        """Should execute Contains metric and return 1.0 for a match."""
        data = {
            "metric_id": "contains",
            "init_config": {},
            "scoring_kwargs": {"output": "hello world", "reference": "hello"}
        }
        
        result = executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        
        assert "scores" in result
        assert len(result["scores"]) == 1
        assert result["scores"][0]["value"] == 1.0
        assert result["scores"][0]["name"] == "contains_metric"


    def test_unknown_metric_returns_error(self, executor):
        """Should return error for unknown metric ID."""
        data = {
            "metric_id": "unknown_metric_xyz",
            "init_config": {},
            "scoring_kwargs": {"output": "test"}
        }
        
        result = executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert "error" in result
        assert "Unknown metric" in result["error"]


class TestCommonMetricExecutionDetails:
    """Detailed tests for ProcessExecutor only (faster execution)."""

    def test_execute_contains_case_sensitive(self, process_executor):
        """Should respect case_sensitive parameter in init_config."""
        # Case insensitive (default) - should match
        data = {
            "metric_id": "contains",
            "init_config": {"reference": "HELLO"},
            "scoring_kwargs": {"output": "hello world"}
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert result["scores"][0]["value"] == 1.0
        
        # Case sensitive - should not match
        data["init_config"]["case_sensitive"] = True
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert result["scores"][0]["value"] == 0.0

    def test_execute_is_json_metric(self, process_executor):
        """Should execute IsJson metric correctly."""
        # Valid JSON
        data = {
            "metric_id": "is_json",
            "init_config": {},
            "scoring_kwargs": {"output": '{"key": "value"}'}
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert result["scores"][0]["value"] == 1.0
        
        # Invalid JSON
        data["scoring_kwargs"]["output"] = "not json"
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert result["scores"][0]["value"] == 0.0

    def test_execute_regex_match_metric(self, process_executor):
        """Should execute RegexMatch metric correctly."""
        data = {
            "metric_id": "regex_match",
            "init_config": {"regex": r"\d{3}-\d{4}"},
            "scoring_kwargs": {"output": "Call me at 123-4567"}
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert result["scores"][0]["value"] == 1.0

    def test_missing_metric_id(self, process_executor):
        """Should return error when metric_id is missing."""
        data = {
            "init_config": {},
            "scoring_kwargs": {"output": "test"}
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert "error" in result
        # Error message should indicate unknown or missing metric
        assert "metric" in result["error"].lower()

    def test_missing_scoring_kwargs(self, process_executor):
        """Should return error when scoring_kwargs is missing."""
        data = {
            "metric_id": "contains",
            "init_config": {"reference": "test"}
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert "error" in result

    def test_invalid_scoring_kwargs_type(self, process_executor):
        """Should return error when scoring_kwargs is not a dict."""
        data = {
            "metric_id": "contains",
            "init_config": {},
            "scoring_kwargs": "not a dict"
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert "error" in result
        # Error should indicate type mismatch or argument error
        assert "error" in result["error"].lower() or "argument" in result["error"].lower()

    def test_missing_required_score_parameter(self, process_executor):
        """Should return error when required score parameter is missing."""
        data = {
            "metric_id": "contains",
            "init_config": {"reference": "test"},
            "scoring_kwargs": {}  # Missing 'output' parameter
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        assert "error" in result

    def test_tracking_disabled_by_default(self, process_executor):
        """Should disable tracking even if not specified in init_config."""
        data = {
            "metric_id": "contains",
            "init_config": {"reference": "test"},
            "scoring_kwargs": {"output": "test"}
        }
        
        result = process_executor.run_scoring("", data, PayloadType.COMMON_METRIC.value)
        
        # Should succeed without trying to track to Opik
        assert "scores" in result
        assert result["scores"][0]["value"] == 1.0


class TestCommonMetricEndpointIntegration:
    """Integration tests with the Flask endpoint (ProcessExecutor only)."""

    @pytest.fixture
    def app(self, process_executor):
        """Create Flask app with ProcessExecutor."""
        from opik_backend import create_app
        app = create_app(should_init_executor=False)
        app.executor = process_executor
        return app

    @pytest.fixture
    def client(self, app):
        """Create test client for the app."""
        return app.test_client()

    def test_endpoint_executes_common_metric(self, client):
        """Should execute common metric through endpoint."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "hello"},
                "scoring_kwargs": {"output": "hello world"}
            }
        )
        
        assert response.status_code == 200
        result = response.get_json()
        assert "scores" in result
        assert result["scores"][0]["value"] == 1.0

    def test_endpoint_handles_unknown_metric(self, client):
        """Should return 404 for unknown metric."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/unknown_metric/score",
            json={
                "scoring_kwargs": {"output": "test"}
            }
        )
        
        assert response.status_code == 404

    def test_endpoint_validates_scoring_kwargs(self, client):
        """Should return 400 when scoring_kwargs is missing."""
        response = client.post(
            "/v1/private/evaluators/common-metrics/contains/score",
            json={
                "init_config": {"reference": "test"}
            }
        )
        
        assert response.status_code == 400
