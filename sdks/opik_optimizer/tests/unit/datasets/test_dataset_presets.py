from __future__ import annotations

import unittest.mock as mock

import pytest

import opik_optimizer.datasets as dataset_module
from opik_optimizer.utils import dataset_utils


CURATED_DATASETS = [
    ("ai2_arc", "train", 300),
    ("gsm8k", "train", 300),
    ("truthful_qa", "validation", 300),
    ("cnn_dailymail", "validation", 100),
    ("ragbench_sentence_relevance", "train", 300),
    ("election_questions", "test", 300),
    ("medhallu", "train", 300),
    ("rag_hallucinations", "train", 300),
    ("tiny_test", "train", 5),
    ("halu_eval_300", "data", 300),
]


@pytest.mark.parametrize(
    "helper_name,preset_key,expected_count",
    CURATED_DATASETS,
)
def test_helpers_prefer_presets_by_default(
    helper_name: str, preset_key: str, expected_count: int
) -> None:
    """Ensure curated dataset helpers opt into their preset slices."""
    helper = getattr(dataset_module, helper_name)
    sentinel_dataset = mock.Mock(name=f"{helper_name}_dataset")
    with mock.patch.object(
        dataset_utils, "load_hf_dataset_slice", return_value=sentinel_dataset
    ) as mock_loader:
        dataset = helper()

    assert dataset is sentinel_dataset
    mock_loader.assert_called_once()
    kwargs = mock_loader.call_args.kwargs
    assert kwargs["prefer_presets"] is True
    preset = kwargs["presets"][preset_key]
    assert preset["count"] == expected_count
