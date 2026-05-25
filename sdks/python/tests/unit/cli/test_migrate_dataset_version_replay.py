"""Tests for ``opik migrate`` Slice 2 — full version-history replay.

Slice 2 is the default behaviour: every source dataset/test-suite version is
replayed onto the target with full fidelity (items + per-item overrides +
suite-level config + version metadata + user tags + display order). The
Slice 1 fallback (``--exclude-versions``, current items only) lives in
``test_migrate_dataset_exclude_versions.py``.

Shared helpers (``_DatasetRow``, ``_Page``, ``_build_fake_client``) come from
``_migrate_helpers``.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock, patch

from click.testing import CliRunner

from opik.cli import cli
from opik.cli.migrate.audit import AuditLog

from ._migrate_helpers import _DatasetRow, _Page, _build_fake_client


# ---------------------------------------------------------------------------
# Slice 2: version-history replay
# ---------------------------------------------------------------------------


class _SourceVersion:
    """Minimal stand-in for ``DatasetVersionPublic`` (read side)."""

    def __init__(
        self,
        id: str,
        version_hash: str,
        version_name: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        evaluators: Optional[List[Any]] = None,
        execution_policy: Optional[Any] = None,
        metadata: Optional[Dict[str, str]] = None,
    ) -> None:
        self.id = id
        self.version_hash = version_hash
        self.version_name = version_name
        self.change_description = change_description
        self.tags = tags
        self.evaluators = evaluators
        self.execution_policy = execution_policy
        self.metadata = metadata


class _AppliedVersion:
    """Stand-in for the ``DatasetVersionPublic`` returned by apply_changes."""

    def __init__(self, id: str, version_hash: str) -> None:
        self.id = id
        self.version_hash = version_hash


def _ds_item(
    item_id: str,
    *,
    description: Optional[str] = None,
    tags: Optional[List[str]] = None,
    evaluators: Optional[List[Dict[str, Any]]] = None,
    execution_policy: Optional[Dict[str, int]] = None,
    trace_id: Optional[str] = None,
    span_id: Optional[str] = None,
    source: str = "sdk",
    **data: Any,
) -> Any:
    """Build a wire-type ``DatasetItemPublic`` for the parser-level stream
    patches to yield. Mirrors the BE-side persisted shape; all per-item
    fields the migration cares about (tags, evaluators, execution_policy,
    description, trace_id, span_id, source) are exposed as kwargs."""
    from opik.rest_api.types.dataset_item_public import DatasetItemPublic
    from opik.rest_api.types.evaluator_item_public import EvaluatorItemPublic
    from opik.rest_api.types.execution_policy_public import ExecutionPolicyPublic

    evals = None
    if evaluators is not None:
        evals = [
            EvaluatorItemPublic(name=e["name"], type=e["type"], config=e["config"])
            for e in evaluators
        ]
    pol = None
    if execution_policy is not None:
        pol = ExecutionPolicyPublic(
            runs_per_item=execution_policy["runs_per_item"],
            pass_threshold=execution_policy["pass_threshold"],
        )
    return DatasetItemPublic(
        id=item_id,
        data=data,
        description=description,
        tags=tags,
        evaluators=evals,
        execution_policy=pol,
        trace_id=trace_id,
        span_id=span_id,
        source=source,
    )


class TestVersionItemStreamPagination:
    """Pin the per-version item-stream pagination contract.

    The BE caps each ``/items/stream`` response at 2000 items
    (``DatasetItemStreamRequest.steamLimit``'s ``@Max(2000)`` + 2000
    default). ``_stream_version_items_raw`` MUST paginate via
    ``lastRetrievedId`` -- without it, any version with >2000 items was
    silently truncated and ``_compute_delta`` then misclassified the
    missing tail as deletions on every subsequent source version.

    These tests bypass the executor and hit ``_stream_version_items_raw``
    directly so the pagination behavior is anchored independently of the
    rest of the replay machinery.
    """

    def test_streams_single_page_when_version_under_cap(self) -> None:
        from opik.cli.migrate.datasets.version_replay import (
            _stream_version_items_raw,
        )

        rest_client = MagicMock()
        rest_client.datasets.stream_dataset_items.return_value = iter(())

        # Three items, all fit in one page (cap=2000) -- expect a single
        # call to ``stream_dataset_items`` with ``last_retrieved_id=None``.
        all_items = [_ds_item(f"id-{i}", q=f"Q{i}") for i in range(3)]
        with patch(
            "opik.cli.migrate.datasets.version_replay.rest_stream_parser.read_and_parse_stream",
            return_value=all_items,
        ):
            result = _stream_version_items_raw(
                rest_client,
                dataset_name="MyDataset",
                project_name="A",
                version_hash="v1-hash",
            )

        assert [it.id for it in result] == ["id-0", "id-1", "id-2"]
        assert rest_client.datasets.stream_dataset_items.call_count == 1
        first_call_kwargs = rest_client.datasets.stream_dataset_items.call_args_list[
            0
        ].kwargs
        assert first_call_kwargs["last_retrieved_id"] is None

    def test_paginates_until_short_page_when_version_exceeds_cap(self) -> None:
        """A version with 2500 items must yield ALL 2500, not just 2000.

        The BE returns at most ``steam_limit`` items per call (default
        2000); the client must re-call with ``last_retrieved_id`` set to
        the last item's id until it gets a short page. This test pins
        that behavior: previously the replay code called once and
        truncated to 2000, then ``_compute_delta`` saw the 500-item tail
        as "deletions" against the next version that DID include them.
        """
        from opik.cli.migrate.datasets.version_replay import (
            _stream_version_items_raw,
        )

        rest_client = MagicMock()
        rest_client.datasets.stream_dataset_items.return_value = iter(())

        page_one = [_ds_item(f"id-{i}", q=f"Q{i}") for i in range(2000)]
        page_two = [_ds_item(f"id-{i}", q=f"Q{i}") for i in range(2000, 2500)]
        pages = iter([page_one, page_two])

        with patch(
            "opik.cli.migrate.datasets.version_replay.rest_stream_parser.read_and_parse_stream",
            side_effect=lambda *args, **kwargs: next(pages),
        ):
            result = _stream_version_items_raw(
                rest_client,
                dataset_name="MyDataset",
                project_name="A",
                version_hash="big-version",
            )

        assert len(result) == 2500
        assert [it.id for it in result[:3]] == ["id-0", "id-1", "id-2"]
        assert [it.id for it in result[-3:]] == ["id-2497", "id-2498", "id-2499"]

        assert rest_client.datasets.stream_dataset_items.call_count == 2
        kwargs_first = rest_client.datasets.stream_dataset_items.call_args_list[
            0
        ].kwargs
        kwargs_second = rest_client.datasets.stream_dataset_items.call_args_list[
            1
        ].kwargs
        assert kwargs_first["last_retrieved_id"] is None
        assert kwargs_second["last_retrieved_id"] == "id-1999"


class TestVersionReplayUnit:
    """Direct unit tests for ``version_replay`` — bypass the Click CLI so
    we can exercise the delta / apply / remap logic without staging the
    full executor surface."""

    def _setup(
        self,
        *,
        source_versions: List[_SourceVersion],
        items_per_version: Dict[str, List[Any]],
        applied_versions: List[_AppliedVersion],
        source_dataset_id: str = "src-id",
        dest_dataset_id: str = "tgt-dataset-id",
    ) -> tuple[MagicMock, AuditLog]:
        rest_client = MagicMock()
        # ``list_dataset_versions`` is called twice in the new flow:
        # 1) source-version listing (id=source_dataset_id, paginated newest-first);
        # 2) post-``create_or_update_dataset_items`` lookup of v1 on the
        #    target (id=dest_dataset_id, page=1 size=1).
        # Route by id so both paths can coexist on one mock.
        applied_iter = iter(applied_versions)

        def _list_versions(id: str, **kwargs: Any) -> Any:
            if id == source_dataset_id:
                # Paginated source version listing — newest-first.
                page = kwargs.get("page", 1)
                if page == 1:
                    return _Page(list(reversed(source_versions)))
                return _Page([])
            if id == dest_dataset_id:
                # Post-v1-write read-back: peel one ``applied_versions`` entry
                # and surface it as the latest version on the target.
                next_v = next(applied_iter)
                return _Page([next_v])
            raise AssertionError(f"unexpected list_dataset_versions id={id}")

        rest_client.datasets.list_dataset_versions.side_effect = _list_versions

        # apply_dataset_item_changes — one response per call, fed from the
        # remaining applied_versions pool (after any v1 read-back consumed entries).
        rest_client.datasets.apply_dataset_item_changes.side_effect = (
            lambda *args, **kwargs: next(applied_iter)
        )

        # ``create_or_update_dataset_items`` returns None (per its REST signature).
        rest_client.datasets.create_or_update_dataset_items.return_value = None

        # The replay code reads items via raw REST stream + parser. We
        # capture the ``dataset_version`` arg on the raw stream call and
        # route the parser patch to the right items_per_version bucket.
        last_dataset_version: List[Optional[str]] = [None]

        def _raw_stream(
            *,
            dataset_name: str,
            project_name: Optional[str] = None,
            dataset_version: Optional[str] = None,
            **_: Any,
        ) -> Any:
            last_dataset_version[0] = dataset_version
            return iter(())  # parser will be patched; bytes content doesn't matter

        rest_client.datasets.stream_dataset_items.side_effect = _raw_stream

        def _parse(stream: Any, item_class: Any, **_: Any) -> List[Any]:
            return list(items_per_version.get(last_dataset_version[0] or "", []))

        self._parse_patch = patch(
            "opik.cli.migrate.datasets.version_replay.rest_stream_parser.read_and_parse_stream",
            side_effect=_parse,
        )
        self._parse_patch.start()

        audit = AuditLog(command="opik migrate dataset", args={})
        return rest_client, audit

    def teardown_method(self) -> None:
        if hasattr(self, "_parse_patch"):
            self._parse_patch.stop()

    def test_replay__single_version_source__one_create_or_update_call(self) -> None:
        # Source v1 has items, so replay's first-version path uses
        # ``create_or_update_dataset_items`` (the same write path
        # ``Dataset.insert`` uses) — NOT ``apply_dataset_item_changes``.
        # This is what makes target version count == source version count
        # for plain datasets.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1", version_name="v1"),
            ],
            items_per_version={
                "hv1": [
                    _ds_item("item-a", input="hello"),
                    _ds_item("item-b", input="world"),
                ],
                # Post-create re-read on the new target version.
                "tgt-h1": [
                    _ds_item("tgt-a", input="hello"),
                    _ds_item("tgt-b", input="world"),
                ],
            },
            applied_versions=[_AppliedVersion(id="tgt-v1", version_hash="tgt-h1")],
        )

        result = replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        assert result.versions_replayed == 1
        assert result.version_remap == {"src-v1": "tgt-v1"}
        # item_id_remap reflects content-hash matches between source adds
        # and the target's re-read items.
        assert set(result.item_id_remap.keys()) == {"item-a", "item-b"}
        assert set(result.item_id_remap.values()) == {"tgt-a", "tgt-b"}

        # No apply_dataset_item_changes calls at all — v1 went through
        # create_or_update_dataset_items.
        assert rest_client.datasets.apply_dataset_item_changes.call_count == 0
        rest_client.datasets.create_or_update_dataset_items.assert_called_once()
        create_kwargs = (
            rest_client.datasets.create_or_update_dataset_items.call_args.kwargs
        )
        assert create_kwargs["dataset_id"] == "tgt-dataset-id"
        assert create_kwargs["batch_group_id"] is not None
        # Items are sent reversed (newest source-stream item last) so the
        # BE's UUIDv7 + ORDER BY id DESC display order matches the source.
        assert [it.data for it in create_kwargs["items"]] == [
            {"input": "world"},
            {"input": "hello"},
        ]

    def test_replay__v1_over_be_cap__chunks_create_or_update_with_shared_batch_group(
        self,
    ) -> None:
        # The BE caps ``DatasetItemBatch.items`` at @Size(max=1000); a
        # source v1 with more than 1000 items must be split across multiple
        # ``create_or_update_dataset_items`` POSTs. All chunks must share
        # the SAME ``batch_group_id`` so the BE rolls them into ONE target
        # version (per ``DatasetItemService.applyToLatestVersion``); a
        # fresh group id per chunk would mint one new version per chunk and
        # blow up target version count.
        from opik.cli.migrate.datasets.version_replay import (
            _DATASET_ITEMS_INSERT_BATCH_SIZE,
            replay_all_versions,
        )

        # Use a size just over the cap so the test is fast but the chunking
        # branch is exercised (cap + 1 -> 2 chunks).
        oversize_n = _DATASET_ITEMS_INSERT_BATCH_SIZE + 1
        source_items = [_ds_item(f"item-{i}", input=f"v{i}") for i in range(oversize_n)]
        target_items = [_ds_item(f"tgt-{i}", input=f"v{i}") for i in range(oversize_n)]

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1", version_name="v1"),
            ],
            items_per_version={
                "hv1": source_items,
                "tgt-h1": target_items,
            },
            applied_versions=[_AppliedVersion(id="tgt-v1", version_hash="tgt-h1")],
        )

        result = replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # Single source version => single target version after chunked writes.
        assert result.versions_replayed == 1
        assert result.version_remap == {"src-v1": "tgt-v1"}

        # Two chunks: 1000 + 1.
        call_args_list = (
            rest_client.datasets.create_or_update_dataset_items.call_args_list
        )
        assert len(call_args_list) == 2
        chunk_sizes = [len(c.kwargs["items"]) for c in call_args_list]
        assert chunk_sizes == [_DATASET_ITEMS_INSERT_BATCH_SIZE, 1], (
            "expected first chunk at the BE cap and a small tail chunk"
        )

        # All chunks must share one batch_group_id -- otherwise the BE
        # mints N versions.
        batch_group_ids = {c.kwargs["batch_group_id"] for c in call_args_list}
        assert len(batch_group_ids) == 1, (
            f"chunks must share one batch_group_id, got {batch_group_ids}"
        )

        # Reversed display-order pinning still holds across chunks: item 0
        # of the source (newest in stream order) is written LAST overall.
        all_sent_items = [it for call in call_args_list for it in call.kwargs["items"]]
        assert all_sent_items[-1].data == {"input": "v0"}
        assert all_sent_items[0].data == {"input": f"v{oversize_n - 1}"}

    def test_replay__items_built_from_traces__source_and_trace_id_round_trip(
        self,
    ) -> None:
        # Datasets built from traces (Opik's "Add to dataset" trace flow)
        # produce items with ``source="trace"`` and a non-null ``trace_id``
        # pointing at the originating trace. Both fields must round-trip
        # to the destination so the migrated dataset still surfaces the
        # trace-origin link in the UI. ``trace_id`` references stay valid
        # without remap because traces are workspace-globally addressable
        # by id; the migration runs within a single workspace.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1", version_name="v1"),
            ],
            items_per_version={
                "hv1": [
                    _ds_item(
                        "item-a",
                        input="hello",
                        trace_id="trace-from-prod-X",
                        source="trace",
                    ),
                    _ds_item(
                        "item-b",
                        input="world",
                        trace_id="trace-from-prod-Y",
                        span_id="span-from-prod-Y",
                        source="span",
                    ),
                    _ds_item("item-c", input="manual", source="manual"),
                ],
                "tgt-h1": [
                    _ds_item(
                        "tgt-a",
                        input="hello",
                        trace_id="trace-from-prod-X",
                        source="trace",
                    ),
                    _ds_item(
                        "tgt-b",
                        input="world",
                        trace_id="trace-from-prod-Y",
                        span_id="span-from-prod-Y",
                        source="span",
                    ),
                    _ds_item("tgt-c", input="manual", source="manual"),
                ],
            },
            applied_versions=[_AppliedVersion(id="tgt-v1", version_hash="tgt-h1")],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # Inspect what got sent on the v1 create_or_update call: source +
        # trace_id + span_id all preserved per item, untouched by the remap.
        create_kwargs = (
            rest_client.datasets.create_or_update_dataset_items.call_args.kwargs
        )
        sent_by_input = {it.data["input"]: it for it in create_kwargs["items"]}
        assert sent_by_input["hello"].trace_id == "trace-from-prod-X"
        assert sent_by_input["hello"].source == "trace"
        assert sent_by_input["hello"].span_id is None
        assert sent_by_input["world"].trace_id == "trace-from-prod-Y"
        assert sent_by_input["world"].span_id == "span-from-prod-Y"
        assert sent_by_input["world"].source == "span"
        assert sent_by_input["manual"].trace_id is None
        assert sent_by_input["manual"].span_id is None
        assert sent_by_input["manual"].source == "manual"

    def test_replay__adds_only_v2__second_apply_carries_only_new_items(self) -> None:
        # v1 = [a, b]; v2 = [a, b, c]. Delta on v2 = [c] add, no mods, no dels.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", input="A"), _ds_item("b", input="B")],
                "hv2": [
                    _ds_item("a", input="A"),
                    _ds_item("b", input="B"),
                    _ds_item("c", input="C"),
                ],
                "tgt-h1": [_ds_item("tgt-a", input="A"), _ds_item("tgt-b", input="B")],
                "tgt-h2": [
                    _ds_item("tgt-a", input="A"),
                    _ds_item("tgt-b", input="B"),
                    _ds_item("tgt-c", input="C"),
                ],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        result = replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        assert result.versions_replayed == 2
        assert result.version_remap == {"src-v1": "tgt-v1", "src-v2": "tgt-v2"}

        # Two apply calls total.
        # v1 written via create_or_update_dataset_items; v2 via apply.
        rest_client.datasets.create_or_update_dataset_items.assert_called_once()
        v1_items = rest_client.datasets.create_or_update_dataset_items.call_args.kwargs[
            "items"
        ]
        assert len(v1_items) == 2
        assert rest_client.datasets.apply_dataset_item_changes.call_count == 1
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        # v2: only the new item ("c") as an add; base = tgt-v1 (the v1 we
        # just minted via create_or_update_dataset_items).
        assert len(v2_request["added_items"]) == 1
        assert v2_request["added_items"][0]["data"] == {"input": "C"}
        assert "edited_items" not in v2_request
        assert "deleted_ids" not in v2_request
        assert v2_request["base_version"] == "tgt-v1"

    def test_replay__modifications_only__edits_carry_stable_ids(self) -> None:
        # v1 = [a={x:1}, b={y:2}]; v2 = [a={x:99}, b={y:2}]. Item a is modified.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", x=1), _ds_item("b", y=2)],
                "hv2": [_ds_item("a", x=99), _ds_item("b", y=2)],
                "tgt-h1": [_ds_item("tgt-a", x=1), _ds_item("tgt-b", y=2)],
                "tgt-h2": [_ds_item("tgt-a", x=99), _ds_item("tgt-b", y=2)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v2 is the FIRST apply call (v1 went through create_or_update).
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        # Only modifications; no adds, no deletions.
        assert "added_items" not in v2_request
        assert "deleted_ids" not in v2_request
        assert len(v2_request["edited_items"]) == 1
        edit = v2_request["edited_items"][0]
        # Edit is keyed by the *target's* id (BE matches edits by id, so the
        # source id from item_id_remap[source_id]==target_id is the right
        # value to send). v1's add gave source 'a' the target id 'tgt-a',
        # which is what the v2 edit must now carry.
        assert edit["id"] == "tgt-a"
        assert edit["data"] == {"x": 99}

    def test_replay__deletions_only__deleted_ids_carry_previous_ids(self) -> None:
        # v1 = [a, b]; v2 = [a]. Item b deleted.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", input="A"), _ds_item("b", input="B")],
                "hv2": [_ds_item("a", input="A")],
                "tgt-h1": [_ds_item("tgt-a", input="A"), _ds_item("tgt-b", input="B")],
                "tgt-h2": [_ds_item("tgt-a", input="A")],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v2 is the FIRST apply call (v1 went through create_or_update).
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert "added_items" not in v2_request
        assert "edited_items" not in v2_request
        # Deletions reference the *target's* id for item 'b' (added in v1
        # as 'tgt-b'), not the source id, since BE matches by target id.
        assert v2_request["deleted_ids"] == ["tgt-b"]

    def test_replay__mixed_delta__separates_adds_mods_and_dels(self) -> None:
        # v1 = [a={x:1}, b={y:2}, c={z:3}]
        # v2 = [a={x:99}, c={z:3}, d={w:4}] — modify a, delete b, add d.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [
                    _ds_item("a", x=1),
                    _ds_item("b", y=2),
                    _ds_item("c", z=3),
                ],
                "hv2": [
                    _ds_item("a", x=99),
                    _ds_item("c", z=3),
                    _ds_item("d", w=4),
                ],
                "tgt-h1": [
                    _ds_item("tgt-a", x=1),
                    _ds_item("tgt-b", y=2),
                    _ds_item("tgt-c", z=3),
                ],
                "tgt-h2": [
                    _ds_item("tgt-a", x=99),
                    _ds_item("tgt-c", z=3),
                    _ds_item("tgt-d", w=4),
                ],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        result = replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v2 is the FIRST apply call (v1 went through create_or_update).
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert len(v2_request["added_items"]) == 1
        assert v2_request["added_items"][0]["data"] == {"w": 4}
        assert len(v2_request["edited_items"]) == 1
        # Edit / delete payloads reference target ids (remapped from source
        # ids via the previously-built item_id_remap).
        assert v2_request["edited_items"][0]["id"] == "tgt-a"
        assert v2_request["edited_items"][0]["data"] == {"x": 99}
        assert v2_request["deleted_ids"] == ["tgt-b"]

        # version_remap captures both versions; item_id_remap captures
        # adds across both versions but NOT modifications (mods keep their
        # stable id and don't need remapping).
        assert result.version_remap == {"src-v1": "tgt-v1", "src-v2": "tgt-v2"}
        # v1 adds: a, b, c → tgt-a, tgt-b, tgt-c. v2 adds: d → tgt-d.
        assert result.item_id_remap == {
            "a": "tgt-a",
            "b": "tgt-b",
            "c": "tgt-c",
            "d": "tgt-d",
        }

    def test_replay__zero_versions_source__no_op(self) -> None:
        # Source dataset has no committed versions — replay walks an empty
        # list and returns an empty result without calling apply_changes.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[],
            items_per_version={},
            applied_versions=[],
        )

        result = replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        assert result.versions_replayed == 0
        assert result.version_remap == {}
        assert result.item_id_remap == {}
        rest_client.datasets.apply_dataset_item_changes.assert_not_called()

    def test_replay__send_order_is_reversed_to_match_source_display_order(
        self,
    ) -> None:
        # Pin the bug found by the slice 2 e2e: source streams items in
        # newest-first display order (ClickHouse ORDER BY id DESC over UUIDv7),
        # and the BE assigns row UUIDs to each apply payload in list order
        # with later list entries getting larger UUIDs. To preserve the
        # source's display order on the target, ``added_items`` and
        # ``edited_items`` must be sent REVERSED — newest-display-first item
        # last in the list so it gets the largest UUID and lands on top.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                # Source v1 streams [Q3, Q2, Q1] in newest-first order. v2
                # adds Q5 (newest), Q4, and edits Q1 and Q3 — items_per_version
                # is what the source's per-version stream returns.
                "hv1": [
                    _ds_item("Q3-id", q="Q3"),
                    _ds_item("Q2-id", q="Q2"),
                    _ds_item("Q1-id", q="Q1"),
                ],
                "hv2": [
                    _ds_item("Q5-id", q="Q5"),
                    _ds_item("Q4-id", q="Q4"),
                    _ds_item("Q3-id", q="Q3-EDITED"),
                    _ds_item("Q2-id", q="Q2"),
                    _ds_item("Q1-id", q="Q1-EDITED"),
                ],
                "tgt-h1": [
                    _ds_item("tgt-Q3", q="Q3"),
                    _ds_item("tgt-Q2", q="Q2"),
                    _ds_item("tgt-Q1", q="Q1"),
                ],
                "tgt-h2": [
                    _ds_item("tgt-Q5", q="Q5"),
                    _ds_item("tgt-Q4", q="Q4"),
                    _ds_item("tgt-Q3", q="Q3-EDITED"),
                    _ds_item("tgt-Q2", q="Q2"),
                    _ds_item("tgt-Q1", q="Q1-EDITED"),
                ],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v1 added [Q3, Q2, Q1] (source order). create_or_update_dataset_items
        # gets the items REVERSED so the BE's UUIDv7 + ORDER BY id DESC
        # display order matches the source — Q3 gets the largest UUID and
        # displays first.
        v1_items = rest_client.datasets.create_or_update_dataset_items.call_args.kwargs[
            "items"
        ]
        assert [it.data["q"] for it in v1_items] == ["Q1", "Q2", "Q3"]

        # v2 adds [Q5, Q4] in source order (newest first), edits [Q3, Q1]
        # in source order. After reversal in the apply payload:
        # added=[Q4, Q5], edited=[Q1, Q3].
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert [a["data"]["q"] for a in v2_request["added_items"]] == [
            "Q4",
            "Q5",
        ]
        assert [e["data"]["q"] for e in v2_request["edited_items"]] == [
            "Q1-EDITED",
            "Q3-EDITED",
        ]

    def test_replay__edits_and_deletes_use_target_ids_not_source_ids(self) -> None:
        # Pin the bug found by the slice 2 e2e: edits/deletes in v_n+1 must
        # reference the target-side item id (assigned by the BE on add in
        # v_n), NOT the source's stable id. The BE matches edit/delete
        # payloads by id against the *target* dataset, so forwarding source
        # ids would silently no-op and the target version would diverge from
        # the source version's content.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("src-a", x=1), _ds_item("src-b", y=2)],
                # v2 modifies src-a, deletes src-b.
                "hv2": [_ds_item("src-a", x=99)],
                # Target side: BE assigned new ids on the v1 adds.
                "tgt-h1": [_ds_item("TGT-A", x=1), _ds_item("TGT-B", y=2)],
                "tgt-h2": [_ds_item("TGT-A", x=99)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v2 is the FIRST apply call (v1 went through create_or_update).
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        # Edit ID is the target id (TGT-A), not the source id (src-a).
        assert v2_request["edited_items"][0]["id"] == "TGT-A"
        # Delete ID is the target id (TGT-B), not the source id (src-b).
        assert v2_request["deleted_ids"] == ["TGT-B"]

    def test_replay__clears_version_level_execution_policy_when_dropped(
        self,
    ) -> None:
        # PR review #3 (baz-reviewer, version_replay.py:706): when v_n had a
        # suite-level execution_policy and v_{n+1} drops it (set to None), the
        # BE inherits the previous policy on omission. Forward the dedicated
        # clear_execution_policy=true flag at the version level so the target
        # mirrors the source's drop.
        #
        # Shape: v1 is config-only (test-suite-style) carrying the policy;
        # v2 has items and drops the policy. v1 routes through the
        # config-only path (one apply call); v2 routes through the standard
        # apply path with clear_execution_policy=true.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        pol = MagicMock()
        pol.runs_per_item = 3
        pol.pass_threshold = 2

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1", execution_policy=pol),
                # v2 drops the suite-level policy.
                _SourceVersion(id="src-v2", version_hash="hv2", execution_policy=None),
            ],
            items_per_version={
                "hv1": [],  # v1 config-only, carries the policy only.
                "hv2": [_ds_item("a", x=1)],
                "tgt-h2": [_ds_item("tgt-a", x=1)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v1 = config-only apply (carries policy); v2 = standard apply with
        # clear flag. apply_dataset_item_changes called twice; v2 is the
        # second entry.
        calls = rest_client.datasets.apply_dataset_item_changes.call_args_list
        assert len(calls) == 2
        v2_request = calls[1].kwargs["request"]
        # v2: omit execution_policy AND send clear flag — that's what gets
        # the BE to drop the inherited policy instead of carrying it forward.
        assert "execution_policy" not in v2_request
        assert v2_request["clear_execution_policy"] is True

    def test_replay__forwards_empty_user_tags_and_metadata_explicitly(
        self,
    ) -> None:
        # PR review #4 (baz-reviewer, version_replay.py:710): explicit clears
        # (``tags=[]`` or ``metadata={}`` on the source) must round-trip as
        # explicit empty values on the apply payload — truthiness gating would
        # silently collapse them to omission and the BE would inherit prior
        # values. ``_user_version_tags`` already returns None for "all tags
        # were the system 'latest' marker," so this test exercises metadata
        # specifically (where the helper layer doesn't intervene).
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(
                    id="src-v1", version_hash="hv1", metadata={"author": "ada"}
                ),
                # v2 clears metadata explicitly.
                _SourceVersion(id="src-v2", version_hash="hv2", metadata={}),
            ],
            items_per_version={
                "hv1": [_ds_item("a", x=1)],
                "hv2": [_ds_item("a", x=1), _ds_item("b", y=2)],
                "tgt-h1": [_ds_item("tgt-a", x=1)],
                "tgt-h1b": [_ds_item("tgt-a", x=1)],
                "tgt-h2": [_ds_item("tgt-a", x=1), _ds_item("tgt-b", y=2)],
            },
            applied_versions=[
                # v1: post-create read-back + version-field follow-up apply
                # (items + metadata both present on v1 forces an extra apply
                # call; that's expected and warned about in _create_first_version_with_items).
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v1b", version_hash="tgt-h1b"),
                # v2 apply.
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v2 apply is the second (last) apply call. metadata={} must be
        # forwarded explicitly so the BE drops the inherited {"author": "ada"}.
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert v2_request["metadata"] == {}

    def test_replay__duplicate_content_items_remap_to_distinct_target_ids(
        self,
    ) -> None:
        # PR review #5 (baz-reviewer, version_replay.py:766): when source v1
        # has two items with identical content (e.g. user inserted duplicates),
        # they get different stable ids on the source but identical content
        # hashes. Earlier implementation collapsed both into one entry of
        # target_items_by_hash, so both source ids remapped to the same target
        # id — corrupting any later edit/delete that referenced one of them.
        # Fix: per-hash FIFO queue so each source-add pairs with a distinct
        # target row.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
            ],
            items_per_version={
                "hv1": [
                    _ds_item("src-dupe-1", q="DUP"),
                    _ds_item("src-dupe-2", q="DUP"),
                ],
                # Target has two distinct ids for the same hash — same shape
                # the BE produces when you insert duplicate-content items.
                "tgt-h1": [
                    _ds_item("tgt-dupe-1", q="DUP"),
                    _ds_item("tgt-dupe-2", q="DUP"),
                ],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
            ],
        )

        result = replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # Both source ids must remap to DIFFERENT target ids — not collapse
        # onto the same one.
        assert "src-dupe-1" in result.item_id_remap
        assert "src-dupe-2" in result.item_id_remap
        assert result.item_id_remap["src-dupe-1"] != result.item_id_remap["src-dupe-2"]
        assert {
            result.item_id_remap["src-dupe-1"],
            result.item_id_remap["src-dupe-2"],
        } == {
            "tgt-dupe-1",
            "tgt-dupe-2",
        }

    def test_replay__forwards_per_version_suite_evaluators_and_execution_policy(
        self,
    ) -> None:
        # Pin the bug found by the test-suite e2e: each source version's
        # suite-level ``evaluators`` and ``execution_policy`` must ride on
        # that version's write. Without this, the BE would inherit the
        # last-set config across all replayed versions, collapsing suite-
        # config history onto whichever version last carried explicit
        # suite-level fields.
        #
        # Test suite shape: source v1 is config-only (zero items + suite
        # evaluators + execution_policy) — the canonical shape created by
        # ``create_test_suite(global_assertions=...)``. v1 routes through
        # ``apply_dataset_item_changes(base_version=null, override=true)``
        # carrying the suite config. v2 adds items and bumps suite config,
        # routing through standard ``apply_dataset_item_changes``.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        v1_evaluator = MagicMock()
        v1_evaluator.name = "v1-judge"
        v1_evaluator.type = "llm_judge"
        v1_evaluator.config = {"model": "haiku"}
        v1_policy = MagicMock()
        v1_policy.runs_per_item = 1
        v1_policy.pass_threshold = 1

        v2_evaluator = MagicMock()
        v2_evaluator.name = "v2-judge"
        v2_evaluator.type = "llm_judge"
        v2_evaluator.config = {"model": "opus"}
        v2_policy = MagicMock()
        v2_policy.runs_per_item = 5
        v2_policy.pass_threshold = 3

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(
                    id="src-v1",
                    version_hash="hv1",
                    evaluators=[v1_evaluator],
                    execution_policy=v1_policy,
                ),
                _SourceVersion(
                    id="src-v2",
                    version_hash="hv2",
                    evaluators=[v2_evaluator],
                    execution_policy=v2_policy,
                ),
            ],
            items_per_version={
                "hv1": [],  # config-only: no items on v1
                "hv2": [_ds_item("a", x=1)],
                "tgt-h2": [_ds_item("tgt-a", x=1)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # Two apply_dataset_item_changes calls: v1 (base_version=null,
        # override=true, config-only) and v2 (base_version=tgt-v1, items + config).
        assert rest_client.datasets.apply_dataset_item_changes.call_count == 2
        v1_call = rest_client.datasets.apply_dataset_item_changes.call_args_list[0]
        v1_request = v1_call.kwargs["request"]
        assert v1_call.kwargs["override"] is True
        assert "base_version" not in v1_request
        assert v1_request["evaluators"] == [
            {"name": "v1-judge", "type": "llm_judge", "config": {"model": "haiku"}}
        ]
        assert v1_request["execution_policy"] == {
            "runs_per_item": 1,
            "pass_threshold": 1,
        }

        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args_list[
            1
        ].kwargs["request"]
        assert v2_request["base_version"] == "tgt-v1"
        assert v2_request["evaluators"] == [
            {"name": "v2-judge", "type": "llm_judge", "config": {"model": "opus"}}
        ]
        assert v2_request["execution_policy"] == {
            "runs_per_item": 5,
            "pass_threshold": 3,
        }

    def test_replay__forwards_per_item_tags_on_add_and_edit(self) -> None:
        # Per-item tags are version-scoped on the BE — they can change across
        # versions. Verify they round-trip on both write paths.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", tags=["regression"], x=1)],
                # v2: tag list expanded.
                "hv2": [_ds_item("a", tags=["regression", "baseline"], x=1)],
                "tgt-h1": [_ds_item("tgt-a", tags=["regression"], x=1)],
                "tgt-h2": [_ds_item("tgt-a", tags=["regression", "baseline"], x=1)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v1 went through create_or_update; its payload carries tags.
        v1_items = rest_client.datasets.create_or_update_dataset_items.call_args.kwargs[
            "items"
        ]
        assert v1_items[0].tags == ["regression"]

        # v2 went through apply; its edit payload carries the new tag list.
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert v2_request["edited_items"][0]["tags"] == ["regression", "baseline"]

    def test_replay__clears_per_item_tags_when_removed(self) -> None:
        # Source v1 has an item with tags; v2's same item has no tags. The
        # BE inherits on omission, so we must explicitly send tags=[] to
        # overwrite the stale set on the target.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", tags=["regression"], x=1)],
                "hv2": [
                    _ds_item("a", tags=None, x=2)
                ],  # tags cleared, data also changed
                "tgt-h1": [_ds_item("tgt-a", tags=["regression"], x=1)],
                "tgt-h2": [_ds_item("tgt-a", tags=None, x=2)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert v2_request["edited_items"][0]["tags"] == []

    def test_replay__clears_per_item_evaluators_when_removed(self) -> None:
        # Source v1's item has per-item evaluators; v2's same item drops
        # them. Forward evaluators=[] on the edit to override.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        eval_v1 = {"name": "j1", "type": "llm_judge", "config": {"model": "haiku"}}
        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", evaluators=[eval_v1], x=1)],
                "hv2": [_ds_item("a", evaluators=None, x=2)],
                "tgt-h1": [_ds_item("tgt-a", evaluators=[eval_v1], x=1)],
                "tgt-h2": [_ds_item("tgt-a", evaluators=None, x=2)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        assert v2_request["edited_items"][0]["evaluators"] == []

    def test_replay__clears_per_item_execution_policy_when_removed(self) -> None:
        # Source v1's item has a per-item execution_policy override; v2's
        # same item drops it. Send clear_execution_policy=true (not just
        # omit the field, which would inherit the stale override).
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        pol_v1 = {"runs_per_item": 5, "pass_threshold": 3}
        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", execution_policy=pol_v1, x=1)],
                "hv2": [_ds_item("a", execution_policy=None, x=2)],
                "tgt-h1": [_ds_item("tgt-a", execution_policy=pol_v1, x=1)],
                "tgt-h2": [_ds_item("tgt-a", execution_policy=None, x=2)],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args.kwargs[
            "request"
        ]
        edited = v2_request["edited_items"][0]
        assert "execution_policy" not in edited
        assert edited["clear_execution_policy"] is True

    def test_replay__forwards_version_metadata_and_filters_latest_tag(self) -> None:
        # Version-level metadata is per-version Map<str,str>; round-trip it.
        # Version-level tags include BE's 'latest' system marker on the
        # latest version — that must be stripped (forwarding it 409s).
        # Source v1 carries items AND version-level fields, which forces a
        # follow-up apply (one extra target version) — the warning is by
        # design; the alternative would be silent N+1 surprises in prod.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(
                    id="src-v1",
                    version_hash="hv1",
                    metadata={"author": "ada"},
                    tags=["baseline"],
                ),
                _SourceVersion(
                    id="src-v2",
                    version_hash="hv2",
                    metadata={"author": "ada", "experiment": "42"},
                    tags=["baseline", "v1.0", "latest"],  # 'latest' must be filtered
                ),
            ],
            items_per_version={
                "hv1": [_ds_item("a", x=1)],
                "hv2": [_ds_item("a", x=1), _ds_item("b", y=2)],
                "tgt-h1": [_ds_item("tgt-a", x=1)],
                "tgt-h1b": [_ds_item("tgt-a", x=1)],
                "tgt-h2": [_ds_item("tgt-a", x=1), _ds_item("tgt-b", y=2)],
            },
            applied_versions=[
                # 1) post-create_or_update read-back on v1 (drives list_dataset_versions)
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                # 2) v1 follow-up apply for metadata/tags (response from apply_changes)
                _AppliedVersion(id="tgt-v1b", version_hash="tgt-h1b"),
                # 3) v2 apply (response from apply_changes)
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v1 went through create_or_update + version-field follow-up apply
        # to land the metadata + tags. Find the apply call (the only one
        # carrying base_version=tgt-init isn't possible since we don't have
        # initial; this is the v1 follow-up).
        v1_followup = rest_client.datasets.apply_dataset_item_changes.call_args_list[
            0
        ].kwargs["request"]
        assert v1_followup["metadata"] == {"author": "ada"}
        assert v1_followup["tags"] == ["baseline"]

        # v2 apply: metadata expanded; 'latest' is filtered out of tags.
        v2_request = rest_client.datasets.apply_dataset_item_changes.call_args_list[
            1
        ].kwargs["request"]
        assert v2_request["metadata"] == {"author": "ada", "experiment": "42"}
        assert v2_request["tags"] == ["baseline", "v1.0"]

    def test_replay__progress_callback_fires_once_per_version(self) -> None:
        # The CLI's Rich Progress bar needs per-version progress signals.
        # ``replay_all_versions`` must invoke the optional ``progress_callback``
        # once before each source version begins, with ``(completed_count,
        # total, version_label)``. Pin: 3 source versions → 3 callback invocations
        # with completed counts 0, 1, 2 and total = 3 every time.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1", version_name="v1"),
                _SourceVersion(id="src-v2", version_hash="hv2", version_name="v2"),
                _SourceVersion(id="src-v3", version_hash="hv3", version_name="v3"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", x=1)],
                "hv2": [_ds_item("a", x=1), _ds_item("b", y=2)],
                "hv3": [_ds_item("a", x=1), _ds_item("b", y=2), _ds_item("c", z=3)],
                "tgt-h1": [_ds_item("tgt-a", x=1)],
                "tgt-h2": [_ds_item("tgt-a", x=1), _ds_item("tgt-b", y=2)],
                "tgt-h3": [
                    _ds_item("tgt-a", x=1),
                    _ds_item("tgt-b", y=2),
                    _ds_item("tgt-c", z=3),
                ],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
                _AppliedVersion(id="tgt-v3", version_hash="tgt-h3"),
            ],
        )

        events: List[tuple] = []

        def _cb(completed: int, total: int, label: str) -> None:
            events.append((completed, total, label))

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
            progress_callback=_cb,
        )

        assert events == [(0, 3, "v1"), (1, 3, "v2"), (2, 3, "v3")]

    def test_replay__base_version_chains_across_calls(self) -> None:
        # Each apply call's base_version must be the previous response's id —
        # not a stale value, not the initial. Pin this so a refactor that
        # accidentally re-uses the initial id silently breaks here.
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client, audit = self._setup(
            source_versions=[
                _SourceVersion(id="src-v1", version_hash="hv1"),
                _SourceVersion(id="src-v2", version_hash="hv2"),
                _SourceVersion(id="src-v3", version_hash="hv3"),
            ],
            items_per_version={
                "hv1": [_ds_item("a", x=1)],
                "hv2": [_ds_item("a", x=1), _ds_item("b", y=2)],
                "hv3": [_ds_item("a", x=1), _ds_item("b", y=2), _ds_item("c", z=3)],
                "tgt-h1": [_ds_item("tgt-a", x=1)],
                "tgt-h2": [_ds_item("tgt-a", x=1), _ds_item("tgt-b", y=2)],
                "tgt-h3": [
                    _ds_item("tgt-a", x=1),
                    _ds_item("tgt-b", y=2),
                    _ds_item("tgt-c", z=3),
                ],
            },
            applied_versions=[
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
                _AppliedVersion(id="tgt-v3", version_hash="tgt-h3"),
            ],
        )

        replay_all_versions(
            rest_client,
            source_dataset_id="src-id",
            source_name_after_rename="MyDataset_v1",
            source_project_name=None,
            dest_dataset_id="tgt-dataset-id",
            dest_name="MyDataset",
            dest_project_name="B",
            audit=audit,
        )

        # v1 went through create_or_update_dataset_items, not apply_changes.
        # v2 and v3 each go through apply_changes; their base_versions chain
        # off the previous response: v2 base=tgt-v1, v3 base=tgt-v2.
        bases = [
            call.kwargs["request"]["base_version"]
            for call in rest_client.datasets.apply_dataset_item_changes.call_args_list
        ]
        assert bases == ["tgt-v1", "tgt-v2"]


class TestVersionReplayE2ECli:
    """End-to-end Click smoke test: drives the full executor plumbing
    (CreateDestination → replay loop → audit-log finalisation). With the
    Slice 2 contract that target version count == source version count, v1
    is written through ``create_or_update_dataset_items`` (when v1 has items)
    or ``apply_dataset_item_changes(base_version=null, override=true)`` (when
    v1 is config-only). Subsequent versions go through ``apply_dataset_item_changes``."""

    def test_migrate_dataset__default_path__replay_versions_executes(
        self, tmp_path: Path
    ) -> None:
        runner = CliRunner()
        client, _, dest_dataset = _build_fake_client(
            source_rows=[_DatasetRow(id="src-1", name="MyDataset")],
            destination_rows=[],
            items=[],
        )

        # Source has one version with one item.
        source_version = _SourceVersion(
            id="src-v1", version_hash="hv1", version_name="v1"
        )
        # The executor now resolves the destination via ``client.get_dataset``;
        # pin the ``.id`` so the stream / list_dataset_versions stubs below can
        # route off it.
        dest_dataset.id = "tgt-dataset-id"
        client.rest_client.datasets.create_or_update_dataset_items.return_value = None

        # list_dataset_versions is called for both the source (paginated
        # newest-first) and the target (post-create read-back of v1). Route
        # by id rather than a flat side_effect list so the order doesn't
        # depend on internal call sequencing.
        post_create_iter = iter([_AppliedVersion(id="tgt-v1", version_hash="tgt-h1")])

        def _list_versions(id: str, **kwargs: Any) -> Any:
            if id == "src-1":
                page = kwargs.get("page", 1)
                if page == 1:
                    return _Page([source_version])
                return _Page([])
            if id == "tgt-dataset-id":
                return _Page([next(post_create_iter)])
            raise AssertionError(f"unexpected list_dataset_versions id={id}")

        client.rest_client.datasets.list_dataset_versions.side_effect = _list_versions

        items_per_version: Dict[str, List[Any]] = {
            "hv1": [_ds_item("a", x=1)],
            "tgt-h1": [_ds_item("tgt-a", x=1)],
        }

        last_dataset_version: List[Optional[str]] = [None]

        def _raw_stream(*, dataset_version: Optional[str] = None, **_: Any) -> Any:
            last_dataset_version[0] = dataset_version
            return iter(())

        def _parse(stream: Any, item_class: Any, **_: Any) -> List[Any]:
            return list(items_per_version.get(last_dataset_version[0] or "", []))

        client.rest_client.datasets.stream_dataset_items.side_effect = _raw_stream

        audit_path = tmp_path / "audit.json"
        with (
            patch("opik.cli.migrate.main.opik.Opik", return_value=client),
            patch(
                "opik.cli.migrate.datasets.version_replay.rest_stream_parser.read_and_parse_stream",
                side_effect=_parse,
            ),
        ):
            result = runner.invoke(
                cli,
                [
                    "migrate",
                    "dataset",
                    "MyDataset",
                    "--to-project",
                    "B",
                    "--audit-log",
                    str(audit_path),
                ],
            )

        assert result.exit_code == 0, result.output
        # v1 went through create_or_update_dataset_items.
        client.rest_client.datasets.create_or_update_dataset_items.assert_called_once()
        # No apply_dataset_item_changes calls — source has only one version
        # and it was written via the create path.
        client.rest_client.datasets.apply_dataset_item_changes.assert_not_called()
        audit = json.loads(audit_path.read_text())
        types = [a["type"] for a in audit["actions"]]
        # Outer replay_versions ok bracket + inner replay_dataset_version
        # per-version record.
        assert "replay_versions" in types
        assert "replay_dataset_version" in types
        assert audit["status"] == "ok"

    def test_migrate_dataset__source_v1_is_config_only__no_leading_empty_version(
        self, tmp_path: Path
    ) -> None:
        # Pin the test-suite shape: source v1 is config-only (zero items,
        # suite-level evaluators + execution_policy on the version itself).
        # Replay must mint target v1 via ``apply_dataset_item_changes(
        # base_version=null, override=true)`` carrying the suite config —
        # not via a separate empty seed followed by another version. Result:
        # target version count == source version count, no extra leading
        # empty version.
        runner = CliRunner()
        client, _, dest_dataset = _build_fake_client(
            source_rows=[
                _DatasetRow(id="src-1", name="MySuite", type="evaluation_suite")
            ],
            destination_rows=[],
            items=[],
        )

        evaluator = MagicMock()
        evaluator.name = "v1-judge"
        evaluator.type = "llm_judge"
        evaluator.config = {"model": "haiku"}
        policy = MagicMock()
        policy.runs_per_item = 1
        policy.pass_threshold = 1

        source_version = _SourceVersion(
            id="src-v1",
            version_hash="hv1",
            version_name="v1",
            evaluators=[evaluator],
            execution_policy=policy,
        )
        client.rest_client.datasets.list_dataset_versions.side_effect = [
            _Page([source_version]),  # source version list (single page)
            _Page([]),  # pagination terminator
        ]
        # The executor now resolves the destination via ``client.get_dataset``;
        # pin the ``.id`` so the apply_dataset_item_changes stub below routes
        # against the same target id the executor passes through.
        dest_dataset.id = "tgt-dataset-id"
        # Exactly one apply call for v1 (config-only with base_version=null +
        # override=true). The config-only path does not need create_or_update.
        client.rest_client.datasets.apply_dataset_item_changes.side_effect = [
            _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
        ]

        items_per_version: Dict[str, List[Any]] = {
            "hv1": [],  # config-only: zero items on v1
        }

        last_dataset_version: List[Optional[str]] = [None]

        def _raw_stream(*, dataset_version: Optional[str] = None, **_: Any) -> Any:
            last_dataset_version[0] = dataset_version
            return iter(())

        def _parse(stream: Any, item_class: Any, **_: Any) -> List[Any]:
            return list(items_per_version.get(last_dataset_version[0] or "", []))

        client.rest_client.datasets.stream_dataset_items.side_effect = _raw_stream

        audit_path = tmp_path / "audit.json"
        with (
            patch("opik.cli.migrate.main.opik.Opik", return_value=client),
            patch(
                "opik.cli.migrate.datasets.version_replay.rest_stream_parser.read_and_parse_stream",
                side_effect=_parse,
            ),
        ):
            result = runner.invoke(
                cli,
                [
                    "migrate",
                    "dataset",
                    "MySuite",
                    "--to-project",
                    "B",
                    "--audit-log",
                    str(audit_path),
                ],
            )

        assert result.exit_code == 0, result.output
        # Exactly one apply call (v1, config-only).
        calls = client.rest_client.datasets.apply_dataset_item_changes.call_args_list
        assert len(calls) == 1
        v1_kwargs = calls[0].kwargs
        assert v1_kwargs["override"] is True
        v1_request = v1_kwargs["request"]
        assert "base_version" not in v1_request
        assert "added_items" not in v1_request
        assert v1_request["evaluators"] == [
            {"name": "v1-judge", "type": "llm_judge", "config": {"model": "haiku"}}
        ]
        assert v1_request["execution_policy"] == {
            "runs_per_item": 1,
            "pass_threshold": 1,
        }
        # No create_or_update_dataset_items call — v1 has no items to insert.
        client.rest_client.datasets.create_or_update_dataset_items.assert_not_called()


class TestVersionReplayAuditLog:
    """Per-version audit records — one ``replay_dataset_version`` entry per
    replayed source version, plus per-version add/modify/delete counts."""

    def test_replay__per_version_audit_records_emitted(self) -> None:
        from opik.cli.migrate.datasets.version_replay import replay_all_versions

        rest_client = MagicMock()

        source_versions = [
            _SourceVersion(id="src-v1", version_hash="hv1"),
            _SourceVersion(id="src-v2", version_hash="hv2"),
        ]
        applied_iter = iter(
            [
                _AppliedVersion(id="tgt-v1", version_hash="tgt-h1"),
                _AppliedVersion(id="tgt-v2", version_hash="tgt-h2"),
            ]
        )

        def _list_versions(id: str, **kwargs: Any) -> Any:
            if id == "src-id":
                return _Page(list(reversed(source_versions)))
            if id == "tgt-dataset-id":
                # Post-v1 read-back: peel one applied_versions entry.
                return _Page([next(applied_iter)])
            raise AssertionError(f"unexpected list_dataset_versions id={id}")

        rest_client.datasets.list_dataset_versions.side_effect = _list_versions
        rest_client.datasets.apply_dataset_item_changes.side_effect = (
            lambda *a, **kw: next(applied_iter)
        )
        rest_client.datasets.create_or_update_dataset_items.return_value = None

        items_per_version: Dict[str, List[Any]] = {
            "hv1": [_ds_item("a", x=1)],
            "hv2": [_ds_item("a", x=99), _ds_item("b", y=2)],
            "tgt-h1": [_ds_item("tgt-a", x=1)],
            "tgt-h2": [_ds_item("tgt-a", x=99), _ds_item("tgt-b", y=2)],
        }

        last_dataset_version: List[Optional[str]] = [None]

        def _raw_stream(*, dataset_version: Optional[str] = None, **_: Any) -> Any:
            last_dataset_version[0] = dataset_version
            return iter(())

        def _parse(stream: Any, item_class: Any, **_: Any) -> List[Any]:
            return list(items_per_version.get(last_dataset_version[0] or "", []))

        rest_client.datasets.stream_dataset_items.side_effect = _raw_stream

        with patch(
            "opik.cli.migrate.datasets.version_replay.rest_stream_parser.read_and_parse_stream",
            side_effect=_parse,
        ):
            audit = AuditLog(command="opik migrate dataset", args={})
            replay_all_versions(
                rest_client,
                source_dataset_id="src-id",
                source_name_after_rename="MyDataset_v1",
                source_project_name=None,
                dest_dataset_id="tgt-dataset-id",
                dest_name="MyDataset",
                dest_project_name="B",
                audit=audit,
            )

        # AuditLog.record() flattens ``details`` into the entry, so version
        # counts and id linkages live at the top level of the record.
        per_version_records = [
            r for r in audit.actions if r["type"] == "replay_dataset_version"
        ]
        assert len(per_version_records) == 2
        # First version: 1 add, 0 mods, 0 dels.
        assert per_version_records[0]["items_added"] == 1
        assert per_version_records[0]["items_modified"] == 0
        assert per_version_records[0]["items_deleted"] == 0
        # Second version: 1 add (b), 1 mod (a), 0 dels.
        assert per_version_records[1]["items_added"] == 1
        assert per_version_records[1]["items_modified"] == 1
        assert per_version_records[1]["items_deleted"] == 0
        # Source/target version-id linkage recorded.
        assert per_version_records[0]["source_version_id"] == "src-v1"
        assert per_version_records[0]["target_version_id"] == "tgt-v1"
        assert per_version_records[1]["source_version_id"] == "src-v2"
        assert per_version_records[1]["target_version_id"] == "tgt-v2"
