"""End-to-end load scenario for the full ``opik migrate dataset`` cascade.

Where ``test_migrate_cascade_read`` exercises only the cascade's read
primitives (``search_traces`` / ``search_spans`` with a bounded
``max_batch_size``), this scenario runs the **actual CLI command**
``opik migrate dataset NAME --to-project=…`` end-to-end against a real
backend and asserts the whole cascade landed: the destination project gets
the recreated experiment with its traces and spans.

This is the coverage the read-only scenario can't give — it wires the
OPIK-7152 page-size fix through the real command path
(``_copy_traces_and_spans`` → ``recreate_experiment`` → dataset
rename/replay), not just the two read calls.

Because ``opik migrate dataset`` mutates state (renames the source to
``<name>_v1`` and writes a destination dataset + experiment + traces +
spans), this is NOT safe to run against shared/persistent environments. It
is written for the Load Tests workflow, whose backend is a throwaway local
docker-compose Opik torn down at job end — so the rename + destination
writes need no cleanup. Do not point this at dev / staging / a customer
deployment.

Scale with ``--load-scale``; defaults give a sustainable ~40k spans that
completes well within the job budget while running the real cascade.
"""

from __future__ import annotations

import logging
import os
from typing import List

import opik
from opik import datetime_helpers, id_helpers
from opik.message_processing import messages
from opik.rest_api.types.experiment_item import ExperimentItem

from . import _helpers
from ._helpers import KB, Metrics

LOGGER = logging.getLogger("test_migrate_dataset_e2e")


