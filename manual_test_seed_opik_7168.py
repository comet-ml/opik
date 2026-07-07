"""Seed a source dataset + N experiments for the OPIK-7168 resume manual test.

Points at whatever the Opik SDK env resolves to. For the ad-hoc PR env:

    export OPIK_URL_OVERRIDE="https://pr-7358.dev.comet.com/api"
    export OPIK_API_KEY="<key for that env>"
    export OPIK_WORKSPACE="<workspace>"

Then:

    python manual_test_seed_opik_7168.py                 # seed, print the migrate command
    python manual_test_seed_opik_7168.py --experiments 9 --spans-per-trace 6

Each experiment gets one trace with a small span tree; bump --spans-per-trace
(and --experiments) so the cascade is slow enough to interrupt around
experiment 5. After seeding, the script prints the exact `opik migrate` command
and where the checkpoint file will land.

This is a throwaway manual-test helper, not part of the SDK or the test suite.
"""

from __future__ import annotations

import argparse
import datetime as dt
import uuid

import opik
from opik import id_helpers
from opik.rest_api.types.dataset_item_write import DatasetItemWrite
from opik.rest_api.types.experiment_item import ExperimentItem
from opik.rest_api.types.span_write import SpanWrite
from opik.rest_api.types.trace_write import TraceWrite


def _seed_experiment(
    rest,
    *,
    experiment_name: str,
    dataset_name: str,
    dataset_id: str,
    dataset_version_id: str,
    project_name: str,
    item_id: str,
    spans_per_trace: int,
) -> None:
    now = dt.datetime.now(dt.timezone.utc)
    trace_id = id_helpers.generate_id()
    rest.traces.create_traces(
        traces=[
            TraceWrite(
                id=trace_id,
                project_name=project_name,
                name=f"{experiment_name}-trace",
                start_time=now,
                end_time=now + dt.timedelta(milliseconds=50),
                input={"q": experiment_name},
                output={"a": "seeded"},
                metadata={"seed": True},
            )
        ]
    )

    span_writes = []
    root_id = id_helpers.generate_id()
    span_writes.append(
        SpanWrite(
            id=root_id,
            project_name=project_name,
            trace_id=trace_id,
            name="root",
            start_time=now,
            end_time=now + dt.timedelta(milliseconds=50),
            input={"q": experiment_name},
            output={"a": "seeded"},
        )
    )
    for s in range(max(0, spans_per_trace - 1)):
        span_writes.append(
            SpanWrite(
                id=id_helpers.generate_id(),
                project_name=project_name,
                trace_id=trace_id,
                parent_span_id=root_id,
                name=f"child-{s}",
                type="llm",
                start_time=now,
                end_time=now + dt.timedelta(milliseconds=30),
                input={"prompt": f"p{s}"},
                output={"completion": f"c{s}"},
            )
        )
    rest.spans.create_spans(spans=span_writes)

    experiment_id = id_helpers.generate_id()
    rest.experiments.create_experiment(
        id=experiment_id,
        name=experiment_name,
        dataset_name=dataset_name,
        project_name=project_name,
        dataset_version_id=dataset_version_id,
    )
    rest.experiments.create_experiment_items(
        experiment_items=[
            ExperimentItem(
                id=id_helpers.generate_id(),
                experiment_id=experiment_id,
                dataset_item_id=item_id,
                trace_id=trace_id,
            )
        ]
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--experiments", type=int, default=9)
    ap.add_argument("--spans-per-trace", type=int, default=6)
    ap.add_argument(
        "--source-project", default=f"opik7168-src-{uuid.uuid4().hex[:6]}"
    )
    ap.add_argument(
        "--to-project", default=f"opik7168-dst-{uuid.uuid4().hex[:6]}"
    )
    ap.add_argument("--dataset", default=f"opik7168-ds-{uuid.uuid4().hex[:6]}")
    args = ap.parse_args()

    client = opik.Opik()
    rest = client.rest_client

    # Ensure both projects exist (the destination must pre-exist for migrate).
    for project in (args.source_project, args.to_project):
        try:
            rest.projects.create_project(name=project)
        except Exception as exc:  # already exists is fine
            print(f"  (project {project}: {type(exc).__name__} — assuming it exists)")

    # Source dataset, single version, one item per experiment.
    rest.datasets.create_dataset(
        name=args.dataset,
        description="OPIK-7168 resume manual test source",
        project_name=args.source_project,
    )
    source = client.get_dataset(name=args.dataset, project_name=args.source_project)
    rest.datasets.create_or_update_dataset_items(
        dataset_id=source.id,
        items=[
            DatasetItemWrite(source="manual", data={"q": f"Q{i}", "a": f"A{i}"})
            for i in range(args.experiments)
        ],
        batch_group_id=id_helpers.generate_id(),
    )
    v1 = rest.datasets.list_dataset_versions(id=source.id, page=1, size=1).content[0]

    # Resolve item ids at v1.
    last_id = None
    items = []
    while True:
        kw = {
            "dataset_name": args.dataset,
            "steam_limit": 2000,
            "dataset_version": v1.version_hash,
        }
        if last_id:
            kw["last_retrieved_id"] = last_id
        chunk = b""
        for part in rest.datasets.stream_dataset_items(**kw):
            chunk += part
        import json as _json

        page = [
            _json.loads(line) for line in chunk.decode().splitlines() if line.strip()
        ]
        if not page:
            break
        items.extend(page)
        if len(page) < 2000:
            break
        last_id = page[-1]["id"]
    item_id_by_q = {it["data"]["q"]: it["id"] for it in items}

    for i in range(args.experiments):
        name = f"opik7168-exp-{i}"
        _seed_experiment(
            rest,
            experiment_name=name,
            dataset_name=args.dataset,
            dataset_id=source.id,
            dataset_version_id=v1.id,
            project_name=args.source_project,
            item_id=item_id_by_q[f"Q{i}"],
            spans_per_trace=args.spans_per_trace,
        )
        print(f"  seeded {name}")

    client.flush()

    print("\n=== SEED COMPLETE ===")
    print(f"workspace       : {getattr(client, '_workspace', '?')}")
    print(f"source dataset  : {args.dataset}  (project {args.source_project})")
    print(f"experiments     : {args.experiments}")
    print(f"destination proj: {args.to_project}")
    print("\nRun the migration (from a directory where the checkpoint can land):")
    print(
        f"  opik migrate dataset '{args.dataset}' "
        f"--to-project='{args.to_project}' --audit-log ./audit.json"
    )
    print(
        "\nWhen the bar reaches ~experiment 5/9, interrupt it "
        "(kill -9 <pid> for the true OOM path, or pull the VPN)."
    )
    print("Inspect:  cat ./opik-migrate-checkpoint-*.json")
    print("Resume :  re-run the exact same migrate command.")


if __name__ == "__main__":
    main()
