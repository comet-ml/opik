"""Version-history replay for ``opik migrate dataset``.

This module deliberately stays on the low-level ``OpikApi`` (Fern) client
rather than the high-level ``opik.Opik`` SDK surface: the cascade elsewhere
(``experiments.py``) routes writes through the high-level streamer, but
version replay needs the raw BE shapes that the high-level ``Dataset``
helper hides — ``apply_dataset_item_changes`` with ``base_version`` chaining,
``override=true`` for the config-only v1 path, ``batch_group_id`` on
``create_or_update_dataset_items``, ``clear_execution_policy``,
per-item evaluators / execution_policy, paginated ``list_dataset_versions``,
and the raw item stream that preserves ``tags`` (the SDK-level
``stream_dataset_items`` helper silently drops them during dataclass
reconstruction). Every site is still rate-limit-wrapped via
``ensure_rest_api_call_respecting_rate_limit``.

Slice 2's algorithmic core: enumerate source dataset versions in chronological
order and, for each, compute the (adds, modifications, deletions) delta
against the previous version and apply it via ``apply_dataset_item_changes``
on the target. The starting target state is whatever the executor placed
before this step ran:

* plain datasets — the empty initial version created by ``CreateDestination``
* test suites — the suite-config-only initial version produced by
  ``CopyTestSuiteConfig``

In both cases the first call passes that version's id as ``base_version``,
and every subsequent call passes the previous response's new version id.

Item identity is the BE's stable ``DatasetItem.id``, which the BE guarantees
remains constant across versions of the same dataset (see
``DatasetItemEdit`` schema: "Stable dataset item identifier. Remains the
same across dataset versions"). Source-side identity is therefore directly
usable as the diff key. Target-side ids are minted fresh by the BE on every
add, so we read them back after each apply call to populate
``item_id_remap[old_id] = new_id`` for Slice 3's experiment FK remap.
"""

from __future__ import annotations

import hashlib
import json
import logging
import sys
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Set

from opik.api_objects import rest_helpers, rest_stream_parser
from opik.rest_api import OpikApi
from opik.rest_api.types import (
    dataset_item_public,
    dataset_version_public,
    evaluator_item_public,
    execution_policy_public,
)

from ..audit import AuditLog
from ..errors import ReplayError

LOGGER = logging.getLogger(__name__)

# Page size for ``list_dataset_versions``. The endpoint orders newest-first;
# we paginate to exhaustion and reverse to chronological order before replay.
_VERSIONS_PAGE_SIZE = 100

# Maximum ``items`` per ``create_or_update_dataset_items`` POST. The BE
# enforces ``DatasetItemBatch.@Size(max=1000)``; oversized payloads are
# rejected with HTTP 422. ``_mint_v1`` therefore chunks the source-v1
# items into batches of ``_DATASET_ITEMS_INSERT_BATCH_SIZE`` and sends
# each batch under the SAME ``batch_group_id`` so the BE rolls them
# into ONE target version (per ``DatasetItemService.applyToLatestVersion``:
# "If batchGroupId is provided: Creates a new version with batch grouping
# (multiple batches can share the same version)").
_DATASET_ITEMS_INSERT_BATCH_SIZE = 1000


@dataclass(frozen=True)
class _SourceItem:
    """Snapshot of a source-version item for diff purposes.

    We hold the full wire-type ``DatasetItemPublic`` (not the SDK-level
    ``DatasetItem`` dataclass) because the SDK dataclass silently drops
    ``tags`` during stream reconstruction. Reading directly from the REST
    wire type preserves every field the BE persists — tags, evaluators,
    execution_policy, description, source, trace_id, span_id — so the
    migration is full-fidelity.
    """

    id: str
    content_hash: str
    item: dataset_item_public.DatasetItemPublic


@dataclass
class _Modification:
    """A modification carries both the new and the previous snapshots.

    The previous snapshot is needed to detect "clears": when ``prev`` had a
    non-None ``execution_policy`` or ``evaluators`` and ``curr`` has it as
    ``None``, the user removed the per-item override. The BE's edit-merge
    semantics inherit when a field is omitted, so we have to forward an
    explicit "clear" signal (``clear_execution_policy=true`` for policy,
    ``evaluators=[]`` for evaluators) to reproduce that intent on the target.
    """

    curr: dataset_item_public.DatasetItemPublic
    prev: dataset_item_public.DatasetItemPublic


@dataclass
class VersionDelta:
    adds: List[dataset_item_public.DatasetItemPublic] = field(default_factory=list)
    modifications: List[_Modification] = field(default_factory=list)
    deletions: List[str] = field(default_factory=list)

    def is_empty(self) -> bool:
        return not (self.adds or self.modifications or self.deletions)


