"""Shared mock helpers for ``opik migrate`` test modules.

Used by both ``test_migrate_dataset_exclude_versions.py`` (Slice 1 paths) and
``test_migrate_dataset_version_replay.py`` (Slice 2 paths). Lives as a plain
module (not conftest.py) because these are helper classes, not pytest
fixtures.
"""

from __future__ import annotations

from typing import Dict, List, Optional
from unittest.mock import MagicMock


class _DatasetRow:
    def __init__(
        self,
        id: str,
        name: str,
        description: Optional[str] = None,
        items: int = 0,
        type: Optional[str] = "dataset",
        visibility: Optional[str] = "private",
        tags: Optional[List[str]] = None,
        # ``project_id=None`` represents a workspace-scoped dataset (V1
        # entity, or anything left at workspace scope after auto-migration).
        # Tests that want a project-scoped source pass an explicit id.
        project_id: Optional[str] = None,
    ) -> None:
        self.id = id
        self.name = name
        self.description = description
        self.dataset_items_count = items
        self.type = type
        self.visibility = visibility
        self.tags = tags
        self.project_id = project_id


class _Page:
    def __init__(self, content: List[_DatasetRow]) -> None:
        self.content = content


def _named(name: str) -> MagicMock:
    obj = MagicMock()
    obj.name = name
    return obj


def _planner_rest_client(
    find_side_effects: List[_Page],
    *,
    target_project_exists: bool = True,
    workspace_project_names: Optional[List[str]] = None,
) -> MagicMock:
    """Build a rest_client mock for direct planner unit tests."""
    rest_client = MagicMock()
    if target_project_exists:
        target_project = MagicMock()
        target_project.id = "target-project-id"
        rest_client.projects.retrieve_project.return_value = target_project
    else:
        from opik.rest_api.core.api_error import ApiError

        rest_client.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={}
        )
        # Suggestion lookup queries find_projects on the 404 path.
        candidates = [_named(name) for name in (workspace_project_names or [])]
        rest_client.projects.find_projects.return_value = _Page(candidates)
    rest_client.datasets.find_datasets.side_effect = find_side_effects
    return rest_client


def _planner_client(rest_client: MagicMock) -> MagicMock:
    """Wrap a planner rest_client mock as an ``opik.Opik``-shaped client.

    The planner now takes the high-level client (so the resolver can route
    ``get_project_by_id`` through ``client.get_project`` instead of the
    Fern surface). Tests still build the low-level rest_client mock and
    drive its side_effects, then wrap with this helper to satisfy the new
    planner signature.

    ``client.get_project(id=...)`` delegates to
    ``rest_client.projects.get_project_by_id(id=...)`` so any per-test
    stub on the rest_client side still flows through unchanged.
    """
    client = MagicMock()
    client.rest_client = rest_client
    client.get_project = MagicMock(
        side_effect=lambda id: rest_client.projects.get_project_by_id(id=id)
    )
    return client


def _build_fake_client(
    *,
    source_rows: List[_DatasetRow],
    destination_rows: List[_DatasetRow],
    items: List[Dict[str, object]],
    target_project_exists: bool = True,
) -> MagicMock:
    """Construct an opik.Opik mock matching the executor's call surface.

    The find_datasets mock returns ``source_rows`` for the first lookup
    (source resolution) and ``destination_rows`` for the second (preflight
    conflict check). The planner places those calls in a fixed order, so a
    side-effect list is the simplest way to drive both branches deterministically.

    ``items`` is a list of dicts; we materialize them as DatasetItem
    dataclasses for the streaming mock (matches the real `__internal_api__
    stream_items_as_dataclasses__` shape) so per-item fidelity assertions
    have somewhere to land.
    """
    from opik.api_objects.dataset import dataset_item

    rest_client = MagicMock()
    if target_project_exists:
        target_project = MagicMock()
        target_project.id = "target-project-id"
        rest_client.projects.retrieve_project.return_value = target_project
    else:
        from opik.rest_api.core.api_error import ApiError

        rest_client.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={}
        )
    rest_client.datasets.find_datasets.side_effect = [
        _Page(source_rows),
        _Page(destination_rows),
    ]
    rest_client.datasets.update_dataset = MagicMock()
    rest_client.datasets.create_dataset = MagicMock()

    client = MagicMock()
    client.rest_client = rest_client
    client._workspace = "default"

    # Build DatasetItem dataclasses from the provided dicts so the executor's
    # dataclass-form stream returns a realistic shape. Top-level fields like
    # `description` / `source` / `trace_id` / `span_id` can be passed via the
    # dict (other keys get stuffed into `data`/extra).
    top_level = {
        "id",
        "trace_id",
        "span_id",
        "source",
        "description",
        "evaluators",
        "execution_policy",
    }
    source_items: List[dataset_item.DatasetItem] = []
    for raw in items:
        kwargs = {k: v for k, v in raw.items() if k in top_level and k != "id"}
        data = {k: v for k, v in raw.items() if k not in top_level}
        ds_item = dataset_item.DatasetItem(**kwargs, **data)
        if "id" in raw:
            ds_item.id = raw["id"]  # type: ignore[assignment]
        source_items.append(ds_item)

    # MagicMock treats dunder-prefixed names as magic and blocks them by
    # default; pre-attach plain MagicMocks so attribute access works.
    source_dataset = MagicMock()
    stream_mock = MagicMock(return_value=iter(source_items))
    source_dataset.__internal_api__stream_items_as_dataclasses__ = stream_mock

    dest_dataset = MagicMock()
    insert_mock = MagicMock()
    dest_dataset.__internal_api__insert_items_as_dataclasses__ = insert_mock

    # Source name after the executor's RenameSource step is ``<orig>_v1``;
    # dest keeps the original. Route by suffix so the executor can resolve the
    # destination in ``_replay_versions`` BEFORE any source streaming happens
    # (the prior call-sequence-based heuristic broke once that lookup moved
    # earlier in the cascade).
    source_orig_name = source_rows[0].name if source_rows else ""
    renamed_source = f"{source_orig_name}_v1"

    def _get_dataset(name: str, project_name: Optional[str] = None) -> MagicMock:
        if name == renamed_source:
            return source_dataset
        return dest_dataset

    client.get_dataset.side_effect = _get_dataset
    client.create_dataset = MagicMock()
    client.delete_dataset = MagicMock()
    return client, source_dataset, dest_dataset
