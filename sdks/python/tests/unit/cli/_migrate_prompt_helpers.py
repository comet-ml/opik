"""Shared mock helpers for ``opik migrate prompt`` test modules.

Used by ``test_migrate_prompt_planner.py``,
``test_migrate_prompt_executor.py``, and
``test_migrate_prompt_version_replay.py``. Lives as a plain module (not
a conftest.py) because these are helper classes, not pytest fixtures.
"""

from __future__ import annotations

from typing import Any, List, Optional
from unittest.mock import MagicMock


class _PromptRow:
    """Minimal stand-in for ``PromptPublic`` rows returned by ``get_prompts``."""

    def __init__(
        self,
        id: str,
        name: str,
        *,
        description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        # None = workspace-scoped (legacy v1 prompts that never had a
        # project). Tests that want a project-scoped source pass an id.
        project_id: Optional[str] = None,
        template_structure: Optional[str] = "text",
    ) -> None:
        self.id = id
        self.name = name
        self.description = description
        self.tags = tags
        self.project_id = project_id
        self.template_structure = template_structure


class _PromptVersionRow:
    """Stand-in for ``PromptVersionPublic`` rows from ``get_prompt_versions``.

    Field set mirrors the Fern model: ``id``, ``prompt_id``, ``commit``,
    ``template`` (required), plus optional metadata / type / change_description
    / tags / template_structure.
    """

    def __init__(
        self,
        id: str,
        prompt_id: str,
        commit: str,
        template: str,
        *,
        metadata: Optional[Any] = None,
        type: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        template_structure: Optional[str] = "text",
    ) -> None:
        self.id = id
        self.prompt_id = prompt_id
        self.commit = commit
        self.template = template
        self.metadata = metadata
        self.type = type
        self.change_description = change_description
        self.tags = tags
        self.template_structure = template_structure


class _Page:
    def __init__(self, content: List[Any]) -> None:
        self.content = content


def _named(name: str) -> MagicMock:
    obj = MagicMock()
    obj.name = name
    return obj


def _planner_rest_client(
    find_prompts_side_effects: List[_Page],
    *,
    target_project_exists: bool = True,
    workspace_project_names: Optional[List[str]] = None,
) -> MagicMock:
    """Build a rest_client mock for direct prompt-planner unit tests.

    ``find_prompts_side_effects`` is the side-effect list for
    ``rest_client.prompts.get_prompts``; the planner calls it twice (once
    for source resolution, once for the rename-collision preflight) so
    callers pass two pages.
    """
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
        candidates = [_named(n) for n in (workspace_project_names or [])]
        rest_client.projects.find_projects.return_value = _Page(candidates)
    rest_client.prompts.get_prompts.side_effect = find_prompts_side_effects
    return rest_client


def _planner_client(rest_client: MagicMock) -> MagicMock:
    """Wrap a rest_client mock as an ``opik.Opik``-shaped client.

    The resolver routes project-id lookups through ``client.get_project``
    (high-level surface) so the project-name resolution still benefits
    from any test-side stubs on ``rest_client.projects.get_project_by_id``.
    """
    client = MagicMock()
    client.rest_client = rest_client
    client.get_project = MagicMock(
        side_effect=lambda id: rest_client.projects.get_project_by_id(id=id)
    )
    return client
