from __future__ import annotations

from typing import Any

import opik
from datasets import load_dataset

from opik_optimizer.utils.dataset_utils import (
    create_dataset_from_records,
    download_and_slice_hf_dataset,
    resolve_dataset_seed,
    resolve_test_mode_count,
)

IF_TRAIN_FILE = (
    "hf://datasets/allenai/IF_multi_constraints_upto5/data/train-00000-of-00001.parquet"
)
IF_TEST_FILE = "hf://datasets/allenai/IFBench_test/data/train-00000-of-00001.parquet"


def ifbench_train(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _ifbench_split(
        dataset_name="ifbench_train",
        source_key="if_train",
        start=0,
        count=150,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def ifbench_validation(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _ifbench_split(
        dataset_name="ifbench_validation",
        source_key="if_train",
        start=150,
        count=300,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def ifbench_test(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    return _ifbench_split(
        dataset_name="ifbench_test",
        source_key="if_test",
        start=0,
        count=294,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def ifbench_full_train(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    hf_dataset = load_dataset(**_ifbench_source_kwargs("if_train"))
    return _ifbench_split(
        dataset_name="ifbench_full_train",
        source_key="if_train",
        start=0,
        count=len(hf_dataset),
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def _ifbench_split(
    *,
    dataset_name: str,
    source_key: str,
    start: int,
    count: int,
    seed: int | None,
    test_mode: bool,
    test_mode_count: int | None,
) -> opik.Dataset:
    resolved_seed = resolve_dataset_seed(seed)
    effective_test_count = resolve_test_mode_count(test_mode_count)
    expected_items = effective_test_count if test_mode else count

    load_kwargs = _ifbench_source_kwargs(source_key)
    records = download_and_slice_hf_dataset(
        load_fn=load_dataset,
        load_kwargs=load_kwargs,
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


def _ifbench_source_kwargs(source_key: str) -> dict[str, Any]:
    if source_key == "if_train":
        data_file = IF_TRAIN_FILE
    elif source_key == "if_test":
        data_file = IF_TEST_FILE
    else:
        raise ValueError(f"Unknown IFBench source '{source_key}'.")
    return {"path": "parquet", "data_files": data_file, "split": "train"}
