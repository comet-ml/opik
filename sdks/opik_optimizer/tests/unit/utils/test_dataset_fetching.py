from __future__ import annotations

from collections.abc import Callable

import pytest

from opik_optimizer.utils import dataset as dataset_utils


class DummyDataset:
    def __init__(self, records: list[dict[str, int]]) -> None:
        self._records = list(records)

    def __len__(self) -> int:
        return len(self._records)

    def shuffle(self, *, seed: int) -> DummyDataset:
        return self

    def filter(self, function: Callable[[dict[str, int]], bool]) -> DummyDataset:
        self._records = [record for record in self._records if function(record)]
        return self

    def select(self, indices: range) -> DummyDataset:
        return DummyDataset([self._records[i] for i in indices])

    def to_list(self) -> list[dict[str, int]]:
        return list(self._records)


def test_stream_records_for_slice_filters_and_slices() -> None:
    records = [{"value": i} for i in range(6)]

    def load_fn(*, streaming: bool, **kwargs: object) -> list[dict[str, int]]:
        del kwargs
        assert streaming
        return records

    result = dataset_utils.stream_records_for_slice(
        load_fn=load_fn,
        load_kwargs={},
        start=1,
        count=2,
        filter_by={"value": lambda value: value >= 2},
    )

    assert result == [{"value": 3}, {"value": 4}]


def test_download_and_slice_hf_dataset_raises_when_slice_exceeds_total() -> None:
    def load_fn(**kwargs: object) -> DummyDataset:
        del kwargs
        return DummyDataset([{"value": 0}, {"value": 1}, {"value": 2}])

    with pytest.raises(ValueError):
        dataset_utils.download_and_slice_hf_dataset(
            load_fn=load_fn,
            load_kwargs={},
            start=2,
            count=5,
            seed=42,
        )


def test_download_and_slice_hf_dataset_respects_filter_by(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def load_fn(**kwargs: object) -> DummyDataset:
        del kwargs
        return DummyDataset([{"value": 0}, {"value": 1}, {"value": 2}, {"value": 3}])

    result = dataset_utils.download_and_slice_hf_dataset(
        load_fn=load_fn,
        load_kwargs={},
        start=0,
        count=10,
        seed=42,
        filter_by={"value": lambda value: value >= 2},
    )

    assert result == [{"value": 2}, {"value": 3}]


def test_fetch_records_for_slice_falls_back_to_download(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    slice_request = dataset_utils.SliceRequest(
        source_split="train",
        start=0,
        count=2,
        dataset_name="test",
    )

    monkeypatch.setattr(
        dataset_utils,
        "stream_records_for_slice",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("stream disabled")),
    )

    download_called: list[dict[str, object]] = []

    def fake_download(
        *,
        load_fn: Callable[..., object],
        load_kwargs: dict[str, object],
        start: int,
        count: int | None,
        seed: int,
        filter_by: dict[str, object] | None = None,
    ) -> list[dict[str, object]]:
        download_called.append(load_kwargs)
        return [{"downloaded": True}]

    monkeypatch.setattr(dataset_utils, "download_and_slice_hf_dataset", fake_download)

    records = dataset_utils.fetch_records_for_slice(
        slice_request=slice_request,
        load_kwargs_resolver=lambda split: {"split": split},
        seed=42,
        filter_by={"value": 1},
        custom_loader=None,
        load_fn=lambda **kwargs: (),
    )

    assert records == [{"downloaded": True}]
    assert download_called
