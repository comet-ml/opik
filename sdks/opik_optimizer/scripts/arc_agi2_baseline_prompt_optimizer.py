"""
Phase 1: Optimize a stronger baseline ARC-AGI-2 code-generation prompt.

This script:
- Loads a small ARC-AGI-2 slice from HF (arc-agi-community/arc-agi-2).
- Starts from a code-gen system prompt and asks for Python
  `transform(grid: np.ndarray) -> np.ndarray`.
- Uses Hierarchical Reflective Optimizer (HRPO) with a high-end reasoning model
  to evolve the prompt based on execution feedback (code-gen + run on train).
- Writes the best prompt messages to stdout (can be persisted by redirecting).

Run:
    python sdks/opik_optimizer/scripts/arc_agi2_baseline_prompt_optimizer.py
"""

from __future__ import annotations

import json
import re
import traceback
from typing import Any
from collections.abc import Sequence

import numpy as np
from opik.evaluation.metrics import score_result
from opik_optimizer import ChatPrompt, HierarchicalReflectiveOptimizer
from opik_optimizer.datasets import arc_agi2

# Config: high-end reasoning model for optimizer; small slice for speed.
DATASET_SPLIT = "train"
DATASET_COUNT = 30
DATASET_START = 0
TEST_MODE = False
SEED = 42
PASS_AT_K = 2

EVAL_MODEL = "gemini/gemini-1.5-flash-002"  # model used for candidate eval calls
REASONING_MODEL = "gemini/gemini-3-pro-preview"  # model used inside HRPO
EVAL_TEMPERATURE = 0.0
REASONING_TEMPERATURE = 0.7
HRPO_MAX_TRIALS = 4
HRPO_THREADS = 8

# Starting system prompt
SYSTEM_PROMPT = """You are an expert ARC-AGI solver. Infer the transformation from training pairs and write Python code:
def transform(grid: np.ndarray) -> np.ndarray
- Analyze objects, colors, symmetries. Keep rules concise.
- Use numpy only (import numpy as np). Return a numpy array.
- Provide one or two code blocks (primary + optional fallback)."""

USER_PROMPT = """Training examples (input -> output):
{training_examples_text}

Test inputs:
{test_inputs_text}

Respond with ONE or TWO python code blocks (```python ...```), each defining transform(grid: np.ndarray) -> np.ndarray."""


def _format_grid(grid: Sequence[Sequence[int]]) -> str:
    return "\n".join(" ".join(str(c) for c in row) for row in grid)


def _render_examples(examples: list[dict[str, Any]]) -> str:
    parts = []
    for idx, ex in enumerate(examples):
        parts.append(
            f"Train {idx}\nInput:\n{_format_grid(ex.get('input', []))}\nOutput:\n{_format_grid(ex.get('output', []))}"
        )
    return "\n\n".join(parts)


def _render_test_inputs(test_inputs: list[list[list[int]]]) -> str:
    return "\n\n".join(
        f"Test {idx} input:\n{_format_grid(grid)}"
        for idx, grid in enumerate(test_inputs)
    )


