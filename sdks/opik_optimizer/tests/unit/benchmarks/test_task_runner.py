from typing import Any, cast

from opik_optimizer import BaseOptimizer, ChatPrompt
import opik_optimizer.datasets
import pytest

from benchmarks.utils.task_runner import (
    DatasetBundle,
    collect_dataset_metadata,
    evaluate_prompt_on_dataset,
    resolve_dataset_bundle,
)


class DummyDataset:
    def __init__(self, name: str) -> None:
        self.name = name
        self.id = f"{name}-id"

    def get_items(self) -> list[dict]:
        return []


class DummyOptimizer:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def evaluate_prompt(  # type: ignore[no-untyped-def]
        self,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Any,
        n_threads: int,
    ) -> float:
        metric_name = getattr(metric, "__name__", metric.__class__.__name__)
        self.calls.append((getattr(dataset, "name", "unknown"), metric_name))
        return 0.5


def test_resolve_dataset_bundle_prefers_validation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    dummy_train = DummyDataset("hotpot_train")
    dummy_validation = DummyDataset("hotpot_validation")
    dummy_test = DummyDataset("hotpot_test")

    def fake_hotpot(split: str | None = None, **_: object) -> DummyDataset:
        if split == "validation":
            return dummy_validation
        if split == "test":
            return dummy_test
        return dummy_train

    monkeypatch.setattr(opik_optimizer.datasets, "hotpot", fake_hotpot)

    bundle = resolve_dataset_bundle("hotpot_train", test_mode=False)

    assert bundle.train is dummy_train
    assert bundle.validation is dummy_validation
    assert bundle.test is dummy_test
    assert bundle.evaluation is dummy_validation
    assert bundle.evaluation_role == "validation"


def test_resolve_dataset_bundle_without_splits(monkeypatch: pytest.MonkeyPatch) -> None:
    dummy_dataset = DummyDataset("gsm8k")
    monkeypatch.setattr(
        opik_optimizer.datasets, "gsm8k", lambda test_mode=False: dummy_dataset
    )

    bundle = resolve_dataset_bundle("gsm8k", test_mode=True)

    assert bundle.train is dummy_dataset
    assert bundle.validation is None
    assert bundle.test is None
    assert bundle.evaluation is dummy_dataset
    assert bundle.evaluation_role == "train"


def test_evaluate_prompt_on_dataset_records_metadata() -> None:
    optimizer = cast(BaseOptimizer, DummyOptimizer())
    dataset = DummyDataset("hover_validation")

    def sample_metric(_dataset_item: dict, _llm_output: str) -> float:
        return 1.0

    result = evaluate_prompt_on_dataset(
        optimizer=optimizer,
        prompt=ChatPrompt(messages=[{"role": "user", "content": "{text}"}]),
        dataset=dataset,
        dataset_name="hover_validation",
        dataset_role="validation",
        metrics=[sample_metric],
        n_threads=1,
    )

    assert result.dataset is not None
    assert result.dataset.name == "hover_validation"
    assert result.dataset.split == "validation"
    assert result.metrics[0]["metric_name"] == "sample_metric"
    assert optimizer.calls == [("hover_validation", "sample_metric")]


def test_collect_dataset_metadata_includes_available_splits(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    train = DummyDataset("hotpot_train")
    validation = DummyDataset("hotpot_validation")
    bundle = DatasetBundle(
        train_name="hotpot_train",
        train=train,
        validation_name="hotpot_validation",
        validation=validation,
        test_name=None,
        test=None,
        evaluation_name="hotpot_validation",
        evaluation_role="validation",
        evaluation=validation,
        requested_split="train",
    )

    metadata = collect_dataset_metadata(bundle)

    assert set(metadata.keys()) == {"train", "validation"}
    assert metadata["train"].name == "hotpot_train"
    assert metadata["validation"].name == "hotpot_validation"


def test_resolve_dataset_bundle_with_overrides(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, dict[str, Any]]] = []

    def fake_loader(**kwargs: Any) -> DummyDataset:
        split_key = kwargs.get("split") or "train"
        calls.append((split_key, kwargs))
        name = kwargs.get("dataset_name", "override")
        return DummyDataset(name)

    monkeypatch.setattr(opik_optimizer.datasets, "hotpot", fake_loader)

    bundle = resolve_dataset_bundle(
        "hotpot",
        test_mode=False,
        datasets={
            "train": {"loader": "hotpot", "count": 1},
            "validation": {"loader": "hotpot", "count": 2},
        },
    )

    assert bundle.train.name == "hotpot_train"
    assert bundle.validation and bundle.validation.name == "hotpot_validation"
    assert bundle.test is None
    assert bundle.evaluation_role == "validation"
    assert calls and calls[0][1]["count"] == 1
