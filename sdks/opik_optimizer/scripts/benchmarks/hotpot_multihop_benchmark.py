"""HotpotQA multi-hop benchmark using shared benchmark agent components.

This script is a thin runnable example that reuses the centralized benchmark
agent implementation from `benchmarks/packages/hotpot/agent.py`.
"""

from __future__ import annotations

import logging
import os
import random
from typing import Any

from benchmarks.packages.hotpot.metrics import hotpot_f1
from opik_optimizer import HierarchicalReflectiveOptimizer
from opik_optimizer.datasets import hotpot
from opik_optimizer.utils.logging import setup_logging
from benchmarks.packages.hotpot.agent import build_hotpot_agent
from benchmarks.packages.hotpot.prompts import build_hotpot_prompts
from benchmarks.packages.hotpot.agent import bm25_wikipedia_search

PROJECT_HEADER = "Hotpot QA Multihop Optimization"
SEED = 42
MODEL_NAME = "openai/gpt-4.1-mini"
MODEL_PARAMS = {"temperature": 1.0}
NUM_PASSAGES = 5
TRAIN_COUNT = 150
VALIDATION_COUNT = 300
TEST_COUNT = 300

# Disable tqdm progress bars (used by bm25s)
os.environ["TQDM_DISABLE"] = "1"

setup_logging()
logger = logging.getLogger(__name__)
random.seed(SEED)


def print_header() -> None:
    print("=" * 80)
    print(PROJECT_HEADER)
    print("=" * 80)
    print()
    print("This benchmark uses the centralized Hotpot multi-hop benchmark agent.")
    print("- Multi-hop retrieval pipeline")
    print("- Multiple optimizable prompts")
    print("- Wikipedia search integration")
    print()


def load_datasets() -> tuple[Any, Any, Any]:
    print("Loading datasets...")
    train = hotpot(count=TRAIN_COUNT, split="train", dataset_name="hotpot_train")
    val = hotpot(
        count=VALIDATION_COUNT,
        split="validation",
        dataset_name="hotpot_validation",
    )
    test = hotpot(count=TEST_COUNT, split="test", dataset_name="hotpot_test")
    print(f"  - Train: {len(train.get_items())} samples")
    print(f"  - Validation: {len(val.get_items())} samples")
    print(f"  - Test: {len(test.get_items())} samples")
    print()
    return train, val, test


def run_optimization(
    *,
    agent: Any,
    initial_prompts: dict[str, Any],
    train_dataset: Any,
    validation_dataset: Any,
) -> Any:
    optimizer = HierarchicalReflectiveOptimizer(
        model=MODEL_NAME,
        model_parameters=MODEL_PARAMS,
        seed=SEED,
    )
    print(f"Running multi-prompt optimization ({optimizer.__class__.__name__})...")
    return optimizer.optimize_prompt(
        prompt=initial_prompts,
        dataset=train_dataset,
        validation_dataset=validation_dataset,
        metric=hotpot_f1,
        agent=agent,
        max_trials=50,
    )


def main() -> None:
    print_header()
    train_dataset, validation_dataset, test_dataset = load_datasets()

    print(f"Search function: {bm25_wikipedia_search.__name__}")
    print()
    try:
        bm25_wikipedia_search("warmup", 1)
    except Exception:
        logger.exception("Wikipedia warmup failed")
        raise

    agent = build_hotpot_agent(
        model_name=MODEL_NAME,
        model_parameters=MODEL_PARAMS,
    )
    # Keep passage count configurable in script context.
    agent.num_passages = NUM_PASSAGES
    initial_prompts = build_hotpot_prompts()

    print("=" * 80)
    print("OPTIMIZATION")
    print("=" * 80)
    print("This will optimize ALL prompts in the multi-hop pipeline:")
    for name in initial_prompts:
        print(f"  - {name}")
    print()
    print(f"Training on hotpot_train ({TRAIN_COUNT} samples)...")
    print(f"Validation on hotpot_validation ({VALIDATION_COUNT} samples)...")
    print()

    opt_result = run_optimization(
        agent=agent,
        initial_prompts=initial_prompts,
        train_dataset=train_dataset,
        validation_dataset=validation_dataset,
    )
    print(f"Optimization best score: {opt_result.score:.4f}")
    print(f"Optimization rounds: {len(getattr(opt_result, 'history', []) or [])}")

    print("=" * 80)
    print("TESTING")
    print("=" * 80)
    print("Evaluating optimized prompts on test set...")

    score = HierarchicalReflectiveOptimizer(
        model=MODEL_NAME,
        model_parameters=MODEL_PARAMS,
        seed=SEED,
    ).evaluate_prompt(
        prompt=opt_result.prompt,
        dataset=test_dataset,
        metric=hotpot_f1,
        n_threads=4,
        agent=agent,
    )
    print(f"Test score (hotpot_f1): {score:.4f}")


if __name__ == "__main__":
    main()
