"""Action planning for ``opik migrate dataset``.

The planner builds a ``MigrationPlan`` of typed action records. The executor
applies them in list order; this separation is what lets ``--dry-run`` and
``opik migrate plan`` reuse the same logic with no side effects.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional

from opik.rest_api import OpikApi

from .errors import ConflictError, UnsupportedDatasetTypeError
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


@dataclass(frozen=True)
class DeleteSource:
    source_id: str
    name_after_rename: str
    project_name: Optional[str]


@dataclass
class MigrationPlan:
    source: ResolvedDataset
    target_name: str
    to_project: str
    actions: List[object] = field(default_factory=list)


def build_dataset_plan(
    rest_client: OpikApi,
    name: str,
    to_project: str,
    from_project: Optional[str],
    target_name: Optional[str],
    source_suffix: str,
    delete_source: bool,
) -> MigrationPlan:
    """Build the ordered action list for migrating one dataset.

    Ordering invariant: the source rename (when applied) always precedes the
    destination create, so the workspace-unique-name constraint never trips.
    ``DeleteSource`` is always last; if any earlier action fails, the executor
    short-circuits before reaching it.
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

    rename_applied = target_name is None
    final_target_name = target_name if target_name is not None else source.name
    name_after_rename = (
        f"{source.name}{source_suffix}" if rename_applied else source.name
    )

    # Dataset names are workspace-unique, so the pre-flight has to look
    # workspace-wide:
    #   - rename step: <source><suffix> must be free (excluding the source itself)
    #   - create step: target name must be free (when --target-name overrides
    #     the default; otherwise the rename above frees the source's old name)
    if rename_applied:
        collision = name_taken_in_workspace(
            rest_client, name_after_rename, ignore_dataset_id=source.id
        )
        if collision:
            raise ConflictError(
                f"Cannot rename source to '{name_after_rename}' — that name is "
                f"already used by a dataset in {collision}. "
                "Try a different --source-suffix."
            )

    if target_name is not None:
        collision = name_taken_in_workspace(
            rest_client, target_name, ignore_dataset_id=source.id
        )
        if collision:
            raise ConflictError(
                f"Target name '{target_name}' is already used by a dataset in "
                f"{collision}."
            )

    plan = MigrationPlan(
        source=source,
        target_name=final_target_name,
        to_project=to_project,
    )

    if rename_applied:
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
            name=final_target_name,
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
                dest_name=final_target_name,
                dest_project_name=to_project,
            )
        )

    plan.actions.append(
        CopyCurrentItems(
            source_dataset_id=source.id,
            source_name_after_rename=name_after_rename,
            source_project_name=source.project_name,
            dest_name=final_target_name,
            dest_project_name=to_project,
        )
    )

    if delete_source:
        plan.actions.append(
            DeleteSource(
                source_id=source.id,
                name_after_rename=name_after_rename,
                project_name=source.project_name,
            )
        )

    return plan
