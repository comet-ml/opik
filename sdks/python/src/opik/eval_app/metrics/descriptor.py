"""Metric descriptor for extracting metadata from metric classes."""

import dataclasses
import logging
import re
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
        self._raw_docstring = metric_class.__doc__ or ""
        self._description = description or self._extract_full_docstring()
        self._init_defaults = init_defaults or {}
        self._score_defaults = score_defaults or {}
        # Parse argument descriptions from class docstring
        self._init_param_descriptions = param_extractor.parse_docstring_args(
            self._raw_docstring
        )
        # Parse argument descriptions from score method docstring
        score_docstring = metric_class.score.__doc__ or ""
        self._score_param_descriptions = param_extractor.parse_docstring_args(
            score_docstring
        )

    @property
    def metric_class(self) -> Type[base_metric.BaseMetric]:
        return self._metric_class

    def _extract_full_docstring(self) -> str:
        """Extract the full docstring description (before Args/Returns sections)."""
        doc = self._raw_docstring
        if not doc:
            return ""

        # Remove leading/trailing whitespace
        doc = doc.strip()

        # Find the first section marker (Args:, Returns:, Raises:, Examples:, etc.)
        section_match = re.search(
            r"^\s*(Args|Returns|Raises|Examples|Attributes|Note|See Also|Yields|Warning):",
            doc,
            re.MULTILINE,
        )

        if section_match:
            # Return everything before the first section
            description = doc[: section_match.start()].strip()
        else:
            description = doc

        # Normalize whitespace - join lines into paragraphs
        lines = description.split("\n")
        paragraphs = []
        current_para: List[str] = []

        for line in lines:
            stripped = line.strip()
            if stripped:
                current_para.append(stripped)
            elif current_para:
                paragraphs.append(" ".join(current_para))
                current_para = []

        if current_para:
            paragraphs.append(" ".join(current_para))

        return "\n\n".join(paragraphs)

    def _get_init_params(self) -> List[param_extractor.ParamInfo]:
        """Get parameters from __init__ method with custom defaults applied."""
        params = param_extractor.extract_params(
            self._metric_class.__init__, self._init_param_descriptions
        )
        return self._apply_custom_defaults(params, self._init_defaults)

    def _get_score_params(self) -> List[param_extractor.ParamInfo]:
        """Get parameters from score method with custom defaults applied."""
        params = param_extractor.extract_params(
            self._metric_class.score, self._score_param_descriptions
        )
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
                        description=param.description,
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

