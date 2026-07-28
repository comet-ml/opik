"""Shared resolver helpers for ``opik migrate``.

Slice 6 (OPIK-6574) extracted these from ``datasets/resolver.py`` and
``prompts/resolver.py`` after the second concrete implementation made
the right shape obvious — the moment ``_base.py``'s own docstring
anticipated.

The four helpers here are entity-agnostic:

* ``iter_pages`` — generic pagination over any ``find_*`` endpoint
  that returns a ``.content``-shaped page.
* ``project_name_for_row`` — resolves a row's ``project_id`` to a
  human-readable name, best-effort.
* ``ensure_destination_project_exists`` — fail-fast project preflight
  with difflib "Did you mean…" suggestions on 404.
* ``name_taken_in_workspace`` — workspace-wide name collision check
  used by every rename-then-create planner.

Per-entity ``resolver.py`` modules keep only the entity-specific glue:
the ``Resolved*`` dataclass and the ``resolve_source_*`` function that
raises the right ``*NotFoundError`` on zero matches.
"""

from __future__ import annotations

import difflib
import logging
from typing import Any, Callable, Iterable, List, Optional, Type

import opik
from opik.api_objects import rest_helpers
from opik.rest_api.core.api_error import ApiError

from .errors import MigrationError, ProjectNotFoundError

LOGGER = logging.getLogger(__name__)

# Cap the candidate list we feed to difflib. Workspaces with >100
# projects are uncommon; suggestion quality plateaus past that.
_PROJECT_SUGGESTION_PAGE_SIZE = 100

# Page size when paginating entity lookups.
DEFAULT_PAGE_SIZE = 100


def _name_matches(row_name: str, lookup_name: str) -> bool:
    """Case-insensitive name equality, matching the BE's collation.

    The BE state database uses ``utf8mb4_unicode_ci`` (case-insensitive)
    on every table, so:

    * ``UNIQUE (workspace_id, name)`` forbids ``"MyData"`` and
      ``"mydata"`` from coexisting.
    * ``WHERE name LIKE '%mydata%'`` returns rows stored as ``"MyData"``.

    Python's default ``!=`` is case-sensitive, so the previous Slice 1
    code would receive a case-different row from the BE and then
    discard it client-side — raising ``*NotFoundError`` for a row that
    legitimately exists, or missing a collision in the rename pre-flight.
    ``str.casefold`` is the right Python primitive for Unicode-aware
    case-insensitive comparison (handles e.g. German ß → ss).
    """
    return row_name.casefold() == lookup_name.casefold()


def make_list_fn(
    find_fn: Callable[..., Any],
    *,
    name: Optional[str],
    project_id: Optional[str],
) -> Callable[[int, int], Any]:
    """Bind ``find_fn`` to a ``(page, size) -> response`` closure.

    Most Fern paginated endpoints share the same shape:
    ``find_fn(page, size, name=..., project_id=..., ...)``. Entity
    resolvers use this helper to build the ``list_fn`` that
    ``iter_pages`` / ``name_taken_in_workspace`` /
    ``resolve_unique_source_by_name`` accept, without each entity
    rewriting the closure pattern.

    ``find_fn`` is the bound Fern method (e.g.
    ``client.rest_client.datasets.find_datasets`` or
    ``client.rest_client.prompts.get_prompts``).
    """

    def _list(page: int, size: int) -> Any:
        return find_fn(page=page, size=size, name=name, project_id=project_id)

    return _list


def iter_pages(
    list_fn: Callable[[int, int], Any],
    *,
    page_size: int = DEFAULT_PAGE_SIZE,
) -> Iterable[Any]:
    """Yield every row from a paginated ``find_*`` endpoint.

    ``list_fn`` is a callable ``(page, size) -> page_response`` where
    ``page_response.content`` is the list of rows for that page. The
    caller binds entity-specific filters (``name``, ``project_id``) via
    a closure so this helper stays entity-agnostic.

    Each REST call is wrapped with the 429-aware retry helper so a
    transient rate limit doesn't abort a half-finished planner.
    """
    page_idx = 1
    while True:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda p=page_idx: list_fn(p, page_size)
        )
        content = getattr(page, "content", None) or []
        if not content:
            return
        for row in content:
            yield row
        if len(content) < page_size:
            return
        page_idx += 1


def project_name_for_row(client: opik.Opik, row: Any) -> Optional[str]:
    """Resolve a row's ``project_id`` to a human-readable name.

    Returns ``None`` when the row is workspace-scoped (``project_id``
    null) or when the project lookup raises a recoverable ``ApiError``
    (typically 404 — the project was deleted between listing and lookup).

    Unexpected exceptions are logged at WARNING and we still return
    ``None`` so the surrounding check doesn't fail mid-flight. This is
    deliberate: this lookup powers collision messages and audit-log
    labels (cosmetic, not load-bearing for correctness). A stack-trace
    bubble-up here would abort a migration over a project-name
    formatting concern, which is the wrong trade.
    """
    project_id = getattr(row, "project_id", None)
    if not project_id:
        return None
    try:
        proj = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: client.get_project(id=project_id)
        )
        return getattr(proj, "name", None)
    except ApiError:
        return None
    except Exception as exc:
        LOGGER.warning(
            "Unexpected error resolving project id %s to a name: %s",
            project_id,
            exc.__class__.__name__,
        )
        return None


