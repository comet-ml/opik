from __future__ import annotations

import os
import shutil
from pathlib import Path
from typing import Iterable

import pytest

from opik_optimizer.api_objects.types import DatasetSpec
from opik_optimizer.datasets.ai2_arc import AI2_ARC_SPEC
from opik_optimizer.datasets.cnn_dailymail import CNN_DAILYMAIL_SPEC
from opik_optimizer.datasets.election_questions import ELECTION_QUESTIONS_SPEC
from opik_optimizer.datasets.gsm8k import GSM8K_SPEC
from opik_optimizer.datasets.halu_eval import HALU_EVAL_SPEC
from opik_optimizer.datasets.medhallu import MEDHALLU_SPEC
from opik_optimizer.datasets.rag_hallucinations import RAG_HALLU_SPEC
from opik_optimizer.datasets.ragbench import RAGBENCH_SPEC
from opik_optimizer.datasets.tiny_test import TINY_TEST_SPEC
from opik_optimizer.datasets.truthful_qa import TRUTHFUL_QA_SPEC
from opik_optimizer.utils.dataset_utils import (
    DatasetHandle,
    fetch_records_for_slice,
    resolve_dataset_seed,
    resolve_slice_request,
)

CURATED_SPECS: Iterable[DatasetSpec] = [
    AI2_ARC_SPEC,
    CNN_DAILYMAIL_SPEC,
    ELECTION_QUESTIONS_SPEC,
    GSM8K_SPEC,
    HALU_EVAL_SPEC,
    MEDHALLU_SPEC,
    RAG_HALLU_SPEC,
    RAGBENCH_SPEC,
    TINY_TEST_SPEC,
    TRUTHFUL_QA_SPEC,
]


def _default_cache_dir() -> Path:
    cache_env = os.getenv("HF_DATASETS_CACHE")
    if cache_env:
        return Path(cache_env).expanduser()
    return Path.home() / ".cache" / "huggingface"


@pytest.fixture(scope="module")
def ensured_hf_cache(
    tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch
) -> Path:
    """
    Guarantee a writable HF cache path.

    We prefer the user's configured cache directory (to benefit from CI caching)
    and fall back to an isolated temp directory only when necessary.
    """
    cache_dir = _default_cache_dir()
    try:
        cache_dir.mkdir(parents=True, exist_ok=True)
        probe = cache_dir / ".write_test"
        probe.touch()
        probe.unlink()
        return cache_dir
    except OSError:
        alt_cache = tmp_path_factory.mktemp("hf_cache")
        datasets_cache = alt_cache / "datasets"
        datasets_cache.mkdir(exist_ok=True)
        if cache_dir.exists():
            try:
                shutil.copytree(cache_dir, datasets_cache, dirs_exist_ok=True)
            except OSError:
                # Best-effort copy; it's fine if it fails (dataset will re-download).
                pass
        monkeypatch.setenv("HF_HOME", str(alt_cache))
        monkeypatch.setenv("HF_DATASETS_CACHE", str(datasets_cache))
        return datasets_cache


@pytest.mark.integration
@pytest.mark.parametrize("spec", CURATED_SPECS, ids=lambda spec: spec.name)
def test_hf_sources_resolve_one_record(spec: DatasetSpec, ensured_hf_cache: Path) -> None:  # noqa: ARG001
    """
    Ensure each curated dataset can fetch at least one record directly from Hugging Face.

    We bypass the Opik client entirely so this test exercises the HF integration only,
    preventing accidental dataset creation in shared environments.
    """
    handle = DatasetHandle(spec)
    slice_request = resolve_slice_request(
        base_name=spec.name,
        requested_split=None,
        presets=handle._presets,  # type: ignore[attr-defined]
        default_source_split=spec.default_source_split,
        start=None,
        count=1,
        dataset_name=None,
        prefer_presets=True,
    )
    records = fetch_records_for_slice(
        slice_request=slice_request,
        load_kwargs_resolver=handle._load_kwargs_resolver,  # type: ignore[attr-defined]
        seed=resolve_dataset_seed(None),
        custom_loader=spec.custom_loader,
    )
    assert len(records) == 1
    assert isinstance(records[0], dict)
    assert records[0], "Fetched record should contain data"