@dataclass
class ReplayResult:
    """Outcome of a full replay loop.

    Slice 3 will read these maps to remap experiment FK references. The
    executor exposes them on ``MigrationPlan`` after the run.
    """

    version_remap: Dict[str, str] = field(default_factory=dict)
    item_id_remap: Dict[str, str] = field(default_factory=dict)
    versions_replayed: int = 0


def replay_all_versions(
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    source_name_after_rename: str,
    source_project_name: Optional[str],
    dest_dataset_id: str,
    dest_name: str,
    dest_project_name: str,
    audit: AuditLog,
    progress_callback: Optional[Callable[[int, int, str], None]] = None,
) -> ReplayResult:
    """Replay every source version onto the destination in chronological order.

    The destination is expected to have **zero** versions when this runs (the
    executor's ``CreateDestination`` action makes a dataset shell with no
    versions). The first source version is therefore written through a
    "create v1" path that produces a single target version matching the
    source's v1 shape — content-bearing or config-only — so target version
    count == source version count and there's no leading empty seed.

    Subsequent versions go through ``apply_dataset_item_changes`` chained
    off the previous response's id.

    Each version is its own audited record so the on-disk audit log carries
    one entry per replayed version (the per-version progress requirement
    from the ticket). The outer ``replay_versions`` action's audit
    bracketing is handled by the executor.

    ``progress_callback`` is invoked once before each version begins with
    ``(completed_count, total_versions, source_version_label)`` so callers
    can drive a progress bar. Keeping the UI concern in the callback (not
    in this module) means tests don't have to stub Rich; the executor owns
    the live progress display.
    """
    versions = list(_iter_source_versions_chronological(rest_client, source_dataset_id))
    result = ReplayResult()

    if not versions:
        # Source has no committed versions — nothing to replay. The target
        # is left as the empty shell produced by CreateDestination.
        return result

    total = len(versions)
    prev_items_by_id: Dict[str, _SourceItem] = {}
    prev_suite_execution_policy: Optional[
        execution_policy_public.ExecutionPolicyPublic
    ] = None
    base_version_id: Optional[str] = None

    for index, source_version in enumerate(versions):
        if progress_callback is not None:
            label = source_version.version_name or f"v{index + 1}"
            progress_callback(index, total, label)
        curr_items_by_id = _load_version_items(
            rest_client,
            source_name_after_rename=source_name_after_rename,
            source_project_name=source_project_name,
            version_hash=source_version.version_hash,
        )

        if index == 0:
            new_version_id, new_item_ids, delta = _replay_first_version(
                rest_client,
                dest_dataset_id=dest_dataset_id,
                dest_name=dest_name,
                dest_project_name=dest_project_name,
                source_items=curr_items_by_id,
                source_version=source_version,
            )
        else:
            assert base_version_id is not None  # set after the first iteration
            delta = _compute_delta(prev_items_by_id, curr_items_by_id)
            # When source drops suite-level execution_policy between versions
            # (prev had one, curr=None), forward the BE's dedicated clear
            # flag — omission would otherwise inherit the stale policy.
            clear_suite_execution_policy = (
                prev_suite_execution_policy is not None
                and source_version.execution_policy is None
            )
            new_version_id, new_item_ids = _apply_delta_and_collect_new_ids(
                rest_client,
                dest_dataset_id=dest_dataset_id,
                dest_name=dest_name,
                dest_project_name=dest_project_name,
                base_version_id=base_version_id,
                delta=delta,
                change_description=source_version.change_description,
                item_id_remap=result.item_id_remap,
                suite_evaluators=source_version.evaluators,
                suite_execution_policy=source_version.execution_policy,
                clear_suite_execution_policy=clear_suite_execution_policy,
                user_tags=_user_version_tags(source_version.tags),
                metadata=source_version.metadata,
            )

        # Map the source version id to the new target version id. Slice 3
        # reads this via ``MigrationPlan.version_remap``.
        if source_version.id is not None:
            result.version_remap[source_version.id] = new_version_id

        # Adds get fresh target ids that we recover by content-hash matching
        # the post-apply target items. ``new_item_ids`` is keyed by content
        # hash → ordered queue of target ids; we pop one per source-add so
        # items with identical content (e.g. duplicate-content inserts) each
        # remap to a distinct target row instead of collapsing.
        for added in delta.adds:
            if added.id is None:
                continue
            queue = new_item_ids.get(_content_hash_for(added))
            if queue:
                result.item_id_remap[added.id] = queue.pop(0)

        result.versions_replayed += 1

        audit.record(
            type="replay_dataset_version",
            status="ok",
            details={
                "type": "replay_dataset_version",
                "source_version_id": source_version.id,
                "source_version_name": source_version.version_name,
                "target_version_id": new_version_id,
                "items_added": len(delta.adds),
                "items_modified": len(delta.modifications),
                "items_deleted": len(delta.deletions),
            },
        )

        prev_items_by_id = curr_items_by_id
        prev_suite_execution_policy = source_version.execution_policy
        base_version_id = new_version_id

    return result


