"""Version-history replay for ``opik migrate prompt``.

Walks every source prompt version chronologically and POSTs each one
against the destination prompt via ``create_prompt_version``. Source
commit hashes are carried verbatim — the BE's
``unique(workspace_id, prompt_id, commit)`` key tolerates this because
the destination prompt has a fresh id.

Stays on the low-level ``OpikApi`` (Fern) client so we can read every
field the BE persists per version (``template``, ``metadata``, ``type``,
``commit``, ``change_description``, ``tags``, ``template_structure``)
without going through the high-level ``opik.api_objects.prompt`` wrapper.
Every call site is wrapped with
``ensure_rest_api_call_respecting_rate_limit``.

Slice 7 (OPIK-6575) imports ``replay_all_prompt_versions`` from here as
part of the dataset-cascade-prompts integration.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from opik.api_objects import rest_helpers
from opik.rest_api import OpikApi
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail

from ..audit import AuditLog

LOGGER = logging.getLogger(__name__)

# Page size for ``get_prompt_versions``. The endpoint orders newest-first
# (``pv.id DESC`` in the SQL DAO; Opik IDs are UUIDv7 so id order is
# effectively chronological). We paginate to exhaustion and reverse the
# combined list to oldest-first before replay.
_VERSIONS_PAGE_SIZE = 100


@dataclass
class ReplayResult:
    """Outcome of a full prompt-version replay loop.

    Slice 7 reads ``prompt_version_id_remap`` to remap experiment FK
    references that point at source prompt versions.
    """

    prompt_version_id_remap: Dict[str, str] = field(default_factory=dict)
    versions_replayed: int = 0


def _iter_source_versions_oldest_first(
    rest_client: OpikApi, source_prompt_id: str
) -> List[Any]:
    """Return source versions in oldest-first order.

    BE orders ``pv.id DESC`` (UUIDv7 ids -> chronological newest-first).
    We collect every page then reverse to oldest-first so the destination
    accumulates versions in the same order the source did.
    """
    versions: List[Any] = []
    page_idx = 1
    while True:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda p=page_idx: rest_client.prompts.get_prompt_versions(
                id=source_prompt_id,
                page=p,
                size=_VERSIONS_PAGE_SIZE,
            )
        )
        content = getattr(page, "content", None) or []
        if not content:
            break
        versions.extend(content)
        if len(content) < _VERSIONS_PAGE_SIZE:
            break
        page_idx += 1
    versions.reverse()
    return versions


def replay_all_prompt_versions(
    rest_client: OpikApi,
    *,
    source_prompt_id: str,
    source_name_after_rename: str,
    source_project_name: Optional[str],
    dest_name: str,
    dest_project_name: str,
    template_structure: Optional[str],
    audit: AuditLog,
    progress_callback: Optional[Callable[[int, int, str], None]] = None,
) -> ReplayResult:
    """Replay every source version onto the destination chronologically.

    The destination prompt is expected to have **zero** versions when this
    runs (the executor's ``CreateDestination`` action creates a bare
    container with no template, which the BE recognises as "do not
    auto-mint a v1"). Every source version — including v1 — is therefore
    minted via ``create_prompt_version`` with the source's ``commit``
    carried verbatim.

    Each version is its own audited record so the on-disk audit log
    carries one entry per replayed version (parity with dataset replay's
    per-version audit shape). The outer ``replay_versions`` action's
    audit bracketing is owned by the executor.

    ``progress_callback`` fires once before each version begins with
    ``(completed_count, total_versions, source_version_label)`` so the
    executor can drive a Rich progress bar. Keeping the UI concern in the
    callback (rather than this module) means tests don't have to stub
    Rich; the executor owns the live progress display.
    """
    versions = _iter_source_versions_oldest_first(rest_client, source_prompt_id)
    result = ReplayResult()

    if not versions:
        # Source has no committed versions — the destination is left as
        # the empty container produced by CreateDestination.
        return result

    total = len(versions)

    for index, source_version in enumerate(versions):
        commit = getattr(source_version, "commit", None)
        label = commit or f"v{index + 1}"
        if progress_callback is not None:
            progress_callback(index, total, label)

        version_payload = PromptVersionDetail(
            template=source_version.template,
            metadata=getattr(source_version, "metadata", None),
            type=getattr(source_version, "type", None),
            commit=commit,
            change_description=getattr(source_version, "change_description", None),
            tags=getattr(source_version, "tags", None),
        )

        create_kwargs: Dict[str, Any] = {
            "name": dest_name,
            "version": version_payload,
        }
        if template_structure is not None:
            create_kwargs["template_structure"] = template_structure
        if dest_project_name is not None:
            create_kwargs["project_name"] = dest_project_name

        created = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda kw=create_kwargs: rest_client.prompts.create_prompt_version(**kw)
        )

        new_version_id = getattr(created, "id", None)
        source_version_id = getattr(source_version, "id", None)
        if source_version_id is not None and new_version_id is not None:
            result.prompt_version_id_remap[source_version_id] = new_version_id

        result.versions_replayed += 1

        audit.record(
            type="replay_prompt_version",
            status="ok",
            details={
                "type": "replay_prompt_version",
                "source_version_id": source_version_id,
                "source_commit": commit,
                "target_version_id": new_version_id,
                "target_commit": getattr(created, "commit", None),
            },
        )

    return result
