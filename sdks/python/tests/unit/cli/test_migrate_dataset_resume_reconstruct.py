"""Unit tests for read-only remap reconstruction on resume (OPIK-7168).

``reconstruct_remaps`` rebuilds version_remap / item_id_remap /
optimization_id_remap from an already-migrated destination without writing
anything. These tests mock the REST reads (versions, per-version items,
optimizations) and assert the pairing/identity/fallback logic and the
fail-loud guard on a version-count mismatch.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock

import pytest

from opik.cli.migrate.datasets.resume import (
    ResumeReconstructionError,
    reconstruct_remaps,
)


class _Version:
    def __init__(self, id: str, version_hash: str) -> None:
        self.id = id
        self.version_hash = version_hash


class _Item:
    def __init__(
        self,
        id: str,
        data: Optional[Dict[str, Any]] = None,
        description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        evaluators: Optional[List[Any]] = None,
        execution_policy: Optional[Any] = None,
        trace_id: Optional[str] = None,
        span_id: Optional[str] = None,
        source: Optional[str] = None,
    ) -> None:
        self.id = id
        self.data = data or {"q": id}
        self.description = description
        self.tags = tags
        self.evaluators = evaluators
        self.execution_policy = execution_policy
        self.trace_id = trace_id
        self.span_id = span_id
        self.source = source


class _Optimization:
    def __init__(self, id: str, name: str) -> None:
        self.id = id
        self.name = name


def _page(content: List[Any]) -> MagicMock:
    return MagicMock(content=content)


def _rest_client(
    *,
    source_versions: List[_Version],
    dest_versions: List[_Version],
    items_by_version_hash: Dict[str, List[_Item]],
    source_optimizations: Optional[List[_Optimization]] = None,
    dest_optimizations: Optional[List[_Optimization]] = None,
) -> MagicMock:
    rest = MagicMock()

    def _list_versions(id: str, page: int, size: int) -> MagicMock:
        # reconstruct calls _iter_source_versions_chronological which lists
        # NEWEST-first then reverses; return newest-first here.
        if page != 1:
            return _page([])
        if id == "src-ds":
            return _page(list(reversed(source_versions)))
        return _page(list(reversed(dest_versions)))

    rest.datasets.list_dataset_versions.side_effect = _list_versions

    def _stream(dataset_name: str, steam_limit: int, dataset_version: str, **kw: Any):
        # _stream_version_items_raw parses a byte stream via rest_stream_parser;
        # tests patch that helper directly (see the patch_stream fixture), so
        # this raw endpoint should never be hit.
        raise AssertionError("stream_dataset_items should be patched in tests")

    rest.datasets.stream_dataset_items.side_effect = _stream

    def _find_opts(dataset_id: str, page: int, size: int) -> MagicMock:
        if page != 1:
            return _page([])
        if dataset_id == "src-ds":
            return _page(source_optimizations or [])
        return _page(dest_optimizations or [])

    rest.optimizations.find_optimizations.side_effect = _find_opts
    return rest


@pytest.fixture
def patch_stream(monkeypatch: pytest.MonkeyPatch):
    """Patch ``_stream_version_items_raw`` (byte-stream parser) with a direct
    lookup keyed by version_hash, so tests supply plain ``_Item`` lists."""
    registry: Dict[str, List[_Item]] = {}

    def _fake_stream(
        rest_client: Any,
        *,
        dataset_name: str,
        project_name: Optional[str],
        version_hash: Optional[str],
    ) -> List[_Item]:
        return registry.get(version_hash or "", [])

    monkeypatch.setattr(
        "opik.cli.migrate.datasets.resume._stream_version_items_raw", _fake_stream
    )
    return registry


def test_reconstruct__paired_versions_and_identity_items__remaps_built(
    patch_stream: Dict[str, List[_Item]],
) -> None:
    source_versions = [_Version("sv1", "h1"), _Version("sv2", "h2")]
    # Destination hashes DIFFER from source (BE recomputes them) — pairing must
    # be positional, not by hash.
    dest_versions = [_Version("dv1", "dh1"), _Version("dv2", "dh2")]
    # Identity: dest item ids equal source item ids (copy_from preserves them).
    patch_stream["h1"] = [_Item("i1"), _Item("i2")]
    patch_stream["dh1"] = [_Item("i1"), _Item("i2")]
    patch_stream["h2"] = [_Item("i3")]
    patch_stream["dh2"] = [_Item("i3")]

    rest = _rest_client(
        source_versions=source_versions,
        dest_versions=dest_versions,
        items_by_version_hash={},
    )

    remaps = reconstruct_remaps(
        rest,
        source_dataset_id="src-ds",
        source_name="ds",
        source_project_name=None,
        dest_dataset_id="dst-ds",
        dest_name="ds",
        dest_project_name="proj",
    )

    assert remaps.version_remap == {"sv1": "dv1", "sv2": "dv2"}
    assert remaps.item_id_remap == {"i1": "i1", "i2": "i2", "i3": "i3"}


def test_reconstruct__dest_ids_differ__content_hash_fallback_maps_them(
    patch_stream: Dict[str, List[_Item]],
) -> None:
    # First-version read-back case: dest item id was minted fresh, so identity
    # match fails and we fall back to content-hash matching.
    source_versions = [_Version("sv1", "h1")]
    dest_versions = [_Version("dv1", "dh1")]
    patch_stream["h1"] = [_Item("src-i1", data={"q": "same"})]
    patch_stream["dh1"] = [_Item("dst-i1", data={"q": "same"})]

    rest = _rest_client(
        source_versions=source_versions,
        dest_versions=dest_versions,
        items_by_version_hash={},
    )

    remaps = reconstruct_remaps(
        rest,
        source_dataset_id="src-ds",
        source_name="ds",
        source_project_name=None,
        dest_dataset_id="dst-ds",
        dest_name="ds",
        dest_project_name="proj",
    )

    assert remaps.item_id_remap == {"src-i1": "dst-i1"}


def test_reconstruct__version_count_mismatch__raises_reconstruction_error(
    patch_stream: Dict[str, List[_Item]],
) -> None:
    # Destination has fewer versions than source (e.g. crash mid-replay) — must
    # fail loud rather than mis-pair.
    rest = _rest_client(
        source_versions=[_Version("sv1", "h1"), _Version("sv2", "h2")],
        dest_versions=[_Version("dv1", "dh1")],
        items_by_version_hash={},
    )

    with pytest.raises(ResumeReconstructionError, match="Delete the destination"):
        reconstruct_remaps(
            rest,
            source_dataset_id="src-ds",
            source_name="ds",
            source_project_name=None,
            dest_dataset_id="dst-ds",
            dest_name="ds",
            dest_project_name="proj",
        )


def test_reconstruct__optimizations__matched_by_name_into_remap(
    patch_stream: Dict[str, List[_Item]],
) -> None:
    source_versions = [_Version("sv1", "h1")]
    dest_versions = [_Version("dv1", "dh1")]
    patch_stream["h1"] = []
    patch_stream["dh1"] = []

    rest = _rest_client(
        source_versions=source_versions,
        dest_versions=dest_versions,
        items_by_version_hash={},
        source_optimizations=[
            _Optimization("so1", "opt-a"),
            _Optimization("so2", "opt-b"),
        ],
        dest_optimizations=[
            _Optimization("do1", "opt-a"),
            _Optimization("do2", "opt-b"),
        ],
    )

    remaps = reconstruct_remaps(
        rest,
        source_dataset_id="src-ds",
        source_name="ds",
        source_project_name=None,
        dest_dataset_id="dst-ds",
        dest_name="ds",
        dest_project_name="proj",
    )

    assert remaps.optimization_id_remap == {"so1": "do1", "so2": "do2"}
