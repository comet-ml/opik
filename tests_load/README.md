# Load tests

This folder contains two things:

1. `tests/` — standalone CLI scripts for ad-hoc, one-off performance probes.
2. `suite/<target>/` — pytest-driven load-test suites that run on a schedule
   in CI and can also be triggered manually. Each subdirectory targets a
   different system under test (currently just `python_sdk/`); future
   targets (TypeScript SDK, backend-only, etc.) would be peers.

## Install Opik locally

Install Opik locally using the docker-compose deployment
([docs](https://www.comet.com/docs/opik/self-host/local_deployment)).

The Python SDK reads its configuration from environment variables or
`~/.opik.config`. For a local docker-compose install:

```bash
export OPIK_URL_OVERRIDE=http://localhost:5173/api/
```

## Python SDK load-test suite (`suite/python_sdk/`)

The suite covers four ingestion shapes against a local Opik installation.
Defaults are sized for a scheduled (weekly) run — not for PR checks — so
each scenario produces meaningful load. Use `--load-scale` to dial them
down for local smoke runs.

| File / scenario | Default volume |
| --- | --- |
| `test_ingestion_rate.py::test_many_traces_one_span_each` | 100k traces × 1 nested span, ~100 B payloads |
| `test_ingestion_rate.py::test_many_spans_per_trace` | 5k traces × 50 spans = 250k spans, ~100 B payloads |
| `test_heavy_payload.py::test_traces_with_one_megabyte_payload` | 500 traces × (1 MB in + 1 MB out) ≈ 1 GB |
| `test_heavy_payload.py::test_spans_with_heavy_payload` | 200 traces × 5 spans × (500 KB in + 500 KB out) ≈ 1 GB |
| `test_attachments.py::test_traces_with_explicit_attachments` | 500 traces × 2 × 50 KB attachments ≈ 50 MB, 1k uploads |
| `test_attachments.py::test_traces_with_implicit_attachments` | 500 traces × 400 KB base64 blob extracted as attachment |
| `test_bursts.py::test_burst_single_loop` | 50k traces, tight loop, no think-time |
| `test_bursts.py::test_spread_over_time` | 10k traces evenly spread over 10 minutes |
| `test_bursts.py::test_concurrent_writers_share_one_client` | 30 threads × 1k traces = 30k traces sharing one client |
| `test_bursts.py::test_concurrent_writers_race_stress` | 100 threads × 500 traces, no think-time, batcher flush interval monkey-patched 2 s → 5 ms — tuned specifically to surface missing-lock regressions in `BatchManager.flush_ready` (OPIK-6444 shape) |
| `test_dataset_items.py::test_dataset_insert_many_versions` | 50 sequential `Dataset.insert()` calls × 50 items × ~4 KB payload = 2.5k items across 50 versions. Verifies `dataset.get_items()` round-trips the full count — catches the multi-replica ClickHouse `COPY_VERSION_ITEMS` short-read truncation that drops items on prod (won't reproduce against single-replica localhost) |

Every test:

1. Logs the requested traces/spans (with attachments where applicable).
2. Calls `opik.flush_tracker()`.
3. Polls `search_traces` / `search_spans` / `attachments.attachment_list`
   until the expected number of items is visible, so the run only passes if
   every logged item lands.
4. Records per-phase timings (`logging`, `flush`, `verify`, `total`) to
   `tests_load/.last_run/<test_name>.json`.

### Setup

```bash
# Install the Opik SDK (from this repo, or `pip install opik` for released)
pip install -e sdks/python

# Install suite-specific deps
pip install -r tests_load/suite/python_sdk/requirements.txt

# Point the SDK at whichever Opik install you want to hit
export OPIK_URL_OVERRIDE=http://localhost:5173/api/   # full local stack
# export OPIK_URL_OVERRIDE=http://localhost:8080      # backend-only (./opik.sh --backend)
# export OPIK_URL_OVERRIDE=https://www.comet.com/opik/api/  OPIK_API_KEY=...  OPIK_WORKSPACE=...
```

The suite is environment-agnostic — it reads whatever OPIK_* variables are
set in the shell. Configuring those is the caller's responsibility.

### Run

```bash
cd tests_load
pytest suite/python_sdk                                 # serial
pytest suite/python_sdk -n auto --dist=worksteal        # parallel via pytest-xdist
```

The scheduled workflow runs with `-n auto --dist=worksteal`. Each
scenario uses a unique project name so worker isolation holds; the
shared backend will see meaningful concurrent load, which is itself
useful coverage.

### Scale up / down

Every scenario accepts a scale multiplier. Use it for quick smoke runs or
heavy bake-offs:

```bash
# Quick smoke (~10% of default volumes)
pytest suite/python_sdk --load-scale 0.1

# Heavy run (5x default)
OPIK_LOAD_SCALE=5 pytest suite/python_sdk
```

### Scheduled CI run

The GitHub Actions workflow [`.github/workflows/load_tests.yml`](../.github/workflows/load_tests.yml):

- Runs weekly on Sunday at 04:00 UTC.
- Can be triggered manually via `workflow_dispatch`.
- Stands up a fresh dockerised Opik backend, installs the SDK, runs the
  suite, and uploads `.last_run/` as a build artifact.

## Standalone scripts (`tests/`)

These are kept for ad-hoc experiments; they are not picked up by `pytest`.

- [test_trace_span_ingestion.py](tests/test_trace_span_ingestion.py) — log
  N traces and measure end-to-end latency.
- [test_trace_span_retrieval.py](tests/test_trace_span_retrieval.py) —
  search traces/spans in a project over a date range.
- [test_thread_ingestion.py](tests/test_thread_ingestion.py) — log threads
  with multiple traces and spans.
- [test_image_inference.py](tests/test_image_inference.py) — image generation
  inference probes for online-evaluation testing.
- [test_images_dataset_sample.py](tests/test_images_dataset_sample.py) —
  load a sample image dataset for playground/experiment testing.

Their dependencies are pinned in [`requirements.txt`](requirements.txt).
