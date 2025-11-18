from __future__ import annotations

import json
from importlib.resources import files

import opik
from datasets import load_dataset

from opik_optimizer.utils.dataset_utils import (
    create_dataset_from_records,
    download_and_slice_hf_dataset,
    resolve_dataset_seed,
    resolve_test_mode_count,
)


def hotpot_300(test_mode: bool = False) -> opik.Dataset:
    """
    Dataset containing the first 300 samples of the HotpotQA dataset.
    """
    dataset_name = "hotpot_300_train" if not test_mode else "hotpot_300_sample"
    nb_items = 300 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)

    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(
            f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it."
        )
    elif len(items) == 0:
        # Load data from file and insert into the dataset
        json_content = (files("opik_optimizer") / "data" / "hotpot-500.json").read_text(
            encoding="utf-8"
        )
        all_data = json.loads(json_content)
        trainset = all_data[:nb_items]

        data = []
        for row in reversed(trainset):
            data.append(row)

        dataset.insert(data)
        return dataset


def hotpot_500(test_mode: bool = False) -> opik.Dataset:
    """
    Dataset containing the first 500 samples of the HotpotQA dataset.
    """
    dataset_name = "hotpot_500" if not test_mode else "hotpot_500_test"
    nb_items = 500 if not test_mode else 5

    client = opik.Opik()
    dataset = client.get_or_create_dataset(dataset_name)

    items = dataset.get_items()
    if len(items) == nb_items:
        return dataset
    elif len(items) != 0:
        raise ValueError(
            f"Dataset {dataset_name} contains {len(items)} items, expected {nb_items}. We recommend deleting the dataset and re-creating it."
        )
    elif len(items) == 0:
        # Load data from file and insert into the dataset
        json_content = (files("opik_optimizer") / "data" / "hotpot-500.json").read_text(
            encoding="utf-8"
        )
        all_data = json.loads(json_content)
        trainset = all_data[:nb_items]

        data = []
        for row in reversed(trainset):
            data.append(row)

        dataset.insert(data)
        return dataset


HOT_POT_HF_DATASET = ("hotpot_qa", "fullwiki")


def hotpot_train(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Returns the 150-example training slice used in the GEPA benchmark."""
    dataset_name = "hotpot_train_sample" if test_mode else "hotpot_train"
    return _hotpot_split(
        dataset_name=dataset_name,
        source_split="train",
        start=0,
        count=150,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def hotpot_validation(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Returns the 300-example validation slice used in the GEPA benchmark."""
    dataset_name = "hotpot_validation_sample" if test_mode else "hotpot_validation"
    return _hotpot_split(
        dataset_name=dataset_name,
        source_split="train",
        start=150,
        count=300,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def hotpot_test(
    test_mode: bool = False,
    *,
    seed: int | None = None,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """Returns the 300-example test slice used in the GEPA benchmark."""
    dataset_name = "hotpot_test_sample" if test_mode else "hotpot_test"
    return _hotpot_split(
        dataset_name=dataset_name,
        source_split="validation",
        start=0,
        count=300,
        test_mode=test_mode,
        seed=seed,
        test_mode_count=test_mode_count,
    )


def hotpot_slice(
    *,
    dataset_name: str,
    source_split: str,
    start: int,
    count: int,
    seed: int | None = None,
    test_mode: bool = False,
    test_mode_count: int | None = None,
) -> opik.Dataset:
    """
    Generic helper to create HotpotQA subsets from Hugging Face.
    """
    return _hotpot_split(
        dataset_name=f"{dataset_name}{'_sample' if test_mode else ''}",
        source_split=source_split,
        start=start,
        count=count,
        seed=seed,
        test_mode=test_mode,
        test_mode_count=test_mode_count,
    )


def _hotpot_split(
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

    records = download_and_slice_hf_dataset(
        load_fn=load_dataset,
        load_kwargs={
            "path": HOT_POT_HF_DATASET[0],
            "name": HOT_POT_HF_DATASET[1],
            "split": source_split,
        },
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
