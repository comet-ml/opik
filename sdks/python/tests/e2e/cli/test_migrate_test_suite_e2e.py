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
from ...testlib import generate_project_name
from ._cascade_comparison import compare_cascade
from .conftest import (
    apply_changes,
    chronological_versions,
    create_dataset_shell,
    destination_experiment_items,
    destination_spans_for_trace,
    display_order,
    find_destination_experiment,
    item_hashes,
    normalize_evaluators,
    normalize_policy,
    run_migrate_cli,
    seed_experiment_with_trace_tree,
    strip_be_managed_version_tags,
    stream_items_wire,
)

# Per ``sdks/python/AGENTS.md``: every e2e module sources PROJECT_NAME from
# ``generate_project_name("e2e", __name__)`` so backend project names are
# isolated per test module + the autouse ``configure_e2e_tests_env`` fixture
# can patch ``OPIK_PROJECT_NAME`` to match.
PROJECT_NAME = generate_project_name("e2e", __name__)


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

    # ── Seed a suite-driven experiment on v2 items so the cascade has
    # something to round-trip. Test-suite experiments carry per-trace
    # assertion_results (regular-dataset experiments carry feedback_scores
    # instead -- covered in test_migrate_dataset_e2e.py). Each item gets
    # one trace, one span, and per-item assertion_results that the seed
    # helper writes via store_assertions_batch(entity_type='TRACE', ...).
    # The cascade re-emits them scoped to the new destination trace ids
    # via the same endpoint. ──
    experiment_name = f"e2e-suite-exp-{random_chars()}"
    v2_item_ids = [by_q["Q1"].id, by_q["Q2"].id, by_q["Q3"].id]
    cascade_seed = seed_experiment_with_trace_tree(
        rest,
        experiment_name=experiment_name,
        dataset_name=suite_name,
        dataset_id=suite_id,
        dataset_version_id=v2_id,
        project_name=source_project_name,
        item_ids=v2_item_ids,
        # ``type="regular"`` + ``evaluation_method="evaluation_suite"`` is
        # the canonical shape for a non-optimizer test-suite run, mirroring
        # what ``opik.evaluate(...)`` produces against a test suite. The
        # BE only updates ``last_created_experiment_at`` on the dataset for
        # ``type="regular"`` experiments, so ``with_experiments_only=true``
        # (the UI's project-page filter) correctly surfaces this one.
        # Optimizer-driven trials use ``type="trial"`` + ``optimization_id``;
        # those are Slice 4's cascade scope.
        experiment_type="regular",
        evaluation_method="evaluation_suite",
        experiment_config={"runner": "e2e-suite-cascade-test"},
        experiment_tags=["e2e", "test-suite", "cascade"],
        spans_per_trace=2,
        # Per-item runtime assertion results are seeded with the SAME name
        # as the v2 suite-level evaluator (``v1-judge``). That's what a real
        # test-suite run produces: for each item, one assertion result per
        # evaluator that ran against it. Q2 also has a per-item override
        # evaluator ``q2-item-judge``; we seed a second result there to
        # mirror what an actual evaluator run would produce.
        per_item_extras=[
            {
                "assertion_results": [
                    {
                        "value": "v1-judge",  # matches v2's suite-level evaluator
                        "passed": i % 2 == 0,
                        "reason": f"v1-judge ran on Q{i + 1}",
                    }
                ]
                # Q2 has a per-item override evaluator ``q2-item-judge``; a
                # real run would also produce a result for that. We tack it
                # on for the second item.
                + (
                    [
                        {
                            "value": "q2-item-judge",
                            "passed": True,
                            "reason": "q2-item-judge ran on Q2",
                        }
                    ]
                    if i == 1
                    else []
                ),
            }
            for i in range(len(v2_item_ids))
        ],
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

    # ── Cascade fidelity (test-suite-specific) ──
    # The destination project should now have a copy of the source
    # suite-driven experiment: type + evaluation_method preserved,
    # per-item assertion_results + execution_policy round-trip, FKs
    # remapped, traces+spans land under the destination project.
    dest_exp = find_destination_experiment(
        rest,
        destination_dataset_id=target.id,
        experiment_name=experiment_name,
    )
    assert dest_exp.id != cascade_seed["experiment_id"]
    assert dest_exp.dataset_id == target.id
    # Type + evaluation_method preserved (test-suite-specific fields).
    assert dest_exp.type == "regular", (
        f"destination experiment type should be 'regular', got {dest_exp.type!r}"
    )
    assert dest_exp.evaluation_method == "evaluation_suite", (
        f"destination experiment evaluation_method should be 'evaluation_suite', "
        f"got {dest_exp.evaluation_method!r}"
    )
    target_version_ids = {v.id for v in tgt_versions}
    assert dest_exp.dataset_version_id in target_version_ids

    # Per-item assertion_results survive on the destination via the
    # Compare view (the BE persists them through the dedicated
    # ``assertion_results.store_assertions_batch`` endpoint scoped to the
    # destination trace ids; the Compare view aggregates them onto each
    # ExperimentItemCompare for read).
    dest_items = destination_experiment_items(
        rest,
        experiment_id=dest_exp.id,
        dataset_id=target.id,
    )
    assert len(dest_items) == len(v2_item_ids)
    dest_trace_ids = {it.trace_id for it in dest_items}
    assert dest_trace_ids.isdisjoint(set(cascade_seed["trace_ids"]))

    # Each destination item has at least one assertion result named
    # ``v1-judge`` (the v2 suite-level evaluator's name), matching what a
    # real test-suite run would produce. The Q2 item additionally has a
    # ``q2-item-judge`` result because of the per-item evaluator override.
    items_with_q2_override = 0
    for dest_item in dest_items:
        ars = dest_item.assertion_results
        assert ars, "destination experiment item should have assertion_results"
        names = {a.value for a in ars}
        assert "v1-judge" in names, (
            f"destination item should carry a 'v1-judge' assertion result "
            f"(matches the v2 suite-level evaluator); got names={names}"
        )
        if "q2-item-judge" in names:
            items_with_q2_override += 1
            # Find the q2-item-judge result and verify its reason text.
            q2_ar = next(a for a in ars if a.value == "q2-item-judge")
            assert q2_ar.passed is True
            assert "Q2" in (q2_ar.reason or "")
    assert items_with_q2_override == 1, (
        "exactly one item (Q2) should carry the per-item q2-item-judge "
        f"assertion result; got {items_with_q2_override}"
    )

    # Each destination trace exists under the target project with the
    # span tree shape preserved (root + 1 child, parent_span_id remapped).
    for new_trace_id in dest_trace_ids:
        dest_spans = destination_spans_for_trace(
            rest,
            trace_id=new_trace_id,
            project_name=target_project_name,
        )
        assert len(dest_spans) == 2
        roots = [s for s in dest_spans if s.parent_span_id is None]
        assert len(roots) == 1
        children = [s for s in dest_spans if s.parent_span_id is not None]
        assert all(c.parent_span_id == roots[0].id for c in children)

    # ── Deep-equal source vs. destination ──
    # Pair source/destination items by trace ``name`` (assigned by the
    # seed as "task-0", "task-1", "task-2" and carried verbatim through
    # the cascade), then run a field-by-field comparison of experiment +
    # items (assertion_results + feedback_scores) + traces + spans
    # modulo remapped IDs.
    src_exp = find_destination_experiment(
        rest,
        destination_dataset_id=suite_id,
        experiment_name=experiment_name,
    )
    src_items_compare = destination_experiment_items(
        rest,
        experiment_id=cascade_seed["experiment_id"],
        dataset_id=suite_id,
    )
    src_trace_names = {
        it.trace_id: rest.traces.get_trace_by_id(id=it.trace_id).name
        for it in src_items_compare
    }
    dst_trace_names = {
        it.trace_id: rest.traces.get_trace_by_id(id=it.trace_id).name
        for it in dest_items
    }
    src_items_compare.sort(key=lambda it: src_trace_names[it.trace_id])
    dest_items_sorted = sorted(dest_items, key=lambda it: dst_trace_names[it.trace_id])
    src_trace_ids_sorted = [it.trace_id for it in src_items_compare]
    dst_trace_ids_sorted = [it.trace_id for it in dest_items_sorted]

    compare_cascade(
        rest_client=rest,
        source_experiment=src_exp,
        destination_experiment=dest_exp,
        source_item_ids=v2_item_ids,
        destination_item_ids=[it.dataset_item_id for it in dest_items_sorted],
        source_trace_ids=src_trace_ids_sorted,
        destination_trace_ids=dst_trace_ids_sorted,
        source_items_compare=src_items_compare,
        destination_items_compare=dest_items_sorted,
    )
