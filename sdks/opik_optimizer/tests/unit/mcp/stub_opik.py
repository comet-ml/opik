"""Provide lightweight stubs for optional opik dependencies during tests."""

import sys
import types


if "opik" not in sys.modules:
    opik_module = types.ModuleType("opik")

    evaluation_module = types.ModuleType("opik.evaluation")
    models_module = types.ModuleType("opik.evaluation.models")
    metrics_module = types.ModuleType("opik.evaluation.metrics")
    score_result_module = types.ModuleType("opik.evaluation.metrics.score_result")
    litellm_module = types.ModuleType("opik.evaluation.models.litellm")

    def warning_filters(*args, **kwargs):  # pragma: no cover - simple stub
        return None

    def track(**kwargs):  # pragma: no cover - simple stub
        def decorator(func):
            return func

        return decorator

    class _Dataset:
        def __init__(self, name: str):
            self.name = name
            self.id = name
            self._items = []

        def get_items(self, nb_samples=None):
            if nb_samples is None:
                return [dict(item) for item in self._items]
            return [dict(item) for item in self._items[:nb_samples]]

        def insert(self, data):
            for item in data:
                if "id" not in item:
                    item = {**item, "id": f"{self.name}-{len(self._items)}"}
                self._items.append(dict(item))

        def copy(self):
            copied = _Dataset(self.name)
            copied._items = [dict(item) for item in self._items]
            return copied

    class Opik:
        _DATASETS = {}

        def get_or_create_dataset(self, name):
            if name not in self._DATASETS:
                self._DATASETS[name] = _Dataset(name)
            return self._DATASETS[name]

    litellm_module.warning_filters = warning_filters

    class _ScoreResult:
        def __init__(self, name: str, value: float, reason: str, metadata=None):
            self.name = name
            self.value = value
            self.reason = reason
            self.metadata = metadata or {}

    score_result_module.ScoreResult = _ScoreResult
    metrics_module.score_result = score_result_module

    evaluation_module.models = types.SimpleNamespace(litellm=litellm_module)
    evaluation_module.metrics = metrics_module
    models_module.litellm = litellm_module

    sys.modules["opik"] = opik_module
    sys.modules["opik.evaluation"] = evaluation_module
    sys.modules["opik.evaluation.models"] = models_module
    sys.modules["opik.evaluation.metrics"] = metrics_module
    sys.modules["opik.evaluation.metrics.score_result"] = score_result_module
    sys.modules["opik.evaluation.models.litellm"] = litellm_module

    opik_module.Opik = Opik
    opik_module.evaluation = evaluation_module
    opik_module.track = track
