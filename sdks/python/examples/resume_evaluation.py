"""
Local demo for ``opik.evaluate_resume``.

Run it (against any backend you have configured for ``opik``):

    python examples/resume_evaluation.py

What it does, top to bottom:

  1. Creates a small sentiment-classification dataset.
  2. Runs ``opik.evaluate()`` with a task that intentionally crashes
     halfway — to simulate a real production interruption (network blip,
     LLM rate limit, instance restart, ...).
  3. Catches the crash and reports what got done.
  4. Calls ``opik.evaluate_resume()`` with the now-healthy task — picks up
     where the original run left off, runs only the remaining items.
  5. Prints the final converged state.

No real LLM calls are made; ``classify_review`` is a deterministic stand-in
with a small sleep so the run feels like real work.
"""

import time

import opik
from opik.evaluation import metrics


DATASET_NAME = "resume-demo-dataset"
EXPERIMENT_NAME = "resume-demo-experiment"

# (review text, expected sentiment) pairs — drive both the dataset and the
# fake classifier. Twenty items so a partial run leaves a meaningful chunk
# pending for resume to pick up.
REVIEWS = [
    ("I love this product!", "positive"),
    ("Worst experience ever.", "negative"),
    ("It was okay, nothing special.", "neutral"),
    ("Absolutely fantastic, highly recommend!", "positive"),
    ("Total waste of money.", "negative"),
    ("Mediocre at best.", "neutral"),
    ("Amazing quality and great service!", "positive"),
    ("I want a refund.", "negative"),
    ("Pretty good but room for improvement.", "neutral"),
    ("Five stars, no complaints.", "positive"),
    ("Returned it within a week.", "negative"),
    ("Does what it says on the tin.", "neutral"),
    ("Best purchase I've made all year!", "positive"),
    ("Stopped working after two days.", "negative"),
    ("Average product, average price.", "neutral"),
    ("Highly impressed by the build quality.", "positive"),
    ("Customer support was unhelpful.", "negative"),
    ("Acceptable for the price point.", "neutral"),
    ("Genuinely delighted with this.", "positive"),
    ("Misleading description, do not buy.", "negative"),
]

# Item index where the original run will crash (simulates a real outage
# part-way through). With 20 items, 12 leaves 8 pending for resume to do.
CRASH_ON_INDEX = 12


def make_dataset(opik_client: opik.Opik) -> opik.Dataset:
    """Recreate the demo dataset from scratch so the script is idempotent."""
    try:
        opik_client.delete_dataset(DATASET_NAME)
    except Exception:
        pass

    dataset = opik_client.create_dataset(DATASET_NAME)
    dataset.insert(
        [
            {
                "id": f"review-{i}",
                "input": {"review": text},
                "expected_sentiment": expected,
            }
            for i, (text, expected) in enumerate(REVIEWS)
        ]
    )
    return dataset


def classify_review(review_text: str) -> str:
    """Pretend to call an LLM; deterministic lookup against REVIEWS."""
    time.sleep(0.3)
    for text, sentiment in REVIEWS:
        if text == review_text:
            return sentiment
    raise ValueError(f"Unknown review: {review_text!r}")


def flaky_task(item):
    """Original task: crashes on a specific item to simulate an outage."""
    if item["id"] == f"review-{CRASH_ON_INDEX}":
        raise RuntimeError(
            f"Simulated outage processing {item['id']} "
            "(imagine an LLM rate limit or a network blip)"
        )
    return {"output": classify_review(item["input"]["review"])}


def healthy_task(item):
    """Same as ``flaky_task`` but with the simulated bug fixed."""
    return {"output": classify_review(item["input"]["review"])}


def completed_item_ids(experiment) -> set:
    """Dataset item ids with at least one successful experiment item."""
    return {
        item.dataset_item_id
        for item in experiment.get_items()
        if item.evaluation_task_output is not None
    }


def main() -> None:
    opik_client = opik.Opik()

    # ----- 1. Setup ------------------------------------------------------
    print("=" * 60)
    print("STAGE 1 — building the dataset")
    print("=" * 60)
    dataset = make_dataset(opik_client)
    print(f"Created dataset '{DATASET_NAME}' with {len(REVIEWS)} items")

    # ----- 2. Initial evaluation (crashes mid-way) -----------------------
    print()
    print("=" * 60)
    print("STAGE 2 — running evaluate() with a flaky task")
    print("=" * 60)
    print(f"Task will crash on item 'review-{CRASH_ON_INDEX}' ...")
    try:
        opik.evaluate(
            dataset=dataset,
            task=flaky_task,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping={"reference": "expected_sentiment"},
            experiment_name=EXPERIMENT_NAME,
            task_threads=1,
            verbose=0,
        )
    except RuntimeError as exc:
        print(f"Evaluation interrupted (as expected): {exc}")

    # ----- 3. Inspect the partial state ----------------------------------
    experiments = opik_client.get_experiments_by_name(EXPERIMENT_NAME)
    assert experiments, "Expected exactly one experiment to have been created"
    experiment_id = experiments[-1].id
    experiment = opik_client.get_experiment_by_id(experiment_id)

    completed = completed_item_ids(experiment)
    print()
    print(f"Experiment id: {experiment_id}")
    print(f"Completed so far: {len(completed)}/{len(REVIEWS)} items")
    print(f"Completed ids   : {sorted(completed)}")

    # ----- 4. Resume -----------------------------------------------------
    print()
    print("=" * 60)
    print("STAGE 3 — calling evaluate_resume() with the healthy task")
    print("=" * 60)
    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=healthy_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping={"reference": "expected_sentiment"},
        verbose=0,
    )

    print(f"Resume processed {len(resume_result.test_results)} items:")
    for test_result in resume_result.test_results:
        score = test_result.score_results[0]
        print(
            f"  - {test_result.test_case.dataset_item_id}: "
            f"{score.name} = {score.value}"
        )

    # ----- 5. Verify convergence -----------------------------------------
    print()
    print("=" * 60)
    print("STAGE 4 — final state")
    print("=" * 60)
    experiment = opik_client.get_experiment_by_id(experiment_id)
    completed = completed_item_ids(experiment)
    print(f"Completed now: {len(completed)}/{len(REVIEWS)} items")
    print(f"Completed ids: {sorted(completed)}")
    print(f"Experiment URL: {resume_result.experiment_url}")


if __name__ == "__main__":
    main()