def _replay_first_version(
    rest_client: OpikApi,
    *,
    dest_dataset_id: str,
    dest_name: str,
    dest_project_name: str,
    source_items: Dict[str, _SourceItem],
    source_version: dataset_version_public.DatasetVersionPublic,
) -> tuple[str, Dict[str, List[str]], "VersionDelta"]:
    """Create the destination's v1 to match the source's v1 shape.

    Two BE-imposed write paths, picked by the source v1's shape:

    * **Source v1 carries items** (the typical plain-dataset case from
      ``Dataset.insert(items)``): use ``create_or_update_dataset_items``
      with a ``batch_group_id`` to mint v1 in a single BE-internal version.
      Same write path that produced the source's own v1, so the resulting
      target v1 is structurally identical.
    * **Source v1 is config-only** (the typical test-suite case from
      ``create_test_suite``): use ``apply_dataset_item_changes`` with
      ``base_version=null + override=true`` carrying the suite-level
      evaluators / execution_policy. The BE accepts this only when the
      payload contains zero items (see ``DatasetItemService.applyDeltaChanges``
      around line 1408 — content with ``base_version=null`` is rejected).

    Returns a ``(new_version_id, hash_to_target_item_id, delta)`` triple
    matching the shape of ``_apply_delta_and_collect_new_ids`` so the caller
    can populate ``item_id_remap`` and audit deltas uniformly.
    """
    # Synthesize a delta whose ``adds`` matches the source v1 in stream
    # order. Modifications and deletions are vacuous on v1 — there's no
    # prior version on either side to diff against. The delta is mostly
    # cosmetic for v1 (it powers the audit log's per-version counts).
    delta = VersionDelta(adds=[s.item for s in source_items.values()])

    user_tags = _user_version_tags(source_version.tags)
    metadata = source_version.metadata

    if delta.adds:
        return (
            *_create_first_version_with_items(
                rest_client,
                dest_dataset_id=dest_dataset_id,
                dest_name=dest_name,
                dest_project_name=dest_project_name,
                adds=delta.adds,
                change_description=source_version.change_description,
                suite_evaluators=source_version.evaluators,
                suite_execution_policy=source_version.execution_policy,
                user_tags=user_tags,
                metadata=metadata,
            ),
            delta,
        )

    return (
        *_create_first_version_config_only(
            rest_client,
            dest_dataset_id=dest_dataset_id,
            change_description=source_version.change_description,
            suite_evaluators=source_version.evaluators,
            suite_execution_policy=source_version.execution_policy,
            user_tags=user_tags,
            metadata=metadata,
        ),
        delta,
    )


