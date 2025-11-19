from __future__ import annotations

from functools import lru_cache

from typing import Any

import opik

from functools import lru_cache

from opik_optimizer.utils.dataset_utils import OptimizerDatasetLoader


@lru_cache(maxsize=1)
def _get_election_loader() -> OptimizerDatasetLoader:
    return OptimizerDatasetLoader(
        base_name="election_questions",
        default_source_split="test",
        load_kwargs_resolver=lambda split: {
            "path": "Anthropic/election_questions",
            "split": split,
        },
        presets={
            "test": {
                "source_split": "test",
                "start": 0,
                "count": 300,
                "dataset_name": "election_questions_train",
            }
        },
        prefer_presets=True,
    )


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
    loader = _get_election_loader()
    return loader(
        split=split,
        count=count,
        start=start,
        dataset_name=dataset_name,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )
