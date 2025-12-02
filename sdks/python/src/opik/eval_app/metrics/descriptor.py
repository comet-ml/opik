"""Metric descriptor for introspecting metric classes."""

import dataclasses
import logging
from typing import List, Optional, Type

from opik.evaluation.metrics import base_metric

from .param_extractor import ParamInfo, extract_params

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class MetricInfo:
    """Complete information about a metric."""

    name: str
    description: str
    init_params: List[ParamInfo]
    score_params: List[ParamInfo]


class MetricDescriptor:
    """Descriptor for a metric that can be used in the eval app."""

    def __init__(
        self,
        metric_class: Type[base_metric.BaseMetric],
        description: Optional[str] = None,
    ) -> None:
        self._metric_class = metric_class
        self._description = description or self._extract_class_docstring()

    @property
    def metric_class(self) -> Type[base_metric.BaseMetric]:
        return self._metric_class

    def _extract_class_docstring(self) -> str:
        """Extract the class docstring."""
        doc = self._metric_class.__doc__
        if doc:
            return doc.strip()
        return ""

    def _get_init_params(self) -> List[ParamInfo]:
        """Get parameters for __init__ method."""
        return extract_params(self._metric_class.__init__)

    def _get_score_params(self) -> List[ParamInfo]:
        """Get parameters for score method."""
        return extract_params(self._metric_class.score)

    def to_metric_info(self) -> MetricInfo:
        """Convert to MetricInfo."""
        return MetricInfo(
            name=self._metric_class.__name__,
            description=self._description,
            init_params=self._get_init_params(),
            score_params=self._get_score_params(),
        )