def _create_first_version_with_items(
    rest_client: OpikApi,
    *,
    dest_dataset_id: str,
    dest_name: str,
    dest_project_name: str,
    adds: List[dataset_item_public.DatasetItemPublic],
    change_description: Optional[str],
    suite_evaluators: Optional[List[evaluator_item_public.EvaluatorItemPublic]],
    suite_execution_policy: Optional[execution_policy_public.ExecutionPolicyPublic],
    user_tags: Optional[List[str]],
    metadata: Optional[Dict[str, str]],
) -> tuple[str, Dict[str, List[str]]]:
    """Create v1 via the ``create_or_update_dataset_items`` path.

    The BE rejects content-bearing applies with ``base_version=null``, so
    we mint v1 the same way ``Dataset.insert`` does: a single
    ``create_or_update_dataset_items`` call with a fresh ``batch_group_id``
    grouping all items under one new version.

    Items are sent in REVERSE source-stream order so the target's
    ``ORDER BY id DESC`` display matches the source's. Mirrors
    ``_apply_delta_and_collect_new_ids``'s rationale (different write path,
    same UUID-pool reasoning).

    If the source v1 also carried suite-level evaluators / execution_policy,
    we follow the items insert with a no-op
    ``apply_dataset_item_changes`` whose only payload is the suite config,
    chained off the v1 we just minted. That keeps target v1's suite-level
    fields aligned with source v1's. (Test suites don't hit this branch —
    their v1 has zero items by BE convention — but plain datasets that
    somehow grew suite-level fields on v1 still round-trip correctly.)
    """
    from opik import id_helpers
    from opik.message_processing.batching import sequence_splitter
    from opik.rest_api.types.dataset_item_write import DatasetItemWrite

    batch_group_id = id_helpers.generate_id()
    payloads = [
        DatasetItemWrite(**_added_item_payload(item)) for item in reversed(adds)
    ]
    # Chunk at the BE's ``DatasetItemBatch.@Size(max=1000)`` cap. All chunks
    # share ``batch_group_id`` so the BE rolls them into ONE target version
    # rather than minting one per chunk; without chunking, a source v1 with
    # >1000 items (legal -- ``apply_dataset_item_changes`` has no @Size cap
    # and produces such versions) was rejected with HTTP 422 by the target.
    item_batches = sequence_splitter.split_into_batches(
        payloads,
        max_length=_DATASET_ITEMS_INSERT_BATCH_SIZE,
    )
    for batch in item_batches:
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda b=batch: rest_client.datasets.create_or_update_dataset_items(
                dataset_id=dest_dataset_id,
                items=b,
                batch_group_id=batch_group_id,
            )
        )

    # No structured response — list_dataset_versions to find the new v1.
    new_version = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: rest_client.datasets.list_dataset_versions(
            id=dest_dataset_id, page=1, size=1
        )
    )
    if not new_version.content or new_version.content[0].id is None:
        raise ReplayError(
            f"Failed to read back v1 for destination dataset '{dest_name}': "
            "list_dataset_versions returned empty content."
        )
    new_version_id = new_version.content[0].id
    new_version_hash = new_version.content[0].version_hash

    # Optional follow-up: if v1 should carry version-level fields that
    # ``create_or_update_dataset_items`` can't set (suite-level evaluators,
    # execution_policy, user version tags, metadata), apply them as a
    # zero-content change against this version. The BE allows
    # zero-content applies with a non-null base_version, so this stages
    # the version-level fields without minting another items-bearing version.
    # ``is not None`` gating (not truthiness): an explicit ``metadata={}`` or
    # ``user_tags=[]`` on the source means "clear the version-level field" and
    # must round-trip to the target. ``bool({})`` / ``bool([])`` would treat
    # those as "absent" and silently skip the follow-up, diverging from the
    # delta path which already uses ``is not None`` (see lines 409-412).
    needs_version_field_followup = (
        suite_evaluators is not None
        or suite_execution_policy is not None
        or user_tags is not None
        or metadata is not None
    )
    if needs_version_field_followup:
        # This adds a second target version that the source didn't have —
        # target ends up with N+1 versions for plain datasets that carried
        # version-level fields on v1. In practice this only fires for
        # unusual shapes: real plain datasets created via Dataset.insert
        # don't carry suite-level config or user version tags on v1. Test
        # suites take the config-only path below, not this branch. We log
        # so the operator notices.
        LOGGER.warning(
            "Source v1 of dataset %r carries items AND version-level fields "
            "(suite-config / tags / metadata); the target will end up with an "
            "extra version reflecting the staged version-level update.",
            dest_name,
        )
        request: Dict[str, Any] = {"base_version": new_version_id}
        if change_description:
            request["change_description"] = change_description
        if suite_evaluators is not None:
            request["evaluators"] = _evaluators_payload(suite_evaluators)
        if suite_execution_policy is not None:
            request["execution_policy"] = _execution_policy_payload(
                suite_execution_policy
            )
        # ``is not None`` gating — see _apply_delta_and_collect_new_ids for rationale.
        if user_tags is not None:
            request["tags"] = list(user_tags)
        if metadata is not None:
            request["metadata"] = dict(metadata)
        suite_version = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.datasets.apply_dataset_item_changes(
                id=dest_dataset_id, request=request, override=False
            )
        )
        if suite_version.id is None:
            raise ReplayError(
                "Failed to apply v1 version-level fields to destination "
                f"dataset '{dest_name}'."
            )
        new_version_id = suite_version.id
        new_version_hash = suite_version.version_hash

    target_items_by_hash = _read_back_target_items(
        rest_client,
        dest_name=dest_name,
        dest_project_name=dest_project_name,
        version_hash=new_version_hash,
    )
    return new_version_id, target_items_by_hash


def _create_first_version_config_only(
    rest_client: OpikApi,
    *,
    dest_dataset_id: str,
    change_description: Optional[str],
    suite_evaluators: Optional[List[evaluator_item_public.EvaluatorItemPublic]],
    suite_execution_policy: Optional[execution_policy_public.ExecutionPolicyPublic],
    user_tags: Optional[List[str]],
    metadata: Optional[Dict[str, str]],
) -> tuple[str, Dict[str, List[str]]]:
    """Create v1 via ``apply_dataset_item_changes(base_version=null, override=true)``.

    Used when the source's v1 is the BE's canonical "config-only" first
    version (test suites created by ``create_test_suite(global_assertions=...)``).
    The BE accepts a ``base_version=null + override=true`` payload only when
    it has zero items — this branch carries only version-level fields
    (suite-level evaluators / execution_policy, user tags, metadata) plus
    change_description.
    """
    request: Dict[str, Any] = {}
    if change_description:
        request["change_description"] = change_description
    if suite_evaluators is not None:
        request["evaluators"] = _evaluators_payload(suite_evaluators)
    if suite_execution_policy is not None:
        request["execution_policy"] = _execution_policy_payload(suite_execution_policy)
    # ``is not None`` gating — see _apply_delta_and_collect_new_ids for rationale.
    if user_tags is not None:
        request["tags"] = list(user_tags)
    if metadata is not None:
        request["metadata"] = dict(metadata)
    new_version = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: rest_client.datasets.apply_dataset_item_changes(
            id=dest_dataset_id, request=request, override=True
        )
    )
    if new_version.id is None:
        raise ReplayError("apply_dataset_item_changes returned a v1 without an id.")
    # No items on v1 → empty hash-to-target-id map.
    return new_version.id, {}


