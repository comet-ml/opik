"""Metric descriptor for extracting metadata from metric classes."""

import dataclasses
import logging
from typing import Any, Dict, List, Optional, Type

from opik.evaluation.metrics import base_metric

from . import param_extractor

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class MetricInfo:
    """Information about a metric."""

    name: str
    description: str
    init_params: List[param_extractor.ParamInfo]
    score_params: List[param_extractor.ParamInfo]


class MetricDescriptor:
    """Extracts metadata from a metric class."""

    def __init__(
        self,
        metric_class: Type[base_metric.BaseMetric],
        description: Optional[str] = None,
        init_defaults: Optional[Dict[str, Any]] = None,
        score_defaults: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._metric_class = metric_class
        self._description = description or self._extract_class_docstring()
        self._init_defaults = init_defaults or {}
        self._score_defaults = score_defaults or {}

    @property
    def metric_class(self) -> Type[base_metric.BaseMetric]:
        return self._metric_class

    def _extract_class_docstring(self) -> str:
        """Extract the class docstring (first paragraph only)."""
        doc = self._metric_class.__doc__
        if doc:
            # Get the first paragraph - the brief description
            return doc.strip().split("\n\n")[0].strip()
        return ""

    def _get_init_params(self) -> List[param_extractor.ParamInfo]:
        """Get parameters from __init__ method with custom defaults applied."""
        params = param_extractor.extract_params(self._metric_class.__init__)
        return self._apply_custom_defaults(params, self._init_defaults)

    def _get_score_params(self) -> List[param_extractor.ParamInfo]:
        """Get parameters from score method with custom defaults applied."""
        params = param_extractor.extract_params(self._metric_class.score)
        return self._apply_custom_defaults(params, self._score_defaults)

    def _apply_custom_defaults(
        self,
        params: List[param_extractor.ParamInfo],
        custom_defaults: Dict[str, Any],
    ) -> List[param_extractor.ParamInfo]:
        """Apply custom default values to parameters."""
        if not custom_defaults:
            return params

        result = []
        for param in params:
            if param.name in custom_defaults:
                # Override the default with custom value
                result.append(
                    param_extractor.ParamInfo(
                        name=param.name,
                        required=False,  # If we have a custom default, it's not required
                        type=param.type,
                        default=custom_defaults[param.name],
                    )
                )
            else:
                result.append(param)
        return result

    def to_metric_info(self) -> MetricInfo:
        """Convert to MetricInfo dataclass."""
        return MetricInfo(
            name=self._metric_class.__name__,
            description=self._description,
            init_params=self._get_init_params(),
            score_params=self._get_score_params(),
        )