def _seed(
    client: opik.Opik,
    *,
    dataset_name: str,
    project_name: str,
    trace_count: int,
    spans_per_trace: int,
    span_bytes: int,
    metrics: Metrics,
) -> None:
    """Seed a dataset + experiment with heavy-span traces, linked so the
    migrate cascade has an experiment to walk."""
    rest = client.rest_client

    dataset = client.get_or_create_dataset(name=dataset_name)
    dataset.insert([{"q": f"item-{i}"} for i in range(trace_count)])
    item_ids: List[str] = [it["id"] for it in dataset.get_items()][:trace_count]

    experiment_name = f"{dataset_name}-exp"
    rest.experiments.create_experiment(
        name=experiment_name, dataset_name=dataset_name, project_name=project_name
    )
    experiments = rest.experiments.find_experiments(dataset_id=dataset.id).content or []
    experiment_id = next(e.id for e in experiments if e.name == experiment_name)

    exp_items: List[ExperimentItem] = []
    with metrics.timer("seed"):
        for i in range(trace_count):
            trace_id = id_helpers.generate_id()
            client.__internal_api__trace__(
                id=trace_id,
                name=f"trace-{i}",
                input={"prompt": _helpers.random_text(1 * KB)},
                output={"completion": _helpers.random_text(1 * KB)},
                project_name=project_name,
                source="experiment",
            )
            for s in range(spans_per_trace):
                now = datetime_helpers.local_timestamp()
                client._streamer.put(
                    messages.CreateSpanMessage(
                        span_id=id_helpers.generate_id(),
                        trace_id=trace_id,
                        project_name=project_name,
                        parent_span_id=None,
                        name=f"span-{i}-{s}",
                        type="general",
                        start_time=now,
                        end_time=now,
                        input={"prompt": _helpers.random_text(span_bytes // 2)},
                        output={"completion": _helpers.random_text(span_bytes // 2)},
                        metadata=None,
                        tags=None,
                        usage=None,
                        model=None,
                        provider=None,
                        error_info=None,
                        total_cost=None,
                        last_updated_at=None,
                        source="experiment",
                        environment=None,
                    )
                )
            if i < len(item_ids):
                exp_items.append(
                    ExperimentItem(
                        experiment_id=experiment_id,
                        dataset_item_id=item_ids[i],
                        trace_id=trace_id,
                    )
                )
            if (i + 1) % 5 == 0:
                client.flush()
                rest.experiments.create_experiment_items(experiment_items=exp_items)
                exp_items = []
                LOGGER.info("seed progress: %d/%d traces", i + 1, trace_count)

    client.flush()
    if exp_items:
        rest.experiments.create_experiment_items(experiment_items=exp_items)
    LOGGER.info("seed complete: %d traces x %d spans", trace_count, spans_per_trace)


def test_migrate_dataset_e2e(metrics: Metrics, load_scale: float) -> None:
    """Seed a dataset+experiment, run the real ``opik migrate dataset`` CLI,
    and assert the destination project received the recreated experiment with
    its traces and spans.

    Mutating (renames source, writes destination) — throwaway-backend only.
    """
    from click.testing import CliRunner
    from opik.cli import cli

    trace_count: int = int(20 * load_scale)
    spans_per_trace: int = int(os.getenv("OPIK_MIGRATE_SPANS_PER_TRACE", "2000"))
    span_bytes: int = 25 * KB
    src_project: str = _helpers.unique_project_name("migrate-e2e-src")
    dst_project: str = _helpers.unique_project_name("migrate-e2e-dst")
    dataset_name: str = f"migrate-e2e-{id_helpers.generate_id()[:8]}"

    metrics["src_project"] = src_project
    metrics["dst_project"] = dst_project
    metrics["dataset_name"] = dataset_name
    metrics["trace_count"] = trace_count
    metrics["spans_per_trace"] = spans_per_trace
    metrics["total_spans"] = trace_count * spans_per_trace

    client = _helpers.opik_client()
    # The CLI requires --to-project to already exist.
    client.rest_client.projects.create_project(name=dst_project)

    _seed(
        client,
        dataset_name=dataset_name,
        project_name=src_project,
        trace_count=trace_count,
        spans_per_trace=spans_per_trace,
        span_bytes=span_bytes,
        metrics=metrics,
    )

    LOGGER.info(
        "running: opik migrate dataset %r --to-project=%r", dataset_name, dst_project
    )
    with metrics.timer("migrate"):
        result = CliRunner().invoke(
            cli,
            ["migrate", "dataset", dataset_name, "--to-project", dst_project],
            catch_exceptions=True,
        )
    metrics["migrate_exit_code"] = result.exit_code
    LOGGER.info("migrate exit_code=%s", result.exit_code)
    if result.exit_code != 0:
        LOGGER.error("migrate output tail:\n%s", (result.output or "")[-2000:])

    assert result.exit_code == 0, (
        f"opik migrate dataset exited {result.exit_code}; "
        f"output tail: {(result.output or '')[-1000:]}"
    )

    # Verify the destination cascade landed: the experiment was recreated
    # under the destination dataset, and its traces + spans are present in
    # the destination project.
    dst_dataset = client.get_dataset(name=dataset_name, project_name=dst_project)
    dst_experiments = (
        client.rest_client.experiments.find_experiments(
            dataset_id=dst_dataset.id
        ).content
        or []
    )
    metrics["dst_experiment_count"] = len(dst_experiments)
    assert dst_experiments, "no experiment recreated at the destination"

    dst_traces = client.search_traces(
        project_name=dst_project, max_results=trace_count + 10, truncate=True
    )
    metrics["dst_trace_count"] = len(dst_traces)
    assert len(dst_traces) >= trace_count, (
        f"expected >= {trace_count} destination traces, got {len(dst_traces)}"
    )

    dst_spans = client.search_spans(
        project_name=dst_project,
        trace_id=dst_traces[0].id,
        max_results=spans_per_trace + 10,
        truncate=True,
    )
    metrics["dst_spans_on_sample_trace"] = len(dst_spans)
    assert len(dst_spans) > 0, "destination trace has no spans — cascade dropped spans"
    LOGGER.info(
        "e2e ok: %d dst experiments, %d dst traces, %d spans on sample trace",
        len(dst_experiments),
        len(dst_traces),
        len(dst_spans),
    )