def _iter_source_versions_chronological(
    rest_client: OpikApi,
    dataset_id: str,
) -> List[dataset_version_public.DatasetVersionPublic]:
    """List every version of ``dataset_id`` in chronological order.

    ``list_dataset_versions`` returns newest-first; we paginate to exhaustion
    and reverse so replay applies them oldest-first.
    """
    collected: List[dataset_version_public.DatasetVersionPublic] = []
    page_idx = 1
    while True:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda p=page_idx: rest_client.datasets.list_dataset_versions(
                id=dataset_id, page=p, size=_VERSIONS_PAGE_SIZE
            )
        )
        if not page.content:
            break
        collected.extend(page.content)
        if len(page.content) < _VERSIONS_PAGE_SIZE:
            break
        page_idx += 1
    collected.reverse()
    return collected


def _stream_version_items_raw(
    rest_client: OpikApi,
    *,
    dataset_name: str,
    project_name: Optional[str],
    version_hash: Optional[str],
) -> Any:
    """Rate-limit-wrapped streaming + parsing of one dataset version's items.

    Returns an iterable of ``DatasetItemPublic`` (the raw wire type).
    Shared by ``_load_version_items`` (source side, keyed by id) and
    ``_read_back_target_items`` (target side, keyed by content hash);
    callers handle their own post-processing.

    Goes through the raw REST stream (yielding ``DatasetItemPublic``)
    instead of the SDK-level helper (``rest_operations.stream_dataset_items``)
    because the SDK helper drops ``tags`` during dataclass reconstruction.
    Going direct to the wire type preserves every persisted field for the
    diff and the write payload.

    Paginates via ``read_and_parse_full_stream`` because the BE caps each
    ``/items/stream`` response at 2000 items (``DatasetItemStreamRequest.
    steamLimit``'s ``@Max(2000)`` + default-2000). Without pagination, any
    version with >2000 items was silently truncated and ``_compute_delta``
    misclassified the missing tail as deletions — every subsequent source
    version then appeared to "delete" 500 items relative to the previous,
    leaving the target stuck at 2000.
    """

    def _fetch_page(batch_size: int, last_retrieved_id: Optional[str]) -> Any:
        return rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.datasets.stream_dataset_items(
                dataset_name=dataset_name,
                project_name=project_name,
                dataset_version=version_hash,
                last_retrieved_id=last_retrieved_id,
                steam_limit=batch_size,
            )
        )

    return rest_stream_parser.read_and_parse_full_stream(
        read_source=_fetch_page,
        parsed_item_class=dataset_item_public.DatasetItemPublic,
        max_results=sys.maxsize,
    )


def _load_version_items(
    rest_client: OpikApi,
    *,
    source_name_after_rename: str,
    source_project_name: Optional[str],
    version_hash: Optional[str],
) -> Dict[str, _SourceItem]:
    """Stream every item at ``version_hash`` and key by stable id."""
    items = _stream_version_items_raw(
        rest_client,
        dataset_name=source_name_after_rename,
        project_name=source_project_name,
        version_hash=version_hash,
    )
    by_id: Dict[str, _SourceItem] = {}
    for item in items:
        if item.id is None:
            # Defensive — the BE always returns an id on read; if one's
            # missing skip it rather than poison the diff with None keys.
            continue
        by_id[item.id] = _SourceItem(
            id=item.id, content_hash=_content_hash_for(item), item=item
        )
    return by_id


def _evaluators_payload(
    evaluators: List[evaluator_item_public.EvaluatorItemPublic],
) -> List[Dict[str, Any]]:
    """Convert a wire-type evaluator list to its write-payload shape.

    The BE uses the same ``EvaluatorItemPublic`` wire type at both the
    version level (suite evaluators) and the item level (per-item
    evaluator overrides), so this helper serves all five sites:
    ``_copy_test_suite_config`` (Slice 1), the three
    ``version_replay`` apply sites (Slice 2), and the per-item
    ``_added_item_payload`` / ``_edited_item_payload`` (Slice 2).

    Gating ("when do we forward this field") is left to each caller —
    Slice 1's ``_copy_test_suite_config`` uses truthy gating
    (``if evaluators:``) while Slice 2 uses ``is not None``. The only
    shared concern is the field-by-field wire shape.

    Intentionally NOT used by ``_content_hash_for`` — the hash function
    builds an in-memory dict for sha256, not a wire payload. Coupling
    them would silently rehash every item if a future BE field is added
    to the wire payload, breaking idempotency on re-runs.
    """
    return [{"name": e.name, "type": e.type, "config": e.config} for e in evaluators]


