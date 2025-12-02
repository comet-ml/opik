"""Tests for the eval app service layer."""

import pytest
from unittest import mock

from opik.eval_app import exceptions
from opik.eval_app import schemas
from opik.eval_app.metrics import MetricsRegistry, create_default_registry
from opik.eval_app.services import EvalService, create_service


class TestEvalServiceListMetrics:
    def test_list_metrics__happyflow(self):
        """Test that list_metrics returns registered metrics."""
        registry = create_default_registry()
        eval_service = EvalService(registry=registry)

        result = eval_service.list_metrics()

        assert len(result) > 0
        assert any(m["name"] == "Equals" for m in result)

    def test_list_metrics__custom_registry__returns_only_registered(self):
        """Test with a custom registry."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals, "heuristic")
        eval_service = EvalService(registry=registry)

        result = eval_service.list_metrics()

        assert len(result) == 1
        assert result[0]["name"] == "Equals"


class TestEvalServiceInstantiateMetrics:
    def test_instantiate_metrics__valid_metric__returns_instance(self):
        """Test instantiating a valid metric."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals, "heuristic")
        eval_service = EvalService(registry=registry)

        configs = [schemas.MetricConfig(name="Equals")]
        result = eval_service._instantiate_metrics(configs)

        assert len(result) == 1
        assert isinstance(result[0], Equals)

    def test_instantiate_metrics__with_init_args__applies_args(self):
        """Test that init_args are applied to the metric."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals, "heuristic")
        eval_service = EvalService(registry=registry)

        configs = [
            schemas.MetricConfig(name="Equals", init_args={"case_sensitive": True})
        ]
        result = eval_service._instantiate_metrics(configs)

        assert result[0]._case_sensitive is True

    def test_instantiate_metrics__unknown_metric__raises_unknown_metric_error(self):
        """Test that unknown metric raises UnknownMetricError."""
        registry = MetricsRegistry()
        eval_service = EvalService(registry=registry)

        configs = [schemas.MetricConfig(name="UnknownMetric")]

        with pytest.raises(exceptions.UnknownMetricError) as exc_info:
            eval_service._instantiate_metrics(configs)

        assert exc_info.value.metric_name == "UnknownMetric"

    def test_instantiate_metrics__invalid_init_args__raises_metric_instantiation_error(
        self,
    ):
        """Test that invalid init_args raises MetricInstantiationError."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals, "heuristic")
        eval_service = EvalService(registry=registry)

        configs = [schemas.MetricConfig(name="Equals", init_args={"invalid_arg": True})]

        with pytest.raises(exceptions.MetricInstantiationError) as exc_info:
            eval_service._instantiate_metrics(configs)

        assert exc_info.value.metric_name == "Equals"


class TestEvalServiceExtractMetricInputs:
    def test_extract_metric_inputs__simple_fields__extracts_values(self):
        """Test extracting simple trace fields."""
        registry = MetricsRegistry()
        eval_service = EvalService(registry=registry)

        # Create a mock trace
        mock_trace = mock.MagicMock()
        mock_trace.input = {"question": "What is 2+2?"}
        mock_trace.output = {"answer": "4"}
        mock_trace.metadata = {"context": "math"}
        mock_trace.name = "test-trace"
        mock_trace.tags = ["test"]

        field_mapping = schemas.TraceFieldMapping(
            mapping={"input": "input", "output": "output"}
        )

        result = eval_service._extract_metric_inputs(mock_trace, field_mapping)

        assert result["input"] == {"question": "What is 2+2?"}
        assert result["output"] == {"answer": "4"}

    def test_extract_metric_inputs__nested_fields__extracts_values(self):
        """Test extracting nested trace fields."""
        registry = MetricsRegistry()
        eval_service = EvalService(registry=registry)

        mock_trace = mock.MagicMock()
        mock_trace.metadata = {"context": "some context", "model": "gpt-4"}

        field_mapping = schemas.TraceFieldMapping(
            mapping={"context": "metadata.context"}
        )

        result = eval_service._extract_metric_inputs(mock_trace, field_mapping)

        assert result["context"] == "some context"

    def test_extract_metric_inputs__invalid_field__raises_error(self):
        """Test that invalid field path raises error."""
        registry = MetricsRegistry()
        eval_service = EvalService(registry=registry)

        mock_trace = mock.MagicMock()

        field_mapping = schemas.TraceFieldMapping(mapping={"value": "invalid_field"})

        with pytest.raises(exceptions.InvalidFieldMappingError) as exc_info:
            eval_service._extract_metric_inputs(mock_trace, field_mapping)

        assert "invalid_field" in exc_info.value.field_path


class TestCreateService:
    def test_create_service__no_registry__uses_default_registry(self):
        """Test that create_service returns a configured service."""
        eval_service = create_service()

        assert isinstance(eval_service, EvalService)
        metrics = eval_service.list_metrics()
        assert len(metrics) > 0

    def test_create_service__with_registry__uses_provided_registry(self):
        """Test that create_service uses provided registry."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals, "heuristic")

        eval_service = create_service(registry=registry)

        metrics = eval_service.list_metrics()
        assert len(metrics) == 1
        assert metrics[0]["name"] == "Equals"
