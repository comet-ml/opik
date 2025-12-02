"""Tests for the metrics registry module."""

import pytest

from opik.eval_app.metrics import (
    MetricDescriptor,
    MetricInfo,
    MetricsRegistry,
    ParamInfo,
    create_default_registry,
    get_default_registry,
)
from opik.eval_app.metrics.param_extractor import (
    _format_annotation,
    _serialize_default,
)


class TestMetricDescriptor:
    def test_to_metric_info__equals_metric__returns_correct_structure(self):
        """Test that MetricDescriptor correctly extracts Equals metric info."""
        from opik.evaluation.metrics import Equals

        descriptor = MetricDescriptor(metric_class=Equals)

        result = descriptor.to_metric_info()

        assert isinstance(result, MetricInfo)
        assert result.name == "Equals"
        assert len(result.description) > 0

        param_names = [p.name for p in result.init_params]
        assert "case_sensitive" in param_names
        assert "name" in param_names
        assert "track" in param_names

        score_param_names = [p.name for p in result.score_params]
        assert "output" in score_param_names
        assert "reference" in score_param_names

    def test_to_metric_info__custom_description__uses_provided_description(self):
        """Test that custom description overrides extracted docstring."""
        from opik.evaluation.metrics import Equals

        custom_desc = "Custom description for testing"
        descriptor = MetricDescriptor(
            metric_class=Equals,
            description=custom_desc,
        )

        result = descriptor.to_metric_info()
        assert result.description == custom_desc

    def test_to_metric_info__param_info__has_required_fields(self):
        """Test that ParamInfo has proper fields."""
        from opik.evaluation.metrics import Equals

        descriptor = MetricDescriptor(metric_class=Equals)
        result = descriptor.to_metric_info()

        for param in result.init_params:
            assert isinstance(param, ParamInfo)
            assert isinstance(param.name, str)
            assert isinstance(param.required, bool)


class TestMetricsRegistry:
    def test_register_and_get__happyflow(self):
        """Test registering and retrieving a metric."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals)

        descriptor = registry.get("Equals")
        assert descriptor is not None
        assert descriptor.metric_class == Equals

    def test_get__unknown_metric__returns_none(self):
        """Test that getting an unregistered metric returns None."""
        registry = MetricsRegistry()
        assert registry.get("UnknownMetric") is None

    def test_list_all__multiple_metrics__returns_metric_info_list(self):
        """Test listing all registered metrics returns MetricInfo objects."""
        from opik.evaluation.metrics import Contains, Equals

        registry = MetricsRegistry()
        registry.register(Equals)
        registry.register(Contains)

        metrics_list = registry.list_all()

        assert len(metrics_list) == 2
        assert all(isinstance(m, MetricInfo) for m in metrics_list)
        names = [m.name for m in metrics_list]
        assert "Equals" in names
        assert "Contains" in names

    def test_get_metric_class__happyflow(self):
        """Test getting the metric class by name."""
        from opik.evaluation.metrics import Equals

        registry = MetricsRegistry()
        registry.register(Equals)

        metric_class = registry.get_metric_class("Equals")
        assert metric_class == Equals

    def test_get_metric_class__unknown_metric__returns_none(self):
        """Test that getting an unknown metric class returns None."""
        registry = MetricsRegistry()
        assert registry.get_metric_class("Unknown") is None


class TestDefaultRegistry:
    def test_get_default_registry__happyflow(self):
        """Test that the default registry has pre-registered metrics."""
        registry = get_default_registry()

        metrics_list = registry.list_all()
        assert len(metrics_list) > 0

        names = [m.name for m in metrics_list]
        assert "Equals" in names
        assert "Contains" in names
        assert "AnswerRelevance" in names
        assert "Hallucination" in names

    def test_get_default_registry__at_least_ten_metrics_registered(self):
        """Test that at least ten metrics are registered."""
        registry = get_default_registry()
        metrics_list = registry.list_all()

        assert len(metrics_list) >= 10


class TestFormatAnnotation:
    def test_format_annotation__simple_type__returns_name(self):
        """Test formatting a simple type annotation."""
        assert _format_annotation(str) == "str"
        assert _format_annotation(int) == "int"
        assert _format_annotation(bool) == "bool"

    def test_format_annotation__optional_type__formats_correctly(self):
        """Test formatting Optional type annotation."""
        from typing import Optional

        result = _format_annotation(Optional[str])
        assert "Optional" in result or "str" in result


class TestSerializeDefault:
    def test_serialize_default__none__returns_none(self):
        """Test serializing None default."""
        assert _serialize_default(None) is None

    def test_serialize_default__primitives__returns_values(self):
        """Test serializing primitive defaults."""
        assert _serialize_default("test") == "test"
        assert _serialize_default(42) == 42
        assert _serialize_default(3.14) == 3.14
        assert _serialize_default(True) is True

    def test_serialize_default__list__returns_serialized_list(self):
        """Test serializing list defaults."""
        result = _serialize_default([1, 2, 3])
        assert result == [1, 2, 3]

    def test_serialize_default__dict__returns_serialized_dict(self):
        """Test serializing dict defaults."""
        result = _serialize_default({"a": 1, "b": 2})
        assert result == {"a": 1, "b": 2}

    def test_serialize_default__complex_object__returns_string_repr(self):
        """Test serializing complex objects returns string representation."""

        class CustomClass:
            pass

        result = _serialize_default(CustomClass())
        assert isinstance(result, str)
