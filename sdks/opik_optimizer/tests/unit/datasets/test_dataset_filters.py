from __future__ import annotations

import unittest.mock as mock

import pytest

from opik_optimizer.utils import dataset_utils
from opik_optimizer.utils.dataset_utils import record_matches_filter_by


def test_record_matches_filter_by_supports_common_predicates() -> None:
    record = {"task_id": "abc", "split": "train", "score": 0.8}

    assert record_matches_filter_by(record, {"task_id": "abc"})
    assert record_matches_filter_by(record, {"split": {"train", "validation"}})
    assert record_matches_filter_by(record, {"score": lambda v: v > 0.5})
    assert not record_matches_filter_by(record, {"task_id": "def"})


def test_load_hf_dataset_slice_appends_filter_suffix(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, str] = {}

    def _fake_fetch_records_for_slice(**_kwargs: object) -> list[dict[str, str]]:
        return [{"id": "1"}, {"id": "2"}]

    def _fake_create_dataset_from_records(
        *,
        dataset_name: str,
        records: list[dict[str, str]],
        expected_size: int,
        test_mode: bool,
    ) -> mock.Mock:
        captured["dataset_name"] = dataset_name
        return mock.Mock(name="dataset")

    monkeypatch.setattr(dataset_utils, "fetch_records_for_slice", _fake_fetch_records_for_slice)
    monkeypatch.setattr(
        dataset_utils, "create_dataset_from_records", _fake_create_dataset_from_records
    )

    dataset_utils.load_hf_dataset_slice(
        base_name="arc_agi2",
        requested_split="train",
        presets={
            "train": {
                "source_split": "train",
                "start": 0,
                "count": 2,
                "dataset_name": "arc_agi2_train",
            }
        },
        default_source_split="train",
        load_kwargs_resolver=lambda _split: {},
        start=None,
        count=None,
        dataset_name=None,
        test_mode=False,
        seed=42,
        test_mode_count=None,
        prefer_presets=True,
        filter_by={"task_id": "abc"},
    )

    assert captured["dataset_name"].startswith("arc_agi2_train_filtered_")