def ensure_destination_project_exists(
    client: opik.Opik,
    to_project: str,
) -> str:
    """Resolve ``to_project`` to its ID, or raise ``ProjectNotFoundError``.

    Fails fast rather than auto-creating: a typo in ``--to-project``
    would otherwise silently strand the migration under a brand-new
    project the user did not mean to create. Softens the failure with
    ``Did you mean: ...`` suggestions when close-name workspace projects
    exist.
    """
    try:
        return rest_helpers.resolve_project_id_by_name(
            client.rest_client, project_name=to_project
        )
    except ApiError as exc:
        if exc.status_code == 404:
            suggestions = _suggest_project_names(client, to_project)
            message = f"Destination project '{to_project}' does not exist."
            if suggestions:
                message += f" Did you mean: {', '.join(repr(s) for s in suggestions)}?"
            message += " Create it via the UI or SDK, then re-run."
            raise ProjectNotFoundError(message) from exc
        raise


def _suggest_project_names(client: opik.Opik, name: str) -> List[str]:
    """Best-effort: up to 3 closest project-name matches for ``name``."""
    try:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: client.rest_client.projects.find_projects(
                page=1, size=_PROJECT_SUGGESTION_PAGE_SIZE
            )
        )
    except ApiError:
        return []
    except Exception as exc:
        LOGGER.warning(
            "Project-suggestion lookup failed unexpectedly while resolving "
            "missing project %r: %s",
            name,
            exc.__class__.__name__,
        )
        return []
    candidates = [p.name for p in page.content if p.name]
    return difflib.get_close_matches(name, candidates, n=3, cutoff=0.6)


def name_taken_in_workspace(
    client: opik.Opik,
    *,
    list_fn: Callable[[int, int], Any],
    name: str,
    ignore_id: Optional[str] = None,
) -> Optional[str]:
    """Return a description of the colliding project, or ``None``.

    Both ``datasets`` and ``prompts`` enforce workspace-wide name
    uniqueness (``UNIQUE (workspace_id, name)`` on each table), so the
    pre-flight scans the workspace for any row holding ``name``.
    ``ignore_id`` lets callers exclude the source row from the check
    (it's about to be renamed, so its current name doesn't conflict).

    ``list_fn`` is the entity-specific ``(page, size) -> response``
    callable, same shape as ``iter_pages`` accepts. Caller binds
    ``name=...`` via a closure.

    Returns a human-readable label for the colliding row's project
    (e.g. ``"project 'Foo'"``) or ``"another project"`` when the row is
    workspace-scoped / project lookup fails. ``None`` means no
    collision found.
    """
    for row in iter_pages(list_fn):
        if not _name_matches(row.name, name):
            continue
        if ignore_id is not None and row.id == ignore_id:
            continue
        project_name = project_name_for_row(client, row)
        if project_name:
            return f"project '{project_name}'"
        return "another project"
    return None


def find_row_in_workspace(
    *,
    list_fn: Callable[[int, int], Any],
    name: str,
    ignore_id: Optional[str] = None,
) -> Optional[Any]:
    """Return the first workspace row matching ``name``, or ``None``.

    Sibling to ``name_taken_in_workspace``: that helper answers "is the
    name taken and by which project" (a label); this one hands back the
    row itself so the caller can act on it (e.g. delete a stale temp
    destination by id). ``ignore_id`` excludes a specific row (e.g. the
    source about to be renamed). Workspace name uniqueness means at most
    one row matches.
    """
    for row in iter_pages(list_fn):
        if not _name_matches(row.name, name):
            continue
        if ignore_id is not None and row.id == ignore_id:
            continue
        return row
    return None


def resolve_unique_source_by_name(
    client: opik.Opik,
    *,
    list_fn: Callable[[int, int], Any],
    name: str,
    not_found_error: Type[MigrationError],
    invariant_violated_error: Type[MigrationError],
    entity_label: str,
    scope_label: str = "the workspace",
) -> Any:
    """Resolve ``name`` to the single matching row, or raise.

    Both datasets and prompts have ``UNIQUE (workspace_id, name)``: a
    workspace lookup yields at most one row. ``not_found_error`` is
    raised on zero matches; ``invariant_violated_error`` is raised on
    >1 matches (the BE constraint has been bypassed somehow).

    ``scope_label`` controls the not-found error wording so callers can
    pass e.g. ``"project 'A'"`` when the lookup was scoped via
    ``--from-project``. Defaults to ``"the workspace"`` for the
    workspace-wide lookup case.

    Returns the raw row object so the per-entity ``resolve_source_*``
    function can build its ``Resolved*`` dataclass from it.
    """
    matches: List[Any] = []
    for row in iter_pages(list_fn):
        if not _name_matches(row.name, name):
            continue
        matches.append(row)

    if not matches:
        raise not_found_error(
            f"{entity_label.capitalize()} '{name}' not found in {scope_label}."
        )

    if len(matches) > 1:
        raise invariant_violated_error(
            f"{entity_label.capitalize()} name '{name}' matched {len(matches)} rows "
            "— the workspace uniqueness invariant appears to be violated. "
            "Aborting; investigate via the UI before retrying."
        )

    return matches[0]
