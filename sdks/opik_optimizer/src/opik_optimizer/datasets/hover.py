from __future__ import annotations


import opik
from datasets import load_dataset

from opik_optimizer.utils.dataset_utils import (
    create_dataset_from_records,
    download_and_slice_hf_dataset,
    resolve_dataset_seed,
    resolve_test_mode_count,
)


def hover_train(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _hover_split(
        dataset_name="hover_train",
        source_split="train",
        start=0,
        count=150,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def hover_validation(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _hover_split(
        dataset_name="hover_validation",
        source_split="train",
        start=150,
        count=300,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def hover_test(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _hover_split(
        dataset_name="hover_test",
        source_split="validation",
        start=0,
        count=300,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def hover_slice(
    *,
    dataset_name: str,
    source_split: str,
    start: int,
    count: int,
    seed: int | None = None,
    test_mode: bool = False,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _hover_split(
        dataset_name=dataset_name,
        source_split=source_split,
        start=start,
        count=count,
        seed=seed,
        test_mode=test_mode,
        test_mode_count=test_mode_count,
    )


def _hover_split(
    *,
    dataset_name: str,
    source_split: str,
    start: int,
    count: int,
    seed: int | None,
    test_mode: bool,
    test_mode_count: int | None,
) -> opik.Dataset:
    resolved_seed = resolve_dataset_seed(seed)
    effective_test_count = resolve_test_mode_count(test_mode_count)
    expected_items = effective_test_count if test_mode else count

    selected = download_and_slice_hf_dataset(
        load_fn=load_dataset,
        load_kwargs={
            "path": "vincentkoc/hover-parquet",
            "split": source_split,
        },
        start=start,
        count=count,
        seed=resolved_seed,
    )
    if test_mode:
        selected = selected[:expected_items]

    return create_dataset_from_records(
        dataset_name=dataset_name,
        records=selected,
        expected_size=expected_items,
        test_mode=test_mode,
    )
