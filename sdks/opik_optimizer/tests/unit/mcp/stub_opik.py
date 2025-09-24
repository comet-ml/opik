"""Provide lightweight stubs for optional opik dependencies during tests."""

from __future__ import annotations

from typing import Any, Callable, Dict, List, Optional

import sys
import types


if "opik" not in sys.modules:
    opik_module = types.ModuleType("opik")

    evaluation_module = types.ModuleType("opik.evaluation")
    models_module = types.ModuleType("opik.evaluation.models")
    metrics_module = types.ModuleType("opik.evaluation.metrics")
    score_result_module = types.ModuleType("opik.evaluation.metrics.score_result")
    litellm_module = types.ModuleType("opik.evaluation.models.litellm")

    def warning_filters(*args: Any, **kwargs: Any) -> None:  # pragma: no cover - stub
        return None

    def track(
        **kwargs: Any,
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:  # pragma: no cover - stub
        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            return func

        return decorator

    class _Dataset:
        def __init__(self, name: str) -> None:
            self.name = name
            self.id = name
            self._items: List[Dict[str, Any]] = []

        def get_items(self, nb_samples: Optional[int] = None) -> List[Dict[str, Any]]:
            if nb_samples is None:
                return [dict(item) for item in self._items]
            return [dict(item) for item in self._items[:nb_samples]]

        def insert(self, data: List[Dict[str, Any]]) -> None:
            for item in data:
                current = dict(item)
                current.setdefault("id", f"{self.name}-{len(self._items)}")
                self._items.append(current)

        def copy(self) -> "_Dataset":
            copied = _Dataset(self.name)
            copied._items = [dict(item) for item in self._items]
            return copied

    class Opik:
        _DATASETS: Dict[str, _Dataset] = {}

        def get_or_create_dataset(self, name: str) -> _Dataset:
            if name not in self._DATASETS:
                self._DATASETS[name] = _Dataset(name)
            return self._DATASETS[name]

    setattr(litellm_module, "warning_filters", warning_filters)

    class _ScoreResult:
        def __init__(
            self,
            name: str,
            value: float,
            reason: str,
            metadata: Optional[Dict[str, Any]] = None,
        ) -> None:
            self.name = name
            self.value = value
            self.reason = reason
            self.metadata = metadata or {}

    setattr(score_result_module, "ScoreResult", _ScoreResult)
    setattr(metrics_module, "score_result", score_result_module)

    setattr(evaluation_module, "models", types.SimpleNamespace(litellm=litellm_module))
    setattr(evaluation_module, "metrics", metrics_module)
    setattr(models_module, "litellm", litellm_module)

    sys.modules["opik"] = opik_module
    sys.modules["opik.evaluation"] = evaluation_module
    sys.modules["opik.evaluation.models"] = models_module
    sys.modules["opik.evaluation.metrics"] = metrics_module
    sys.modules["opik.evaluation.metrics.score_result"] = score_result_module
    sys.modules["opik.evaluation.models.litellm"] = litellm_module

    setattr(opik_module, "Opik", Opik)
    setattr(opik_module, "evaluation", evaluation_module)
    setattr(opik_module, "track", track)
