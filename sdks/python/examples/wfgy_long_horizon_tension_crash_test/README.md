# WFGY long-horizon "tension crash test" (Opik example)

This example shows how to use Opik to run a long-horizon stress test as a first-class evaluation workflow.

The focus is on three Opik concepts:

1. **Datasets**  
   Store each test case as a dataset item, versioned and deduplicated.

2. **Prompt library**  
   Use a structured evaluation prompt that can be versioned and reused across runs.

3. **Experiments and traces**  
   Run evaluations that create experiments and traces, even for long-running agents.

The goal is not to provide a single perfect ground truth.  
The goal is to provide a reproducible crash test so you can compare models and prompts on the same set of hard cases.

References in the Opik docs (names only):

- Manage datasets  
- Prompt management  
- Evaluate your LLM and experiments  


## What you get in the Opik UI

After running this example you should see:

- A **dataset** that contains stress-test items, one per case.  
- A **prompted evaluation task** that calls your model on each dataset item.  
- An **experiment** that logs traces for each item, including nested spans for long-running behavior.

You can open any trace to inspect:

- the original long-form input  
- the model output  
- the evaluation spans and hallucination metrics  


## Folder layout

This folder is self-contained and only depends on the Opik Python SDK.

- `sample_dataset.jsonl`  
  Small example dataset with a handful of long-horizon test cases.

- `create_dataset.py`  
  Creates (or fetches) an Opik dataset and inserts items from `sample_dataset.jsonl`.  
  Dataset items are deduplicated by Opik so re-running this script is safe.

- `run_evaluation.py`  
  Runs an evaluation over the dataset, calls the LLM, logs an experiment and traces,  
  and computes a simple hallucination-style metric.


## How to run

Run the scripts from inside this folder.

From the repository root:

```bash
cd sdks/python/examples/wfgy_long_horizon_tension_crash_test
````

Then:

```bash
python create_dataset.py
python run_evaluation.py
```

Requirements:

* `OPIK_API_KEY` set in your environment
* `OPIK_WORKSPACE` set to your Opik workspace name
* an LLM provider configured for the model you select in `run_evaluation.py`

If you do not have a model configured, you can still adapt the script to run a dry run that only exercises the dataset and experiment wiring.

## Dataset schema (recommended)

Opik datasets work best when each item has an `input` field and, optionally, an `expected_output` field.

For this crash test, `expected_output` can be omitted or left empty if you use non-ground-truth metrics.

Recommended fields per item:

* `input`
  The test case prompt given to the model. This can include instructions, constraints and context.

* `expected_output` (optional)
  A reference answer or canonical format. Only needed if your metrics require ground truth.

* `metadata` (optional)
  A dictionary for extra fields such as

  * `question_id` (for example `Q001`)
  * `domain` (for example `math`, `safety`, `planning`)
  * `pack_version` or `pack_hash` for reproducibility

* `tags` (optional)
  A list of tags, for example `["wfgy", "long_horizon", "crash_test"]`.

## Evaluation notes

When you run `run_evaluation.py`, the evaluation task receives each dataset item as a Python dict.

The task should return another dict with the fields that metrics need.
Opik merges the dataset item dict and the task output dict before scoring.

Typical patterns:

* Use `input` from the dataset item as the main model prompt.
* Store the model output under a key such as `output` or `answer`.
* Add any extra fields you want to score on, for example `format_ok` or `constraint_violations`.

This example is designed so you can:

* Evaluate a **filtered subset** of items by using `dataset_filter_string`.
* Evaluate a **sampled subset** when you have many cases by using a `dataset_sampler`.
* Link **experiments** and traces back to the same dataset and prompt configuration.

## Why this fits long-horizon behavior

For long-running agents and complex chains, the trace is part of the result.

This example encourages you to:

* Log intermediate reasoning steps as spans.
* Inspect where the agent drifts, collapses or hallucinates along a long chain of decisions.
* Compare experiments across models and prompt setups on the same dataset.

For a quick smoke test you can sample five to ten items from the dataset.
For a full crash test you can load a larger pack of cases and run filtered or sampled evaluations on top of it.

## Dataset origin

The example test cases in `sample_dataset.jsonl` are adapted from an open-source long-horizon evaluation pack maintained at:

* [https://github.com/onestardao/WFGY](https://github.com/onestardao/WFGY)

Only a small subset is embedded here for demonstration.
The full pack contains a larger collection of long-horizon, model-agnostic stress-test questions that can be used with Opik or other evaluation frameworks.