def _execution_policy_payload(
    execution_policy: execution_policy_public.ExecutionPolicyPublic,
) -> Dict[str, int]:
    """Convert a wire-type execution_policy to its write-payload shape.

    See ``_evaluators_payload`` for shared-vs-not-shared rationale —
    same applies here.
    """
    return {
        "runs_per_item": execution_policy.runs_per_item,
        "pass_threshold": execution_policy.pass_threshold,
    }


_BE_MANAGED_VERSION_TAGS = frozenset({"latest"})


def _user_version_tags(tags: Optional[List[str]]) -> Optional[List[str]]:
    """Strip BE-managed version tags from a source version's tag list.

    The BE attaches ``'latest'`` to whichever version is current; forwarding
    it onto a previously-replayed version would 409 because the marker
    already lives on a different (the actual latest) target version. User
    tags like ``['baseline', 'v1.0']`` are otherwise valid and must round-trip.
    """
    if tags is None:
        return None
    filtered = [t for t in tags if t not in _BE_MANAGED_VERSION_TAGS]
    return filtered or None


def _content_hash_for(item: dataset_item_public.DatasetItemPublic) -> str:
    """Stable content hash including every field the BE persists per item.

    Mirrors the SDK dataclass ``content_hash`` but adds ``tags``,
    ``trace_id``, ``span_id``, and ``source`` — fields the BE accepts on
    write and exposes on read. Pinning all of them in the hash means the
    diff catches any field-level change, not just data + description +
    evaluators + execution_policy.
    """
    content: Dict[str, Any] = {"data": dict(item.data) if item.data else {}}
    if item.description is not None:
        content["description"] = item.description
    if item.tags is not None:
        # Sort tags so logically equal sets hash identically regardless of
        # the order the BE returned them. Tag membership is what matters,
        # not order.
        content["tags"] = sorted(item.tags)
    if item.evaluators is not None:
        content["evaluators"] = [
            {"name": e.name, "type": e.type, "config": e.config}
            for e in item.evaluators
        ]
    if item.execution_policy is not None:
        content["execution_policy"] = {
            "runs_per_item": item.execution_policy.runs_per_item,
            "pass_threshold": item.execution_policy.pass_threshold,
        }
    if item.trace_id is not None:
        content["trace_id"] = item.trace_id
    if item.span_id is not None:
        content["span_id"] = item.span_id
    if item.source is not None:
        content["source"] = item.source
    return hashlib.sha256(
        json.dumps(content, sort_keys=True, default=str).encode()
    ).hexdigest()


def _compute_delta(
    prev: Dict[str, _SourceItem],
    curr: Dict[str, _SourceItem],
) -> VersionDelta:
    """Diff two version snapshots keyed by stable id.

    * ``adds`` — ids in ``curr`` but not ``prev``
    * ``deletions`` — ids in ``prev`` but not ``curr``
    * ``modifications`` — ids in both, content_hash differs

    Iteration order matters for adds and modifications because the BE
    assigns row UUIDs to each apply payload in list order, and the SDK
    streams items newest-first under ``ORDER BY id DESC``. Walking ``curr``
    and ``prev`` in their streamed order (which dicts preserve since 3.7)
    keeps the diff deterministic. The send-side reversal that maps display
    order onto BE UUID-pool order happens in ``_apply_delta_and_collect_new_ids``.
    """
    delta = VersionDelta()
    prev_ids: Set[str] = set(prev)
    curr_ids: Set[str] = set(curr)

    for curr_id, src_item in curr.items():
        if curr_id not in prev_ids:
            delta.adds.append(src_item.item)
        elif prev[curr_id].content_hash != src_item.content_hash:
            # Modifications carry both snapshots — the edit-payload builder
            # needs the previous one to detect cleared overrides.
            delta.modifications.append(
                _Modification(curr=src_item.item, prev=prev[curr_id].item)
            )

    for prev_id in prev:
        if prev_id not in curr_ids:
            delta.deletions.append(prev_id)

    return delta


