"""Covers that task_evaluator forwards explicit dataset ids and sampling size."""

from typing import Any

from opik_optimizer import task_evaluator


class _MockDataset:
    def __init__(self, ids: list[str]) -> None:
        self._ids = ids

    def get_items(self, n: int | None = None) -> list[dict[str, Any]]:
        items = [{"id": _id} for _id in self._ids]
        return items if n is None else items[:n]


def _dummy_metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def test_evaluate_passes_ids_and_samples(monkeypatch: Any) -> None:
    """Expect provided ids to bypass n_samples and be forwarded untouched."""
    captured: dict[str, Any] = {}

    class _EvalResult:
        def __init__(self) -> None:
            self.test_results: list[Any] = [{"dummy": True}]

    def _fake_evaluate(**kwargs: Any) -> _EvalResult:
        captured["dataset_item_ids"] = kwargs.get("dataset_item_ids")
        captured["nb_samples"] = kwargs.get("nb_samples")
        return _EvalResult()

    monkeypatch.setattr(task_evaluator.opik_evaluator, "evaluate", _fake_evaluate)
    monkeypatch.setattr(
        task_evaluator.opik_evaluator, "evaluate_optimization_trial", _fake_evaluate
    )

    dataset = _MockDataset(["a", "b", "c", "d"])

    def _task(item: dict[str, Any]) -> dict[str, str]:
        return {"llm_output": "ok"}

    task_evaluator.evaluate(
        dataset=dataset,
        evaluated_task=_task,
        metric=_dummy_metric,
        num_threads=1,
        dataset_item_ids=["b", "d"],
        n_samples=None,
        optimization_id=None,
    )

    assert captured["dataset_item_ids"] == ["b", "d"]
    assert captured["nb_samples"] is None
