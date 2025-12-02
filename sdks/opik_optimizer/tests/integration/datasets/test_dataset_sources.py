from __future__ import annotations

import os
import shutil
from pathlib import Path
from collections.abc import Iterable

import pytest
from datasets import config as datasets_config

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


def _is_hf_offline_error(exc: Exception) -> bool:
    msg = str(exc)
    markers = (
        "Failed to resolve 'huggingface.co'",
        "Max retries exceeded with url",
        "NameResolutionError",
        "HFValidationError",
        "Temporary failure in name resolution",
    )
    return any(marker in msg for marker in markers)


def _is_disk_full_error(exc: Exception) -> bool:
    return "No space left on device" in str(exc)


def _default_cache_dir() -> Path:
    cache_env = os.getenv("HF_DATASETS_CACHE")
    if cache_env:
        return Path(cache_env).expanduser()
    return Path.home() / ".cache" / "huggingface"


@pytest.fixture
def ensured_hf_cache(
    tmp_path_factory: pytest.TempPathFactory, monkeypatch: pytest.MonkeyPatch
) -> Path:
    """
    Guarantee a writable HF cache path by copying the shared cache into a temp dir.

    This avoids permission conflicts with pre-existing lock files created by other
    users or CI jobs while still letting us reuse the downloaded dataset shards.
    """
    shared_cache = _default_cache_dir()
    isolated_home = tmp_path_factory.mktemp("hf_cache")
    datasets_cache = isolated_home / "datasets"
    datasets_cache.mkdir(parents=True, exist_ok=True)
    if shared_cache.exists():
        try:
            shutil.copytree(shared_cache, datasets_cache, dirs_exist_ok=True)
        except OSError:
            # Best-effort copy; if it fails we'll re-download when network allows.
            pass
    monkeypatch.setenv("HF_HOME", str(isolated_home))
    monkeypatch.setenv("HF_DATASETS_CACHE", str(datasets_cache))
    datasets_config.HF_CACHE_HOME = str(isolated_home)
    datasets_config.HF_DATASETS_CACHE = str(datasets_cache)
    return datasets_cache


@pytest.mark.integration
@pytest.mark.parametrize("spec", CURATED_SPECS, ids=lambda spec: spec.name)
def test_hf_sources_resolve_one_record(
    spec: DatasetSpec, ensured_hf_cache: Path
) -> None:  # noqa: ARG001
    """
    Ensure each curated dataset can fetch at least one record directly from Hugging Face.

    We bypass the Opik client entirely so this test exercises the HF integration only,
    preventing accidental dataset creation in shared environments.
    """
    # Fixture used for side effects (env + cache); keep lint happy
    assert ensured_hf_cache.exists()

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
    try:
        records = fetch_records_for_slice(
            slice_request=slice_request,
            load_kwargs_resolver=handle._load_kwargs_resolver,  # type: ignore[attr-defined]
            seed=resolve_dataset_seed(None),
            custom_loader=spec.custom_loader,
        )
    except Exception as exc:  # pragma: no cover - exercised in offline environments
        if _is_hf_offline_error(exc):
            pytest.skip(f"Hugging Face hub unavailable: {exc}")
        if _is_disk_full_error(exc):
            pytest.skip(f"HF cache volume is full: {exc}")
        raise
    assert len(records) == 1
    assert isinstance(records[0], dict)
    assert records[0], "Fetched record should contain data"
