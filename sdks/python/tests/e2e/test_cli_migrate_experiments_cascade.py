"""E2E tests for ``opik migrate dataset`` Slice 3 -- experiment + trace
cascade.

This test exercises the full pipeline against a real backend: build a
multi-version dataset under a source project, attach an experiment to v1,
seed traces (and a span tree) under that experiment, then invoke the
``opik migrate dataset`` CLI command and verify the destination project
ends up with a copy of the experiment, its traces, and its spans, with
FKs pointing at the destination dataset / version / items.

Skipped unless an Opik backend is reachable (the e2e suite runs against
the docker-compose stack -- see ``.agents/skills/python-sdk/testing.md``
for setup).
"""

from __future__ import annotations

from typing import Iterator, List, Tuple

import pytest
from click.testing import CliRunner

import opik
from opik.cli import cli

from ..conftest import random_chars
from ..testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)


class TestMigrateDatasetExperimentsCascade:
    """End-to-end test: dataset + versions + experiment + traces + spans
    travel through ``opik migrate dataset`` together.
    """

    @pytest.fixture
    def source_project(self, opik_client: opik.Opik) -> Iterator[str]:
        # The source project is the destination of the e2e suite's
        # auto-created project (see configure_e2e_tests_env). We don't
        # create it explicitly; opik_client traces will land in it.
        yield PROJECT_NAME

    @pytest.fixture
    def destination_project(self, opik_client: opik.Opik) -> Iterator[str]:
        # Pre-create the destination project so --to-project resolves on
        # the migrate side. The migrate planner rejects unknown projects
        # by design (ProjectNotFoundError) to catch typos.
        name = f"migrate-dest-{random_chars()}"
        rest_client = opik_client._rest_client
        rest_client.projects.create_project(name=name)
        yield name

    @pytest.fixture
    def dataset_name(self) -> str:
        return f"migrate-src-dataset-{random_chars()}"

    @pytest.fixture
    def experiment_name(self) -> str:
        return f"migrate-src-experiment-{random_chars()}"

    def _build_source_state(
        self,
        opik_client: opik.Opik,
        *,
        dataset_name: str,
        experiment_name: str,
        source_project: str,
    ) -> Tuple[str, List[str], List[str]]:
        """Build a source dataset with two versions and one experiment
        whose items reference v1 items.

        Returns ``(experiment_id, source_trace_ids, source_item_ids)``.
        """
        dataset = opik_client.create_dataset(
            name=dataset_name,
            description="Source dataset for migrate cascade e2e",
            project_name=source_project,
        )
        # v1: two items.
        dataset.insert(
            [
                {
                    "input": {"q": "What is the capital of France?"},
                    "expected": {"a": "Paris"},
                },
                {
                    "input": {"q": "What is 2+2?"},
                    "expected": {"a": "4"},
                },
            ]
        )
        # v2: add one more item so version history has two distinct points.
        # Slice 2's replay walks both; Slice 3 reads version_remap[v1] when
        # remapping the experiment's dataset_version_id.
        dataset.insert(
            [
                {
                    "input": {"q": "What is the capital of Germany?"},
                    "expected": {"a": "Berlin"},
                }
            ]
        )

        items = list(dataset.__internal_api__stream_items_as_dataclasses__())
        # We attach the experiment to v1's two items; take the first two
        # (most-recently-inserted-first stream, so the two v1 items live
        # at the end of the list).
        v1_items = items[-2:]
        source_item_ids = [item.id for item in v1_items]

        # Emit one trace per item under the source project. Each trace
        # gets a single child span so the cascade has tree content to
        # round-trip.
        trace_ids: List[str] = []
        for item in v1_items:
            trace = opik_client.trace(
                name=f"task-{item.id[:8]}",
                input=item.get_content().get("input"),
                output={"a": "placeholder"},
                project_name=source_project,
            )
            opik_client.span(
                name=f"llm-call-{item.id[:8]}",
                type="llm",
                trace_id=trace.id,
                input=item.get_content().get("input"),
                output={"a": "placeholder"},
                project_name=source_project,
            )
            trace_ids.append(trace.id)
        opik_client.flush()

        # Wire the experiment to those traces.
        experiment = opik_client.create_experiment(
            dataset_name=dataset_name,
            name=experiment_name,
            experiment_config={"runner": "e2e-test"},
            tags=["cascade-e2e"],
            project_name=source_project,
        )
        import opik.id_helpers as id_helpers_module
        from opik.rest_api.types.experiment_item import ExperimentItem

        opik_client._rest_client.experiments.create_experiment_items(
            experiment_items=[
                ExperimentItem(
                    id=id_helpers_module.generate_id(),
                    experiment_id=experiment.id,
                    dataset_item_id=item.id,
                    trace_id=trace_id,
                )
                for item, trace_id in zip(v1_items, trace_ids)
            ]
        )
        return experiment.id, trace_ids, source_item_ids

    def test_migrate_dataset__cascades_experiment_and_traces_to_destination_project(
        self,
        opik_client: opik.Opik,
        source_project: str,
        destination_project: str,
        dataset_name: str,
        experiment_name: str,
    ) -> None:
        # ---- arrange ----
        source_experiment_id, source_trace_ids, _ = self._build_source_state(
            opik_client,
            dataset_name=dataset_name,
            experiment_name=experiment_name,
            source_project=source_project,
        )

        # ---- act ----
        # The CLI builds its own opik.Opik client, so we invoke it the
        # same way users would: through Click. The runner uses the
        # ambient env (OPIK_URL_OVERRIDE, etc.) so it talks to the same
        # backend the fixture client is using.
        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "migrate",
                "dataset",
                dataset_name,
                "--from-project",
                source_project,
                "--to-project",
                destination_project,
            ],
            catch_exceptions=False,
        )
        assert result.exit_code == 0, result.output

        # ---- assert ----
        rest_client = opik_client._rest_client

        # 1) Destination dataset exists in the destination project under
        #    the original name (Slice 1 rename + create).
        dest_dataset = rest_client.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name, project_name=destination_project
        )
        assert dest_dataset.id is not None
        assert dest_dataset.id != source_experiment_id  # sanity

        # 2) Destination experiment exists under the destination project,
        #    referencing the destination dataset's id.
        dest_experiments = rest_client.experiments.find_experiments(
            dataset_id=dest_dataset.id,
            page=1,
            size=10,
        )
        assert dest_experiments.content is not None
        matched = [
            exp for exp in dest_experiments.content if exp.name == experiment_name
        ]
        assert len(matched) == 1, (
            f"Expected exactly one destination experiment named {experiment_name}, "
            f"got {len(matched)}: {[e.name for e in dest_experiments.content]}"
        )
        dest_exp = matched[0]
        assert dest_exp.id != source_experiment_id, (
            "Destination experiment should have a fresh id, not the source's"
        )
        assert dest_exp.project_name == destination_project
        # dataset_version_id should have been remapped via Slice 2's
        # version_remap (the source experiment was on v1; the destination
        # has its own v1 from the replay).
        assert dest_exp.dataset_version_id is not None

        # 3) Destination experiment has the same number of items as the
        #    source, with NEW trace_ids (not source_trace_ids).
        dest_items = list(
            rest_client.experiments.stream_experiment_items(
                experiment_id=dest_exp.id, limit=500
            )
        )
        assert len(dest_items) == len(source_trace_ids), (
            f"Destination has {len(dest_items)} items, expected {len(source_trace_ids)}"
        )
        dest_trace_ids = {item.trace_id for item in dest_items}
        assert dest_trace_ids.isdisjoint(set(source_trace_ids)), (
            "Destination experiment items should reference new trace ids, "
            "not the source's."
        )

        # 4) For each destination trace: trace exists in destination
        #    project (project_id should match) and has at least one span
        #    (the source had exactly one span per trace).
        for new_trace_id in dest_trace_ids:
            new_trace = rest_client.traces.get_trace_by_id(id=new_trace_id)
            assert new_trace.id == new_trace_id
            # The span tree was a single LLM span; assert spans exist.
            spans_page = rest_client.spans.get_spans_by_project(
                trace_id=new_trace_id, page=1, size=50
            )
            assert spans_page.content is not None and len(spans_page.content) >= 1, (
                f"Expected at least one span on the destination trace {new_trace_id}"
            )
            for span in spans_page.content:
                assert span.trace_id == new_trace_id

    def test_migrate_dataset__exclude_experiments__skips_cascade(
        self,
        opik_client: opik.Opik,
        source_project: str,
        destination_project: str,
        dataset_name: str,
        experiment_name: str,
    ) -> None:
        """--exclude-experiments leaves source experiments untouched and
        creates no destination experiments."""
        self._build_source_state(
            opik_client,
            dataset_name=dataset_name,
            experiment_name=experiment_name,
            source_project=source_project,
        )

        runner = CliRunner()
        result = runner.invoke(
            cli,
            [
                "migrate",
                "dataset",
                dataset_name,
                "--from-project",
                source_project,
                "--to-project",
                destination_project,
                "--exclude-experiments",
            ],
            catch_exceptions=False,
        )
        assert result.exit_code == 0, result.output

        rest_client = opik_client._rest_client
        dest_dataset = rest_client.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name, project_name=destination_project
        )
        dest_experiments = rest_client.experiments.find_experiments(
            dataset_id=dest_dataset.id, page=1, size=10
        )
        assert dest_experiments.content is None or len(dest_experiments.content) == 0, (
            "No destination experiments should exist when --exclude-experiments is set"
        )
