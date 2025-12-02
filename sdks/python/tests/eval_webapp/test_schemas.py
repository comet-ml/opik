"""Tests for the eval app Pydantic schemas."""

import pytest

from opik.eval_app import schemas


class TestMetricConfig:
    def test_init__minimal_fields__uses_defaults(self):
        """Test MetricConfig with minimal required fields."""
        config = schemas.MetricConfig(name="Equals")

        assert config.name == "Equals"
        assert config.init_args == {}

    def test_init__with_args__stores_args(self):
        """Test MetricConfig with init args."""
        config = schemas.MetricConfig(
            name="Equals",
            init_args={"case_sensitive": True},
        )

        assert config.init_args == {"case_sensitive": True}


class TestTraceFieldMapping:
    def test_init__simple_mapping__stores_mapping(self):
        """Test TraceFieldMapping with simple mapping."""
        mapping = schemas.TraceFieldMapping(
            mapping={"input": "input", "output": "output"}
        )

        assert mapping.mapping["input"] == "input"
        assert mapping.mapping["output"] == "output"

    def test_init__nested_mapping__stores_mapping(self):
        """Test TraceFieldMapping with nested field paths."""
        mapping = schemas.TraceFieldMapping(
            mapping={"context": "metadata.context", "reference": "input.expected"}
        )

        assert mapping.mapping["context"] == "metadata.context"
        assert mapping.mapping["reference"] == "input.expected"


class TestEvaluationRequest:
    def test_init__minimal_fields__uses_defaults(self):
        """Test EvaluationRequest with minimal required fields."""
        request = schemas.EvaluationRequest(
            trace_id="trace-123",
            metrics=[schemas.MetricConfig(name="Equals")],
            field_mapping=schemas.TraceFieldMapping(
                mapping={"output": "output", "reference": "input"}
            ),
        )

        assert request.trace_id == "trace-123"
        assert len(request.metrics) == 1
        assert request.project_name is None

    def test_init__all_fields__stores_all(self):
        """Test EvaluationRequest with all fields provided."""
        request = schemas.EvaluationRequest(
            trace_id="trace-456",
            metrics=[schemas.MetricConfig(name="Equals")],
            field_mapping=schemas.TraceFieldMapping(
                mapping={"output": "output"}
            ),
            project_name="test_project",
        )

        assert request.trace_id == "trace-456"
        assert request.project_name == "test_project"


class TestEvaluationAcceptedResponse:
    def test_init__happyflow(self):
        """Test EvaluationAcceptedResponse."""
        response = schemas.EvaluationAcceptedResponse(
            trace_id="trace-123",
            metrics_count=3,
        )

        assert response.trace_id == "trace-123"
        assert response.metrics_count == 3
        assert "accepted" in response.message.lower()


class TestMetricsListResponse:
    def test_init__happyflow(self):
        """Test MetricsListResponse stores metrics list."""
        response = schemas.MetricsListResponse(
            metrics=[
                schemas.MetricDescriptorResponse(
                    name="Equals",
                    description="Test description",
                    init_params=[
                        schemas.MetricParamDescriptor(
                            name="case_sensitive",
                            required=False,
                            type="bool",
                            default=False,
                        )
                    ],
                    score_params=[
                        schemas.MetricParamDescriptor(
                            name="output",
                            required=True,
                            type="str",
                        )
                    ],
                )
            ]
        )

        assert len(response.metrics) == 1
        assert response.metrics[0].name == "Equals"
        assert len(response.metrics[0].init_params) == 1
        assert len(response.metrics[0].score_params) == 1
