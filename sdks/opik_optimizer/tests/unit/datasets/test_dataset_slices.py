from __future__ import annotations

from opik_optimizer.utils.dataset_utils import resolve_slice_request


def test_resolve_slice_request_respects_split_without_presets() -> None:
    presets = {
        "train": {
            "source_split": "train",
            "start": 0,
            "count": 5,
            "dataset_name": "example_train",
        },
        "validation": {
            "source_split": "train",
            "start": 0,
            "count": 5,
            "dataset_name": "example_validation",
        },
    }

    request = resolve_slice_request(
        base_name="example",
        requested_split="validation",
        presets=presets,
        default_source_split="train",
        prefer_presets=False,
    )

    assert request.source_split == "validation"
