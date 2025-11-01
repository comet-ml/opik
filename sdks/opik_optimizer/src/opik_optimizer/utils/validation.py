"""Backward-compatible imports for validation helpers.

Prefer importing from :mod:`opik_optimizer.utils.dataset_utils`.
"""

from __future__ import annotations

from .dataset_utils import DatasetSplitResult, ValidationSplit

__all__ = ["DatasetSplitResult", "ValidationSplit"]
