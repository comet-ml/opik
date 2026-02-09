# WFGY Long Horizon Tension Crash Test (Opik Example)

This example shows how to use Opik to run a **long-horizon stress test** on LLMs using a “tension crash test” style TXT pack.

The goal is not to claim a universal eval standard. The goal is to demonstrate a **reproducible workflow** using Opik’s core building blocks:

1. **Datasets** (store each problem as a dataset item, versioned)
2. **Prompt management** (store the runner prompt as a versioned asset)
3. **Experiments / Evaluation** (run an evaluation that produces traces + metrics)

If you can run this example end-to-end, you have a working template for long-running / multi-step agent traces and stress testing.

## What this example includes

* A small **sample dataset** (JSONL) with a few problems
* Scripts to:

  * create / update an Opik **dataset** from JSONL
  * create / fetch a versioned Opik **prompt**
  * run **evaluation** to generate an **experiment** with traces and metrics
* Optional: a loader that can parse a full TXT pack into JSONL items

## Folder layout

* `sample_dataset.jsonl`
  Small dataset to keep the example lightweight and PR-friendly.
* `create_dataset.py`
  Creates or updates the dataset in Opik and loads items from JSONL.
* `register_prompt.py`
  Creates a versioned prompt in Opik Prompt Library (and can fetch history).
* `run_evaluation.py`
  Runs `evaluate(...)` to create an experiment and track traces + metrics.
* `parse_wfgy_pack.py` (optional)
  Parses a full TXT pack into JSONL items that match the dataset schema.

## Dataset item schema

Each dataset item is a JSON object with (minimum):

* `input`: string
  The prompt payload for the model (the actual test task).
* `metadata`: object
  Suggested fields:

  * `question_id`: e.g. `Q001`
  * `domain`: e.g. `math`, `policy`, `safety`
  * `pack_version`: e.g. `wfgy_v3`
  * `pack_hash`: optional, for reproducibility
* `tags`: list of strings (optional)
  Example: `["wfgy", "long_horizon", "crash_test"]`

Opik datasets are versioned, so you can update items over time while keeping historical experiment results comparable.

## Prerequisites

* Python 3.10+
* An Opik workspace (cloud or self-hosted)
* Opik configured locally (typically via environment variables)

If you plan to run against an LLM provider (OpenAI, etc.), you will also need that provider’s API key.

## Install dependencies

From the repo root:

* Install Opik Python SDK (and your model provider SDK if needed)

Example (provider optional):

* `pip install opik`
* `pip install openai` (optional)

Note: this example intentionally keeps provider usage optional. You can run a “plumbing only” dry-run without a model to verify dataset/prompt/experiment wiring.

## Step 1: Create or update the dataset

Run:

* `python sdks/python/examples/wfgy_long_horizon_tension_crash_test/create_dataset.py`

What you should see in the Opik UI:

* A dataset created (or updated) named something like `wfgy_long_horizon_sample`
* A version created/updated after items are inserted from `sample_dataset.jsonl`

## Step 2: Register a versioned runner prompt

Run:

* `python sdks/python/examples/wfgy_long_horizon_tension_crash_test/register_prompt.py`

What you should see in the Opik UI:

* A prompt created in Prompt Library (versioned)
* You can later pin a specific prompt version (commit hash) for reproducible experiments

## Step 3: Run evaluation to create an experiment

Run:

* `python sdks/python/examples/wfgy_long_horizon_tension_crash_test/run_evaluation.py`

What you should see in the Opik UI:

* An **experiment** created for this run
* Traces/spans recorded per dataset item
* Evaluation metrics recorded for each item (and aggregated)

## Metrics and “crash” signals

The first version of this example should stay simple:

* Use at least one built-in metric (or a basic correctness check if you provide expected outputs)
* Optionally add a lightweight “format violation” or “constraint violation” signal

The core purpose is to demonstrate:

* long-horizon traces
* dataset versioning
* prompt versioning
* experiment tracking

## Optional: Load the full WFGY TXT pack (131 items)

This PR-friendly example only ships a small JSONL sample.

If you have a full TXT pack locally, you can convert it into JSONL items and load it:

1. Convert TXT -> JSONL

   * `python sdks/python/examples/wfgy_long_horizon_tension_crash_test/parse_wfgy_pack.py --input /path/to/pack.txt --output wfgy_full.jsonl`

2. Load JSONL into Opik dataset

   * Update `create_dataset.py` to read `wfgy_full.jsonl` (or pass a path arg, if implemented)

This keeps the upstream repo lightweight while still supporting full-scale stress tests.

## Expected outcome

After completing the steps:

* You can browse which problems a model solves vs fails (dataset items + experiment results)
* You can compare experiments across:

  * models
  * prompt versions (pinned commits)
  * dataset versions
* You can inspect traces to see where long-horizon behavior drifts or collapses
