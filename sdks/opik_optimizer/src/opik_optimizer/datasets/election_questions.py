from __future__ import annotations

import opik
from opik_optimizer.api_objects.types import DatasetSpec, DatasetSplitPreset
from opik_optimizer.utils.dataset_utils import DatasetHandle

ELECTION_QUESTIONS_SPEC = DatasetSpec(
    name="election_questions",
    hf_path="Anthropic/election_questions",
    default_source_split="test",
    prefer_presets=True,
    presets={
        "test": DatasetSplitPreset(
            source_split="test",
            start=0,
            count=300,
            dataset_name="election_questions_train",
        )
    },
)

_ELECTION_QUESTIONS_HANDLE = DatasetHandle(ELECTION_QUESTIONS_SPEC)


def election_questions(
    *,
    split: str | None = None,
    count: int | None = None,
    start: int | None = None,
    dataset_name: str | None = None,
    test_mode: bool = False,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Anthropic election question classification slices."""
    return _ELECTION_QUESTIONS_HANDLE.load(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
