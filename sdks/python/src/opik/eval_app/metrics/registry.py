"""Registry for supported metrics."""

import logging
from typing import Dict, List, Optional, Type

from opik.evaluation.metrics import base_metric

from .descriptor import MetricDescriptor, MetricInfo

LOGGER = logging.getLogger(__name__)


class MetricsRegistry:
    """Registry of supported metrics."""

    def __init__(self) -> None:
        self._metrics: Dict[str, MetricDescriptor] = {}

    def register(
        self,
        metric_class: Type[base_metric.BaseMetric],
        description: Optional[str] = None,
    ) -> None:
        """Register a metric."""
        descriptor = MetricDescriptor(
            metric_class=metric_class,
            description=description,
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
    """Create the default metrics registry with supported metrics."""
    from opik.evaluation import metrics

    registry = MetricsRegistry()

    # Heuristic metrics
    registry.register(metrics.Equals)
    registry.register(metrics.Contains)
    registry.register(metrics.RegexMatch)
    registry.register(metrics.IsJson)
    registry.register(metrics.LevenshteinRatio)

    # LLM Judge metrics
    registry.register(metrics.AnswerRelevance)
    registry.register(metrics.Hallucination)
    registry.register(metrics.Moderation)
    registry.register(metrics.ContextPrecision)
    registry.register(metrics.ContextRecall)
    registry.register(metrics.Usefulness)

    LOGGER.debug("Created default registry with %d metrics", len(registry._metrics))
    return registry


_default_registry: Optional[MetricsRegistry] = None


def get_default_registry() -> MetricsRegistry:
    """Get the default metrics registry, creating it if necessary."""
    global _default_registry
    if _default_registry is None:
        _default_registry = create_default_registry()
    return _default_registry
