"""Action planning for ``opik migrate dataset``.

The planner builds a ``MigrationPlan`` of typed action records. The executor
applies them in list order; this separation is what lets ``--dry-run``
reuse the same logic with no side effects.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from opik.rest_api import OpikApi

from .._base import BaseMigrationPlan
from ..errors import ConflictError, UnsupportedDatasetTypeError
from .resolver import (
    ResolvedDataset,
    ensure_destination_project_exists,
    name_taken_in_workspace,
    resolve_source,
)

# Slice 1 supports plain datasets and test suites. Test suites (type
# 'evaluation_suite') carry suite-level evaluators + execution_policy as
# versioned config; we copy the LATEST version's config onto the target.
SUPPORTED_DATASET_TYPES = {None, "dataset", "evaluation_suite"}
TEST_SUITE_TYPE = "evaluation_suite"

# Hardcoded suffix appended to the source dataset's name during the rename
# step. Slice 1 doesn't expose this as a flag; if a future slice needs it
# configurable the planner is the right place to add it.
SOURCE_SUFFIX = "_v1"


@dataclass(frozen=True)
class RenameSource:
    source_id: str
    from_name: str
    to_name: str
    # Re-passed verbatim on the rename PUT so the BE doesn't wipe these
    # (description in particular gets nulled when omitted from update_dataset;
    # tags/visibility appear to survive but we re-pass them defensively).
    description: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]


@dataclass(frozen=True)
class CreateDestination:
    name: str
    project_name: str
    description: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]
    # 'dataset' (default) or 'evaluation_suite'. Only forwarded when the
    # source was a test suite — passing the same type is what makes the
    # target accept suite-level evaluators + execution_policy later.
    type: Optional[str]


@dataclass(frozen=True)
class CopyCurrentItems:
    source_dataset_id: str
    source_name_after_rename: str
    source_project_name: Optional[str]
    dest_name: str
    dest_project_name: str


@dataclass(frozen=True)
class CopyTestSuiteConfig:
    """Copy the source's LATEST suite-level evaluators + execution_policy.

    Only emitted when source.type == 'evaluation_suite'. Reads from the
    source's most recent dataset version and applies the same payload to the
    target via apply_dataset_item_changes(override=True), creating one
    initial version on the target with the same suite-level config.
    """

    source_dataset_id: str
    dest_name: str
    dest_project_name: str


@dataclass(kw_only=True)
class MigrationPlan(BaseMigrationPlan):
    """Dataset-specific migration plan.

    Inherits the shared ``source`` / ``actions`` shape from
    ``BaseMigrationPlan`` and narrows ``source`` to ``ResolvedDataset``.
    The action list contains dataset-specific records (``RenameSource``,
    ``CreateDestination``, ``CopyTestSuiteConfig``, ``CopyCurrentItems``).
    """

    source: ResolvedDataset
    target_name: str
    to_project: str


def build_dataset_plan(
    rest_client: OpikApi,
    name: str,
    to_project: str,
    from_project: Optional[str],
) -> MigrationPlan:
    """Build the ordered action list for migrating one dataset.

    Ordering invariant: the source rename always precedes the destination
    create, so the workspace-unique-name constraint never trips. The target
    keeps the source's original name.
    """
    # Fail fast if --to-project doesn't exist. Catches typos before any
    # rename/create/copy work, and prevents auto-creating a stray project.
    ensure_destination_project_exists(rest_client, to_project)

    source = resolve_source(rest_client, name, from_project)

    if source.type not in SUPPORTED_DATASET_TYPES:
        raise UnsupportedDatasetTypeError(
            f"Dataset '{source.name}' has unsupported type '{source.type}'. "
            f"`opik migrate` supports {sorted(t for t in SUPPORTED_DATASET_TYPES if t)}."
        )

    is_test_suite = source.type == TEST_SUITE_TYPE
    name_after_rename = f"{source.name}{SOURCE_SUFFIX}"

    # Dataset names are workspace-unique, so the rename target must be free
    # workspace-wide (excluding the source dataset itself, which is about
    # to be renamed). Without this check the rename PUT would 409 mid-flight.
    collision = name_taken_in_workspace(
        rest_client, name_after_rename, ignore_dataset_id=source.id
    )
    if collision:
        raise ConflictError(
            f"Cannot rename source to '{name_after_rename}' — that name is "
            f"already used by a dataset in {collision}. "
            "Rename or delete the conflicting dataset and re-run."
        )

    plan = MigrationPlan(
        source=source,
        target_name=source.name,
        to_project=to_project,
    )

    plan.actions.append(
        RenameSource(
            source_id=source.id,
            from_name=source.name,
            to_name=name_after_rename,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
        )
    )

    plan.actions.append(
        CreateDestination(
            name=source.name,
            project_name=to_project,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
            # Only forward the type when it differs from the default —
            # creating a 'dataset' explicitly is harmless but creating an
            # 'evaluation_suite' is what makes the target accept suite config.
            type=source.type if is_test_suite else None,
        )
    )

    # Test-suite config has to be applied BEFORE items: copying items creates
    # the target's first version, after which `apply_dataset_item_changes`
    # rejects payloads without an explicit `base_version` (override=True is
    # not enough). Sequencing the suite-config call first means the target
    # starts with an empty initial version that carries the suite-level
    # evaluators+policy, and the subsequent item copy creates v2 with both
    # the items and the inherited config.
    if is_test_suite:
        plan.actions.append(
            CopyTestSuiteConfig(
                source_dataset_id=source.id,
                dest_name=source.name,
                dest_project_name=to_project,
            )
        )

    plan.actions.append(
        CopyCurrentItems(
            source_dataset_id=source.id,
            source_name_after_rename=name_after_rename,
            source_project_name=source.project_name,
            dest_name=source.name,
            dest_project_name=to_project,
        )
    )

    return plan
