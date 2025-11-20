from __future__ import annotations


import opik
from datasets import load_dataset

from opik_optimizer.utils.dataset_utils import (
    create_dataset_from_records,
    download_and_slice_hf_dataset,
    resolve_dataset_seed,
    resolve_test_mode_count,
)


def pupa_train(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _pupa_split(
        dataset_name="pupa_train",
        start=0,
        count=111,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def pupa_validation(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _pupa_split(
        dataset_name="pupa_validation",
        start=111,
        count=111,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def pupa_test(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _pupa_split(
        dataset_name="pupa_test",
        start=222,
        count=221,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def pupa_full(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    hf_dataset = load_dataset("Columbia-NLP/PUPA", "pupa_new", split="train")
    return _pupa_split(
        dataset_name="pupa_full",
        start=0,
        count=len(hf_dataset),
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def pupa_slice(
    *,
    dataset_name: str,
    start: int,
    count: int,
    seed: int | None = None,
    test_mode: bool = False,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _pupa_split(
        dataset_name=dataset_name,
        start=start,
        count=count,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def _pupa_split(
    *,
    dataset_name: str,
    start: int,
    count: int,
    test_mode: bool,
    seed: int | None,
    test_mode_count: int | None,
) -> opik.Dataset:
    resolved_seed = resolve_dataset_seed(seed)
    effective_test_count = resolve_test_mode_count(test_mode_count)
    expected_items = effective_test_count if test_mode else count

    records = download_and_slice_hf_dataset(
        load_fn=load_dataset,
        load_kwargs={"path": "Columbia-NLP/PUPA", "name": "pupa_new", "split": "train"},
        start=start,
        count=count,
        seed=resolved_seed,
    )
    if test_mode:
        records = records[:expected_items]

    return create_dataset_from_records(
        dataset_name=dataset_name,
        records=records,
        expected_size=expected_items,
        test_mode=test_mode,
    )
