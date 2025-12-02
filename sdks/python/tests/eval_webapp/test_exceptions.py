"""Tests for the eval app exceptions."""

from opik.exceptions import OpikException
from opik.eval_app import exceptions


class TestUnknownMetricError:
    def test_init__stores_metric_name(self):
        """Test that UnknownMetricError stores the metric name."""
        error = exceptions.UnknownMetricError("TestMetric")

        assert error.metric_name == "TestMetric"
        assert "TestMetric" in str(error)
        assert "/api/v1/evaluation/metrics" in str(error)


class TestMetricInstantiationError:
    def test_init__stores_details(self):
        """Test that MetricInstantiationError stores metric name and reason."""
        error = exceptions.MetricInstantiationError("TestMetric", "invalid argument")

        assert error.metric_name == "TestMetric"
        assert error.reason == "invalid argument"
        assert "TestMetric" in str(error)
        assert "invalid argument" in str(error)


class TestTraceNotFoundError:
    def test_init__stores_trace_id(self):
        """Test that TraceNotFoundError stores the trace ID."""
        error = exceptions.TraceNotFoundError("trace-123")

        assert error.trace_id == "trace-123"
        assert "trace-123" in str(error)


class TestInvalidFieldMappingError:
    def test_init__stores_details(self):
        """Test that InvalidFieldMappingError stores field path and reason."""
        error = exceptions.InvalidFieldMappingError("invalid.path", "field not found")

        assert error.field_path == "invalid.path"
        assert error.reason == "field not found"
        assert "invalid.path" in str(error)
        assert "field not found" in str(error)


class TestEvaluationError:
    def test_init__stores_reason(self):
        """Test that EvaluationError stores the reason."""
        error = exceptions.EvaluationError("something went wrong")

        assert error.reason == "something went wrong"
        assert "something went wrong" in str(error)


class TestExceptionHierarchy:
    def test_eval_app_error__inherits_from_opik_exception(self):
        """Test that EvalAppError inherits from OpikException."""
        assert issubclass(exceptions.EvalAppError, OpikException)

    def test_all_exceptions__inherit_from_eval_app_error(self):
        """Test that all custom exceptions inherit from EvalAppError."""
        assert issubclass(exceptions.UnknownMetricError, exceptions.EvalAppError)
        assert issubclass(exceptions.MetricInstantiationError, exceptions.EvalAppError)
        assert issubclass(exceptions.TraceNotFoundError, exceptions.EvalAppError)
        assert issubclass(exceptions.InvalidFieldMappingError, exceptions.EvalAppError)
        assert issubclass(exceptions.EvaluationError, exceptions.EvalAppError)
