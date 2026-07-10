"""Read-only reconstruction of a migration's in-memory remaps for resume.

When ``opik migrate dataset`` is interrupted in the experiment cascade and
re-run (OPIK-7168), the dataset-level phases (rename source -> ``_v1``, create
destination, replay versions, cascade optimizations) already completed on the
first run. Their output — ``version_remap`` / ``item_id_remap`` /
``optimization_id_remap`` — lived only in memory and is gone. The cascade
cannot proceed without them (a missing version remap writes an unpinned
experiment; a missing item remap silently skips the item), and re-running
ReplayVersions would duplicate every destination version (it has no
dest-existence check).

So a resumed run rebuilds the maps by *reading* the already-migrated
destination, writing nothing:

* ``version_remap`` — source and destination versions are paired by
  chronological index (replay creates exactly one destination version per
  source version, in order, via base-version chaining). Destination version
  hashes are recomputed by the backend and do NOT match the source, so pairing
  is positional, never by hash.
* ``item_id_remap`` — the backend's ``copy_from`` write preserves
  ``dataset_item_id``, so destination item ids equal source item ids: the map
  is identity for every item present in the paired destination version. A
  content-hash fallback covers the one historical non-identity case (the very
  first version written via ``create_or_update_dataset_items``, whose ids were
  read back).
* ``optimization_id_remap`` — source and destination optimizations are matched
  by ``name`` under their respective datasets (the cascade recreates each
  optimization with the same name).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from opik.api_objects import rest_helpers
from opik.rest_api import OpikApi

from ..errors import MigrationError
from .version_replay import (
    _content_hash_for,
    _iter_source_versions_chronological,
    _stream_version_items_raw,
)

LOGGER = logging.getLogger(__name__)

_OPTIMIZATIONS_PAGE_SIZE = 100


class ResumeReconstructionError(MigrationError):
    """A resumed run could not rebuild the remaps from the destination.

    Raised when the already-migrated destination doesn't line up with the
    source (e.g. a different version count), meaning the prior run left the
    destination in a state this reconstruction can't safely interpret.
    User-facing: delete the destination and restart rather than risk a corrupt
    resume.
    """


@dataclass
class ReconstructedRemaps:
    """The three maps a resumed cascade needs, rebuilt from the destination."""

    version_remap: Dict[str, str] = field(default_factory=dict)
    item_id_remap: Dict[str, str] = field(default_factory=dict)
    optimization_id_remap: Dict[str, str] = field(default_factory=dict)


def reconstruct_remaps(
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    source_name: str,
    source_project_name: Optional[str],
    dest_dataset_id: str,
    dest_name: str,
    dest_project_name: str,
) -> ReconstructedRemaps:
    """Rebuild the remaps from an already-migrated destination, writing nothing.

    See the module docstring for the pairing/identity rationale.
    """
    source_versions = _iter_source_versions_chronological(
        rest_client, source_dataset_id
    )
    dest_versions = _iter_source_versions_chronological(rest_client, dest_dataset_id)

    if len(dest_versions) != len(source_versions):
        # A clean resume has one destination version per source version. A
        # mismatch means the prior run left the destination in a shape we can't
        # safely pair positionally (a partially-replayed dataset, or the
        # ``needs_version_field_followup`` extra-v1 branch). Fail loud rather
        # than silently mis-pair.
        raise ResumeReconstructionError(
            f"Cannot resume: destination dataset '{dest_name}' has "
            f"{len(dest_versions)} version(s) but the source has "
            f"{len(source_versions)}. The prior run may have been interrupted "
            "mid-replay. Delete the destination dataset and re-run the "
            "migration from scratch."
        )

    remaps = ReconstructedRemaps()

    for source_version, dest_version in zip(source_versions, dest_versions):
        if source_version.id is None or dest_version.id is None:
            continue
        remaps.version_remap[source_version.id] = dest_version.id

        _reconstruct_items_for_version_pair(
            rest_client,
            source_name=source_name,
            source_project_name=source_project_name,
            source_version_hash=source_version.version_hash,
            dest_name=dest_name,
            dest_project_name=dest_project_name,
            dest_version_hash=dest_version.version_hash,
            item_id_remap=remaps.item_id_remap,
        )

    remaps.optimization_id_remap = _reconstruct_optimization_id_remap(
        rest_client,
        source_dataset_id=source_dataset_id,
        dest_dataset_id=dest_dataset_id,
    )
    return remaps


def _reconstruct_items_for_version_pair(
    rest_client: OpikApi,
    *,
    source_name: str,
    source_project_name: Optional[str],
    source_version_hash: Optional[str],
    dest_name: str,
    dest_project_name: str,
    dest_version_hash: Optional[str],
    item_id_remap: Dict[str, str],
) -> None:
    """Populate ``item_id_remap`` for one (source, destination) version pair.

    Direct id match first (the backend preserves ``dataset_item_id``, so this
    covers everything migrated via the copy_from path); content-hash fallback
    for any source item whose destination id was minted fresh.
    """
    source_items = _stream_version_items_raw(
        rest_client,
        dataset_name=source_name,
        project_name=source_project_name,
        version_hash=source_version_hash,
    )
    dest_items = _stream_version_items_raw(
        rest_client,
        dataset_name=dest_name,
        project_name=dest_project_name,
        version_hash=dest_version_hash,
    )

    dest_ids = {item.id for item in dest_items if item.id is not None}
    # hash -> queue of dest ids not yet claimed by a direct id match, for the
    # fallback. Built lazily only if a source item misses the direct match.
    dest_hash_queue: Optional[Dict[str, List[str]]] = None

    for source_item in source_items:
        if source_item.id is None or source_item.id in item_id_remap:
            continue
        if source_item.id in dest_ids:
            # Identity: the destination kept the source item id.
            item_id_remap[source_item.id] = source_item.id
            continue
        # Fallback: match by content hash against the destination version.
        if dest_hash_queue is None:
            dest_hash_queue = {}
            claimed = set(item_id_remap.values())
            for item in dest_items:
                if item.id is None or item.id in claimed:
                    continue
                dest_hash_queue.setdefault(_content_hash_for(item), []).append(item.id)
        queue = dest_hash_queue.get(_content_hash_for(source_item))
        if queue:
            item_id_remap[source_item.id] = queue.pop(0)


def _reconstruct_optimization_id_remap(
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    dest_dataset_id: str,
) -> Dict[str, str]:
    """Match source optimizations to destination ones by name.

    The optimization cascade recreates each source optimization under the
    destination with the same ``name``, so name is the stable handle. Both
    sides are enumerated via ``find_optimizations(dataset_id=...)``.
    """
    source_by_name = _optimizations_by_name(rest_client, source_dataset_id)
    if not source_by_name:
        return {}
    dest_by_name = _optimizations_by_name(rest_client, dest_dataset_id)

    remap: Dict[str, str] = {}
    for name, source_id in source_by_name.items():
        dest_id = dest_by_name.get(name)
        if dest_id is not None:
            remap[source_id] = dest_id
    return remap


def _optimizations_by_name(rest_client: OpikApi, dataset_id: str) -> Dict[str, str]:
    """Return ``{optimization_name: optimization_id}`` for a dataset.

    Assumes optimization names are unique per dataset (the cascade creates one
    destination optimization per source optimization, preserving the name); a
    duplicate name keeps the last seen, which is acceptable for the FK remap.
    """
    by_name: Dict[str, str] = {}
    page = 1
    while True:
        resp = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda p=page: rest_client.optimizations.find_optimizations(
                dataset_id=dataset_id, page=p, size=_OPTIMIZATIONS_PAGE_SIZE
            )
        )
        content = resp.content or []
        for optimization in content:
            if optimization.name is not None and optimization.id is not None:
                by_name[optimization.name] = optimization.id
        if len(content) < _OPTIMIZATIONS_PAGE_SIZE:
            break
        page += 1
    return by_name
