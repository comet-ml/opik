"""Registry for available metrics."""

import logging
from typing import Any, Dict, List, Optional, Type

from opik.evaluation import metrics
from opik.evaluation.metrics import base_metric

from .descriptor import MetricDescriptor, MetricInfo

LOGGER = logging.getLogger(__name__)

_default_registry: Optional["MetricsRegistry"] = None


class MetricsRegistry:
    """Registry of available metrics."""

    def __init__(self) -> None:
        self._metrics: Dict[str, MetricDescriptor] = {}

    def register(
        self,
        metric_class: Type[base_metric.BaseMetric],
        init_defaults: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Register a metric class.

        Args:
            metric_class: The metric class to register.
            init_defaults: Optional custom default values for __init__ parameters.
        """
        descriptor = MetricDescriptor(
            metric_class=metric_class,
            init_defaults=init_defaults,
        )
        self._metrics[metric_class.__name__] = descriptor
        LOGGER.debug("Registered metric: %s", metric_class.__name__)

    def get(self, name: str) -> Optional[MetricDescriptor]:
        """Get a metric descriptor by name."""
        return self._metrics.get(name)

    def list_all(self) -> List[MetricInfo]:
        """List all registered metrics."""
        return [descriptor.to_metric_info() for descriptor in self._metrics.values()]

    def get_metric_class(self, name: str) -> Optional[Type[base_metric.BaseMetric]]:
        """Get a metric class by name."""
        descriptor = self._metrics.get(name)
        return descriptor.metric_class if descriptor else None


def create_default_registry() -> MetricsRegistry:
    """Create a registry with default metrics."""
    registry = MetricsRegistry()

    # Heuristic metrics
    registry.register(metrics.Equals)
    registry.register(metrics.Contains)
    registry.register(metrics.RegexMatch)
    registry.register(metrics.IsJson)
    registry.register(metrics.LevenshteinRatio)

    # LLM Judge metrics (default to gpt-4o-mini)
    llm_defaults = {"model": "gpt-4o-mini"}
    registry.register(metrics.AnswerRelevance, init_defaults=llm_defaults)
    registry.register(metrics.ContextPrecision, init_defaults=llm_defaults)
    registry.register(metrics.ContextRecall, init_defaults=llm_defaults)
    registry.register(metrics.Hallucination, init_defaults=llm_defaults)
    registry.register(metrics.Moderation, init_defaults=llm_defaults)
    registry.register(metrics.GEval, init_defaults=llm_defaults)

    return registry


def get_default_registry() -> MetricsRegistry:
    """Get the default metrics registry singleton."""
    global _default_registry
    if _default_registry is None:
        _default_registry = create_default_registry()
    return _default_registry
