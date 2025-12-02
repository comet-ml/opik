"""Tests for the eval app FastAPI application."""

import pytest
from unittest import mock

pytest.importorskip("fastapi")

from fastapi.testclient import TestClient

from opik.eval_app import create_app


@pytest.fixture
def client():
    """Create a test client for the eval app."""
    app = create_app()
    return TestClient(app)


class TestHealthcheckEndpoint:
    def test_healthcheck__happyflow(self, client):
        """Test healthcheck endpoint returns ok status."""
        response = client.get("/healthcheck")
        assert response.status_code == 200
        assert response.json() == {"status": "ok"}


class TestMetricsEndpoint:
    def test_list_metrics__happyflow(self, client):
        """Test metrics endpoint returns list of supported metrics."""
        response = client.get("/api/v1/evaluation/metrics")
        assert response.status_code == 200

        data = response.json()
        assert "metrics" in data
        assert len(data["metrics"]) > 0

    def test_list_metrics__response_contains_expected_fields(self, client):
        """Test that each metric descriptor contains expected fields."""
        response = client.get("/api/v1/evaluation/metrics")
        data = response.json()

        for metric in data["metrics"]:
            assert "name" in metric
            assert "description" in metric
            assert "init_params" in metric
            assert "score_params" in metric

    def test_list_metrics__contains_heuristic_metrics(self, client):
        """Test that heuristic metrics are included."""
        response = client.get("/api/v1/evaluation/metrics")
        data = response.json()

        metric_names = [m["name"] for m in data["metrics"]]
        assert "Equals" in metric_names
        assert "Contains" in metric_names

    def test_list_metrics__contains_llm_judge_metrics(self, client):
        """Test that LLM judge metrics are included."""
        response = client.get("/api/v1/evaluation/metrics")
        data = response.json()

        metric_names = [m["name"] for m in data["metrics"]]
        assert "AnswerRelevance" in metric_names
        assert "Hallucination" in metric_names

    def test_list_metrics__init_params_have_required_fields(self, client):
        """Test that init params have the required structure."""
        response = client.get("/api/v1/evaluation/metrics")
        data = response.json()

        equals_metric = next(m for m in data["metrics"] if m["name"] == "Equals")

        for param in equals_metric["init_params"]:
            assert "name" in param
            assert "required" in param

    def test_list_metrics__score_params_have_required_fields(self, client):
        """Test that score params have the required structure."""
        response = client.get("/api/v1/evaluation/metrics")
        data = response.json()

        equals_metric = next(m for m in data["metrics"] if m["name"] == "Equals")

        for param in equals_metric["score_params"]:
            assert "name" in param
            assert "required" in param


class TestEvaluateTraceEndpoint:
    def test_evaluate_trace__unknown_metric__returns_400(self, client):
        """Test that unknown metric returns 400 error."""
        request_data = {
            "trace_id": "trace-123",
            "metrics": [{"name": "UnknownMetric"}],
            "field_mapping": {"mapping": {"output": "output"}},
        }

        response = client.post("/api/v1/evaluation/traces", json=request_data)
        assert response.status_code == 400
        assert "Unknown metric" in response.json()["error"]

    def test_evaluate_trace__empty_metrics__returns_422(self, client):
        """Test that empty metrics list returns 422 validation error."""
        request_data = {
            "trace_id": "trace-123",
            "metrics": [],
            "field_mapping": {"mapping": {"output": "output"}},
        }

        response = client.post("/api/v1/evaluation/traces", json=request_data)
        assert response.status_code == 422

    def test_evaluate_trace__missing_trace_id__returns_422(self, client):
        """Test that missing trace_id returns 422 validation error."""
        request_data = {
            "metrics": [{"name": "Equals"}],
            "field_mapping": {"mapping": {"output": "output"}},
        }

        response = client.post("/api/v1/evaluation/traces", json=request_data)
        assert response.status_code == 422

    def test_evaluate_trace__missing_field_mapping__returns_422(self, client):
        """Test that missing field_mapping returns 422 validation error."""
        request_data = {
            "trace_id": "trace-123",
            "metrics": [{"name": "Equals"}],
        }

        response = client.post("/api/v1/evaluation/traces", json=request_data)
        assert response.status_code == 422
