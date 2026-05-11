"""End-to-end test for ``opik migrate dataset`` against a test suite.

Test suites have an extra dimension over plain datasets: each version
carries suite-level ``evaluators`` + ``execution_policy`` that the
migration must replay per-version. Plus the BE-natural test-suite shape
is "config-only v1 + content versions v2+" (the SDK's
``create_test_suite(global_assertions=...)`` produces a v1 with zero
items but the suite config attached).

This test covers the full surface:

- 4 versions: config-only v1 + 3 content versions with adds/edits/deletes
- Per-version suite-level evaluators that change across versions
- Per-version suite-level execution_policy that changes across versions
- Per-version user tags + metadata
- Per-item evaluators + execution_policy + tags on individual items
- Clear transitions: items that drop their per-item overrides between versions

Verifies all of the above round-trips at every replayed version on the
target.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

import pytest

import opik

from ...conftest import random_chars
from .conftest import (
    apply_changes,
    chronological_versions,
    create_dataset_shell,
    display_order,
    item_hashes,
    normalize_evaluators,
    normalize_policy,
    run_migrate_cli,
    strip_be_managed_version_tags,
    stream_items_wire,
)

PROJECT_NAME = "e2e-cli-migrate-test-suite"


@pytest.fixture
def suite_name() -> Iterator[str]:
    yield f"e2e-migrate-suite-{random_chars()}"


# Suite-level evaluators per source version. Each entry is what gets
# attached to that version (changes across versions to exercise replay).
SUITE_EVALUATORS_PER_VERSION = [
    # v1: one suite-level assertion
    [{"name": "v1-judge", "type": "llm_judge", "config": {"model": "haiku"}}],
    # v2: still v1's evaluator (no suite-level change)
    [{"name": "v1-judge", "type": "llm_judge", "config": {"model": "haiku"}}],
    # v3: add a second suite-level assertion + bump policy
    [
        {"name": "v1-judge", "type": "llm_judge", "config": {"model": "haiku"}},
        {"name": "v2-judge", "type": "llm_judge", "config": {"model": "sonnet"}},
    ],
    # v4: replace evaluators entirely
    [{"name": "v3-only-judge", "type": "llm_judge", "config": {"model": "opus"}}],
]

SUITE_EXEC_POLICY_PER_VERSION = [
    {"runs_per_item": 1, "pass_threshold": 1},
    {"runs_per_item": 1, "pass_threshold": 1},
    {"runs_per_item": 3, "pass_threshold": 2},
    {"runs_per_item": 5, "pass_threshold": 3},
]


def test_test_suite_full_fidelity_round_trip(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    suite_name: str,
    tmp_path: Path,
) -> None:
    """Migrate a 4-version test suite end-to-end and verify per-version
    fidelity across items, item-level overrides, suite-level config,
    metadata, and user tags.

    Exercises every fidelity dimension Slice 2 supports in one test
    because the BE round-trip is the expensive part; multiple smaller
    tests would each pay the same setup cost. If any dimension regresses
    this single test pins it.
    """
    rest = opik_client.rest_client

    # ── Seed v1: config-only (no items, just suite-level evaluators+policy) ──
    suite_id = create_dataset_shell(
        rest, suite_name, source_project_name, type="evaluation_suite"
    )
    apply_changes(
        rest,
        suite_id,
        base_version_id=None,
        change_description="v1 — initial config-only version",
        suite_evaluators=SUITE_EVALUATORS_PER_VERSION[0],
        suite_execution_policy=SUITE_EXEC_POLICY_PER_VERSION[0],
        override=True,  # required when base_version=None
    )
    v1 = chronological_versions(rest, suite_id)[0]

    # ── Seed v2: add 3 items (Q1, Q2 with per-item evaluator, Q3 with
    # per-item exec policy). Suite config unchanged. ──
    v2_items = [
        {"data": {"q": "Q1", "a": "A1"}, "source": "manual"},
        {
            "data": {"q": "Q2", "a": "A2"},
            "source": "manual",
            "evaluators": [
                {
                    "name": "q2-item-judge",
                    "type": "llm_judge",
                    "config": {"model": "haiku-item"},
                }
            ],
            "tags": ["smoke"],
        },
        {
            "data": {"q": "Q3", "a": "A3"},
            "source": "manual",
            "execution_policy": {"runs_per_item": 7, "pass_threshold": 4},
        },
    ]
    v2_id = apply_changes(
        rest,
        suite_id,
        base_version_id=v1.id,
        added_items=v2_items,
        change_description="v2 — initial items with per-item overrides",
        suite_evaluators=SUITE_EVALUATORS_PER_VERSION[1],
        suite_execution_policy=SUITE_EXEC_POLICY_PER_VERSION[1],
    )
    v2_streamed = stream_items_wire(
        rest,
        dataset_name=suite_name,
        project_name=source_project_name,
        version_hash=None,  # latest
    )
    by_q = {it.data["q"]: it for it in v2_streamed if it.data}

    # ── Seed v3: edit Q1 (add per-item policy + extend tags), edit Q2
    # (clear tags), add Q4. Bump suite evaluators to v3 config, bump policy. ──
    v3_id = apply_changes(
        rest,
        suite_id,
        base_version_id=v2_id,
        edited_items=[
            {
                "id": by_q["Q1"].id,
                "data": {"q": "Q1", "a": "A1"},
                "execution_policy": {"runs_per_item": 9, "pass_threshold": 5},
                "tags": ["regression", "v3-touched"],
            },
            {
                "id": by_q["Q2"].id,
                "data": {"q": "Q2", "a": "A2"},
                # Clear Q2's tags by passing empty list.
                "tags": [],
            },
        ],
        added_items=[{"data": {"q": "Q4", "a": "A4"}, "source": "manual"}],
        change_description="v3 — edit Q1, clear Q2 tags, add Q4",
        suite_evaluators=SUITE_EVALUATORS_PER_VERSION[2],
        suite_execution_policy=SUITE_EXEC_POLICY_PER_VERSION[2],
        metadata={"phase": "experimentation", "owner": "ada"},
        user_tags=["baseline"],
    )

    # ── Seed v4: delete Q2, replace Q3's per-item evaluator, add Q5 with a
    # per-item evaluator. Replace suite config entirely + bump policy. ──
    apply_changes(
        rest,
        suite_id,
        base_version_id=v3_id,
        edited_items=[
            {
                "id": by_q["Q3"].id,
                "data": {"q": "Q3", "a": "A3"},
                "evaluators": [
                    {
                        "name": "q3-item-judge-NEW",
                        "type": "llm_judge",
                        "config": {"model": "sonnet-item"},
                    }
                ],
            }
        ],
        deleted_ids=[by_q["Q2"].id],
        added_items=[
            {
                "data": {"q": "Q5", "a": "A5"},
                "source": "manual",
                "evaluators": [
                    {
                        "name": "q5-item-judge",
                        "type": "llm_judge",
                        "config": {"model": "opus-item"},
                    }
                ],
            }
        ],
        change_description="v4 — delete Q2, replace Q3 evaluator, add Q5",
        suite_evaluators=SUITE_EVALUATORS_PER_VERSION[3],
        suite_execution_policy=SUITE_EXEC_POLICY_PER_VERSION[3],
        metadata={"phase": "production", "owner": "ada"},
        user_tags=["v1.0", "release-candidate"],
    )

    # ── Snapshot source expectations per version ──
    src_versions = chronological_versions(rest, suite_id)
    assert len(src_versions) == 4
    expected = []
    for v in src_versions:
        items = stream_items_wire(
            rest,
            dataset_name=suite_name,
            project_name=source_project_name,
            version_hash=v.version_hash,
        )
        expected.append(
            {
                "hashes": item_hashes(items),
                "order": display_order(items),
                "suite_evals": normalize_evaluators(v.evaluators),
                "suite_pol": normalize_policy(v.execution_policy),
                "metadata": v.metadata or None,
                "user_tags": strip_be_managed_version_tags(v.tags),
            }
        )

    # ── Run the migration ──
    audit_path = tmp_path / "audit.json"
    result = run_migrate_cli(
        [
            "dataset",
            suite_name,
            "--from-project",
            source_project_name,
            "--to-project",
            target_project_name,
        ],
        audit_log_path=str(audit_path),
    )
    assert result.returncode == 0, result.stdout + result.stderr

    # ── Verify target ──
    target = rest.datasets.get_dataset_by_identifier(
        dataset_name=suite_name, project_name=target_project_name
    )
    assert target.type == "evaluation_suite", (
        f"target should be a test suite, got type={target.type!r}"
    )

    tgt_versions = chronological_versions(rest, target.id)
    assert len(tgt_versions) == len(src_versions), (
        f"target version count {len(tgt_versions)} != source {len(src_versions)} "
        "— Slice 2 contract requires N=N for test suites too"
    )

    for src_v, tgt_v, exp in zip(src_versions, tgt_versions, expected):
        items = stream_items_wire(
            rest,
            dataset_name=suite_name,
            project_name=target_project_name,
            version_hash=tgt_v.version_hash,
        )
        # Items: set-equal under content hash + display order matches.
        actual_hashes = item_hashes(items)
        assert actual_hashes == exp["hashes"], (
            f"{tgt_v.version_name}: items diverged from {src_v.version_name}. "
            f"Missing on target: {exp['hashes'] - actual_hashes}; "
            f"extra on target: {actual_hashes - exp['hashes']}"
        )
        assert display_order(items) == exp["order"], (
            f"{tgt_v.version_name}: display order diverged"
        )
        # Suite-level config matches.
        assert normalize_evaluators(tgt_v.evaluators) == exp["suite_evals"], (
            f"{tgt_v.version_name}: suite evaluators diverged"
        )
        assert normalize_policy(tgt_v.execution_policy) == exp["suite_pol"], (
            f"{tgt_v.version_name}: suite execution_policy diverged"
        )
        # Version-level metadata + user tags match.
        assert (tgt_v.metadata or None) == exp["metadata"], (
            f"{tgt_v.version_name}: metadata diverged"
        )
        assert strip_be_managed_version_tags(tgt_v.tags) == exp["user_tags"], (
            f"{tgt_v.version_name}: user version tags diverged"
        )