def _extract_code_blocks(text: str) -> list[str]:
    return re.findall(r"```python\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE)


def _format_diff(pred: np.ndarray, truth: np.ndarray) -> str:
    rows, cols = truth.shape
    lines: list[str] = []
    for r in range(rows):
        row = []
        for c in range(cols):
            if pred.shape != truth.shape:
                row.append("x")
            elif pred[r, c] == truth[r, c]:
                row.append(str(int(pred[r, c])))
            else:
                row.append(f"{int(pred[r, c])}/{int(truth[r, c])}")
        lines.append(" ".join(row))
    return "\n".join(lines)


def _run_transform(code: str, grid: list[list[int]]) -> tuple[bool, Any, str]:
    locals_dict: dict[str, Any] = {}
    globals_dict = {"np": np}
    try:
        exec(code, globals_dict, locals_dict)  # noqa: S102 deliberate exec for sandboxed code
    except Exception:
        return False, None, f"Exec error: {traceback.format_exc(limit=1)}"

    transform_fn = locals_dict.get("transform") or globals_dict.get("transform")
    if not callable(transform_fn):
        return False, None, "No transform(grid) defined."
    try:
        arr_in = np.array(grid, dtype=int)
        arr_out = transform_fn(arr_in)
        if not isinstance(arr_out, np.ndarray):
            return False, None, "transform must return numpy array."
        return True, arr_out, ""
    except Exception:
        return False, None, f"Runtime error: {traceback.format_exc(limit=1)}"


def _is_valid_matrix(matrix: Any, gold: list[list[int]]) -> bool:
    if not isinstance(matrix, list):
        return False
    if not matrix or not isinstance(matrix[0], list):
        return False
    if len(matrix) != len(gold) or len(matrix[0]) != len(gold[0]):
        return False
    return all(
        matrix[r][c] == gold[r][c]
        for r in range(len(gold))
        for c in range(len(gold[0]))
    )


def _evaluate_code_candidate(
    code: str,
    train_in: list[list[list[int]]],
    train_out: list[list[list[int]]],
    test_in: list[list[list[int]]],
) -> dict[str, Any]:
    train_feedback: list[str] = []
    exact_scores: list[float] = []
    soft_scores: list[float] = []

    for idx, (iin, oout) in enumerate(zip(train_in, train_out, strict=True)):
        ok, pred, err = _run_transform(code, iin)
        if not ok or pred is None:
            train_feedback.append(f"Train {idx}: fail - {err}")
            exact_scores.append(0.0)
            soft_scores.append(0.0)
            continue
        truth = np.array(oout, dtype=int)
        if pred.shape != truth.shape:
            train_feedback.append(
                f"Train {idx}: shape mismatch {pred.shape} vs {truth.shape}"
            )
            exact_scores.append(0.0)
            soft_scores.append(0.0)
            continue
        exact = float(np.array_equal(pred, truth))
        soft = float(np.mean(pred == truth)) if truth.size else 1.0
        diff = _format_diff(pred, truth)
        train_feedback.append(f"Train {idx}: exact={exact:.1f} soft={soft:.2f}\n{diff}")
        exact_scores.append(exact)
        soft_scores.append(soft)

    test_outputs: list[list[list[int]]] = []
    for iin in test_in:
        ok, pred, err = _run_transform(code, iin)
        if not ok or pred is None:
            test_outputs.append([])
            continue
        test_outputs.append(pred.astype(int).tolist())

    return {
        "train_exact": float(np.mean(exact_scores)) if exact_scores else 0.0,
        "train_soft": float(np.mean(soft_scores)) if soft_scores else 0.0,
        "train_feedback": " | ".join(train_feedback[:5]),
        "test_outputs": test_outputs,
    }


def arc_agi2_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    train_examples = dataset_item.get("training_examples") or []
    test_inputs = dataset_item.get("test_inputs") or []
    gold_outputs = dataset_item.get("test_outputs") or []

    code_blocks = _extract_code_blocks(llm_output)
    if not code_blocks:
        return score_result.ScoreResult(
            name="arc_agi2_accuracy",
            value=0.0,
            scoring_failed=False,
            reason="No python code block found.",
        )

    train_in = [ex.get("input") for ex in train_examples]
    train_out = [ex.get("output") for ex in train_examples]

    candidates = [
        _evaluate_code_candidate(code, train_in, train_out, test_inputs)
        for code in code_blocks
    ]
    candidates_sorted = sorted(
        candidates, key=lambda c: (c["train_exact"], c["train_soft"]), reverse=True
    )
    best = candidates_sorted[0]

    # Pass@K on test outputs if available
    best_score = 0.0
    if gold_outputs:
        for cand in candidates_sorted[:PASS_AT_K]:
            if len(cand["test_outputs"]) != len(gold_outputs):
                continue
            scores = [
                1.0 if _is_valid_matrix(pred, gold) else 0.0
                for pred, gold in zip(cand["test_outputs"], gold_outputs, strict=False)
            ]
            cand_score = sum(scores) / len(scores) if scores else 0.0
            best_score = max(best_score, cand_score)
    else:
        best_score = best["train_exact"]

    reason = (
        f"code_blocks={len(code_blocks)} "
        f"train_exact={best['train_exact']:.2f} train_soft={best['train_soft']:.2f} "
        f"{best['train_feedback']}"
    )
    return score_result.ScoreResult(
        name="arc_agi2_accuracy",
        value=best_score,
        scoring_failed=False,
        reason=reason,
    )


def build_prompt() -> ChatPrompt:
    return ChatPrompt(
        name="arc-agi2-baseline-optimizer",
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": USER_PROMPT},
        ],
        model=EVAL_MODEL,
        model_parameters={"temperature": EVAL_TEMPERATURE},
    )


def main() -> None:
    dataset = arc_agi2(
        split=DATASET_SPLIT,
        count=DATASET_COUNT,
        start=DATASET_START,
        test_mode=TEST_MODE,
        seed=SEED,
    )

    prompt = build_prompt()

    optimizer = HierarchicalReflectiveOptimizer(
        model=EVAL_MODEL,
        model_parameters={"temperature": EVAL_TEMPERATURE},
        reasoning_model=REASONING_MODEL,
        reasoning_model_parameters={"temperature": REASONING_TEMPERATURE},
        n_threads=HRPO_THREADS,
    )

    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=arc_agi2_metric,
        n_samples=DATASET_COUNT,
        max_trials=HRPO_MAX_TRIALS,
        project_name="ARC-AGI-2 Baseline Prompt Opt",
    )

    print("=== Best prompt messages ===")
    print(json.dumps(result.prompt.messages, indent=2))
    print(f"Final score: {result.score:.3f}")


if __name__ == "__main__":
    main()