def _apply_delta_and_collect_new_ids(
    rest_client: OpikApi,
    *,
    dest_dataset_id: str,
    dest_name: str,
    dest_project_name: str,
    base_version_id: str,
    delta: VersionDelta,
    change_description: Optional[str],
    item_id_remap: Dict[str, str],
    suite_evaluators: Optional[List[evaluator_item_public.EvaluatorItemPublic]],
    suite_execution_policy: Optional[execution_policy_public.ExecutionPolicyPublic],
    clear_suite_execution_policy: bool,
    user_tags: Optional[List[str]],
    metadata: Optional[Dict[str, str]],
) -> tuple[str, Dict[str, List[str]]]:
    """Apply one source version's delta to the destination.

    Returns ``(new_target_version_id, hash_to_target_item_id)`` where the
    second element maps content_hash -> newly-minted target item id, used
    to populate ``item_id_remap`` for the items we just added.

    ``item_id_remap`` is the running source-id → target-id map built up
    across previously-replayed versions. We use it to translate edit/delete
    payload ids: the BE assigns fresh stable ids on add, so source ids
    referenced in subsequent versions' modifications/deletions don't exist
    on the target — translation through the remap is what makes the BE's
    id-keyed edit/delete dispatch hit the right target rows.

    ``suite_evaluators`` / ``suite_execution_policy`` are version-level
    test-suite config (``EvaluatorItemPublic`` / ``ExecutionPolicyPublic``
    instances on each ``DatasetVersionPublic``). They're forwarded for
    every version, including plain datasets where they are typically
    ``None`` and the BE just inherits from the previous version. Per-item
    evaluators/policy are forwarded separately by the item payload builders.

    ``user_tags`` are the source version's user-applied tags with the BE's
    system-managed ``'latest'`` marker filtered out — forwarding it would
    409 because the marker already lives on a different (the actual latest)
    target version. Per-item tags are forwarded separately by the item
    payload builders.

    ``metadata`` is the source version's user-defined ``Map<String, String>``
    metadata blob, forwarded verbatim.
    """
    request: Dict[str, Any] = {"base_version": base_version_id}

    # Display-order preservation: the source streamed adds/modifications
    # newest-first (ClickHouse ``ORDER BY id DESC`` over UUIDv7), and the BE
    # assigns UUIDs to each apply payload in list order with later list
    # entries getting larger UUIDs. To reproduce the source's display order
    # on the target we send each list in REVERSE so the newest source item
    # ends up with the largest target UUID and lands at the top of its pool
    # in the target's display. This mirrors Slice 1's reversal in
    # ``executor._copy_items`` (same rationale, different write path).
    # Deletion order does not affect display.
    if delta.adds:
        request["added_items"] = [
            _added_item_payload(item) for item in reversed(delta.adds)
        ]
    if delta.modifications:
        request["edited_items"] = [
            _edited_item_payload(modification, item_id_remap)
            for modification in reversed(delta.modifications)
        ]
    if delta.deletions:
        request["deleted_ids"] = [
            item_id_remap.get(source_id, source_id) for source_id in delta.deletions
        ]
    if change_description:
        request["change_description"] = change_description
    if suite_evaluators is not None:
        # Version-level evaluators (suite-level config). Pass an empty list
        # explicitly when the source version has zero suite-level evaluators
        # so the BE doesn't inherit a stale set from the previous version.
        request["evaluators"] = _evaluators_payload(suite_evaluators)
    if suite_execution_policy is not None:
        request["execution_policy"] = _execution_policy_payload(suite_execution_policy)
    elif clear_suite_execution_policy:
        # Source dropped the suite-level policy between versions. BE
        # omission would inherit the stale value from base_version, so
        # forward the dedicated clear flag to remove it cleanly.
        request["clear_execution_policy"] = True
    # ``is not None`` gating (not truthiness) so explicit clears — empty
    # tag list / empty metadata dict — round-trip as omission-of-omission.
    # Truthiness gating would silently collapse ``[]``/`{}` to "don't forward,"
    # leaving the BE to inherit the previous version's values.
    if user_tags is not None:
        request["tags"] = list(user_tags)
    if metadata is not None:
        request["metadata"] = dict(metadata)

    new_version = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: rest_client.datasets.apply_dataset_item_changes(
            id=dest_dataset_id, request=request, override=False
        )
    )
    new_version_id = new_version.id
    if new_version_id is None:
        # The BE always returns an id on success; treat absence as a transport
        # bug rather than a recoverable case. Failing fast is preferable to
        # stranding the version_remap with None values.
        raise ReplayError(
            "apply_dataset_item_changes returned a version without an id; "
            "cannot continue replay."
        )

    if not delta.adds:
        return new_version_id, {}

    target_items_by_hash = _read_back_target_items(
        rest_client,
        dest_name=dest_name,
        dest_project_name=dest_project_name,
        version_hash=new_version.version_hash,
    )
    return new_version_id, target_items_by_hash


def _read_back_target_items(
    rest_client: OpikApi,
    *,
    dest_name: str,
    dest_project_name: str,
    version_hash: Optional[str],
) -> Dict[str, List[str]]:
    """Stream the just-written target version and key target ids by content hash.

    Used to populate the source-add → target-id remap. Match by full content
    hash (the same hash used by ``_compute_delta``) because the BE generates
    fresh stable ids for every add and ignores incoming ones. Goes through
    the raw REST stream + wire-type so per-item tags survive the round-trip.

    Returns ``Dict[hash → List[target_id]]`` rather than ``Dict[hash → id]``
    because the BE permits multiple items with identical content in the same
    version (``Dataset.insert([dup, dup])`` succeeds and produces two distinct
    target rows). Collapsing them into a single map entry would remap both
    source ids to the same target id and corrupt edits/deletes in later
    versions. The caller pops one entry per source-add in stream order so the
    pairing matches the source's own duplicate-add ordering.
    """
    items = _stream_version_items_raw(
        rest_client,
        dataset_name=dest_name,
        project_name=dest_project_name,
        version_hash=version_hash,
    )
    target_items_by_hash: Dict[str, List[str]] = {}
    for item in items:
        if item.id is None:
            continue
        target_items_by_hash.setdefault(_content_hash_for(item), []).append(item.id)
    return target_items_by_hash


