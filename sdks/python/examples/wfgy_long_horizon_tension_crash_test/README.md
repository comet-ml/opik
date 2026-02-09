# WFGY long-horizon "tension crash test" (Opik example)

This example shows how to use Opik to run a long-horizon stress test as a first-class evaluation workflow:

1) store each test case as a Dataset item (versioned, deduplicated)
2) manage the prompt template in the Prompt Library (versioned)
3) run Experiments (Evaluation) and inspect traces for long-running behavior

The goal is not “one perfect ground truth”.
It is a reproducible long-horizon crash test that lets you compare models / prompts across the same set of hard cases.

References (official docs):
- Manage Datasets
- Prompt Management
- Evaluate your LLM / Experiments


## What you get in Opik UI

After running this example you should see:

- a Dataset that contains stress-test items (each item is one case)
- a Prompt entry with versions (so you can pin runs to a prompt version)
- an Experiment that logs traces (including nested steps) for each dataset item


## Folder layout

This folder is intentionally self-contained:

- create_dataset.py
  Creates (or fetches) a dataset and inserts items.
  Dataset items are deduplicated by Opik so re-running is safe.

- register_prompts.py
  Creates a prompt in the prompt library (or creates a new version).

- run_evaluation.py
  Runs an evaluation experiment on the dataset and logs traces.


## How to run (clean style)

Run the scripts from inside this folder:

1) open a terminal at the repo root
2) go into the example folder
3) run the scripts in order

Example:

cd sdks/python/examples/wfgy_long_horizon_tension_crash_test

python create_dataset.py
python register_prompts.py
python run_evaluation.py


## Dataset schema (recommended)

Opik datasets work best when each item has an `input` and (optionally) an `expected_output`.
For this crash test, `expected_output` can be omitted or left empty if you are using non-ground-truth metrics.

Recommended fields per item:

- input: the test case prompt (string)
- expected_output: optional
- tags / data: optional metadata (difficulty, category, question_id, etc)


## Evaluation notes (important)

Opik evaluation expects:
- the evaluation task returns a dictionary
- keys must match what your scoring metrics expect
Opik merges the dataset item dict + the task output dict before scoring.

This example is designed so you can:
- evaluate a filtered subset using dataset_filter_string
- evaluate a sampled subset using a dataset_sampler (useful when you have 131 items)
- link prompts to experiments so every run is traceable to a prompt version


## Why this fits “long-horizon” well

For long-running agents, the trace is the product.
This example uses tracked steps so you can inspect where the agent drifts, collapses, or hallucinates.

If you want a quick smoke run, sample 5–10 items.
If you want the full run, load all 131 items and use filters / sampling to compare models.