def _added_item_payload(
    item: dataset_item_public.DatasetItemPublic,
) -> Dict[str, Any]:
    """Build the ``added_items`` payload entry for one source item.

    BE-side this maps to ``DatasetItem`` (the API record). Forwards every
    user-writable field the BE persists: data, trace_id, span_id, source,
    description, tags, evaluators, execution_policy. The BE assigns the
    target's stable id regardless of what we send, so we leave id out.
    """
    payload: Dict[str, Any] = {"data": dict(item.data) if item.data else {}}
    if item.trace_id is not None:
        payload["trace_id"] = item.trace_id
    if item.span_id is not None:
        payload["span_id"] = item.span_id
    if item.source is not None:
        payload["source"] = item.source
    if item.description is not None:
        payload["description"] = item.description
    if item.tags is not None:
        payload["tags"] = list(item.tags)
    if item.evaluators is not None:
        payload["evaluators"] = _evaluators_payload(item.evaluators)
    if item.execution_policy is not None:
        payload["execution_policy"] = _execution_policy_payload(item.execution_policy)
    return payload


def _edited_item_payload(
    modification: _Modification, item_id_remap: Dict[str, str]
) -> Dict[str, Any]:
    """Build the ``edited_items`` payload entry for one modified item.

    The BE matches edits by ``id`` (the stable identifier) against the
    target dataset, so we translate the source's id through ``item_id_remap``
    to the target id assigned when this item was first added in a prior
    replayed version. If the source id is not in the remap (shouldn't
    happen in practice — every modified item must have been added in some
    earlier version) the source id is forwarded as-is and the BE will
    silently no-op the edit.

    "Clear" semantics for per-item overrides — when the source explicitly
    removed an override between versions, the BE's null-means-inherit
    behaviour on edit would otherwise leave the stale override in place:

    * ``execution_policy`` (single object): when ``prev`` had one and
      ``curr`` does not, send ``clear_execution_policy=true``. The BE
      treats this flag as "drop the per-item override and fall back to
      the version-level policy."
    * ``evaluators`` (list): the BE has no clear-flag for this field, but
      passing ``evaluators=[]`` is a meaningful "replace with empty list"
      operation per ``DatasetItemVersionDAO.editItemsViaSelectInsert`` — the
      template adds the column iff ``edit.evaluators() != null``, so an
      empty list does overwrite. That's effectively the clear-semantics we
      need: target item ends up with no per-item evaluators, falling back
      to the suite-level set.

    Other fields (``trace_id``, ``span_id``, ``source``) are accepted by the
    BE schema on adds but ``DatasetItemEdit`` does not expose write fields
    for them, so we cannot meaningfully forward them on edit. In practice
    nothing in the BE / SDK / UI ever edits these mid-version, so this is
    a known acceptable gap.
    """
    item = modification.curr
    prev = modification.prev
    source_id = item.id
    assert source_id is not None  # filtered by _load_version_items
    target_id = item_id_remap.get(source_id, source_id)

    payload: Dict[str, Any] = {
        "id": target_id,
        "data": dict(item.data) if item.data else {},
    }
    if item.description is not None:
        payload["description"] = item.description

    # Per-item tags: forward as-is when present. When previously set and
    # now cleared, send an empty list — the BE's edit-merge treats omission
    # as "inherit," so without this the target keeps the stale tag set.
    if item.tags is not None:
        payload["tags"] = list(item.tags)
    elif prev.tags:
        payload["tags"] = []

    # Per-item evaluators: forward as-is when present. When previously set
    # and now cleared, send an empty list to overwrite the stale per-item
    # override. When neither side has them, omit the key entirely so the
    # BE leaves the (already-absent) override alone.
    if item.evaluators is not None:
        payload["evaluators"] = _evaluators_payload(item.evaluators)
    elif prev.evaluators is not None:
        payload["evaluators"] = []

    # Per-item execution_policy: forward as-is when present. When previously
    # set and now cleared, use the BE's dedicated clear flag. ``execution_policy``
    # is a single object so passing it as ``null`` isn't enough — the BE
    # treats omission as "inherit," which would silently retain the prior
    # override.
    if item.execution_policy is not None:
        payload["execution_policy"] = _execution_policy_payload(item.execution_policy)
    elif prev.execution_policy is not None:
        payload["clear_execution_policy"] = True

    return payload
