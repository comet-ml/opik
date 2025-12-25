"""
Phase 2: Per-task HRPO solver with code-gen + execution feedback.

Workflow:
- Uses a baseline code-gen prompt (can be the output from baseline_prompt_optimizer).
- For each ARC-AGI-2 task slice (default 20 tasks), prompts for one/two code blocks,
  executes them on train examples, and scores test outputs (pass@2 over code blocks).
- Designed to be run per task; add a coordinator to launch multiple HRPO jobs with
  different seeds/models/temps for ensembling and voting (preferred scaling path).
"""

from __future__ import annotations

import os
import time
import re
import traceback
from typing import Any
from collections.abc import Sequence

import numpy as np
from opik.evaluation.metrics import score_result
from opik_optimizer import ChatPrompt, HierarchicalReflectiveOptimizer
from opik_optimizer.datasets import arc_agi2

# Config knobs kept flat for simplicity. Pass@2 is handled via multiple code blocks.
DATASET_SPLIT = "train"
DATASET_COUNT = 20
DATASET_START = 0
TEST_MODE = False  # Set True to force embedded sample.
ARC_AGI2_DATA_DIR = os.getenv("ARC_AGI2_DATA_DIR")  # optional override

EVAL_MODEL = "openai/gpt-5.2"
REASONING_MODEL = "openai/gpt-5.2"
EVAL_TEMPERATURE = 1.0
REASONING_TEMPERATURE = 1.0
HRPO_MAX_TRIALS = 15
HRPO_THREADS = 8
SEED = 42
PASS_AT_K = 2
DEBUG_LOG = os.getenv("ARC_AGI2_DEBUG", "0") not in {"", "0", "false", "False"}

DATASET_NAME = os.getenv("ARC_AGI2_DATASET_NAME") or (
    f"arc_agi2_{DATASET_SPLIT}_{DATASET_COUNT}_{int(time.time())}"
)

def _log_debug(message: str) -> None:
    if DEBUG_LOG:
        print(f"[DEBUG] {message}")

# MIT-licensed baseline prompt adapted from Poetiq ARC-AGI solver (SOLVER_PROMPT_1).
SYSTEM_PROMPT = """You are an expert in solving Abstract Reasoning Corpus (ARC) tasks by writing Python code. Your goal is to analyze input-output examples and create a 'transform' function that correctly transforms any given input grid into the corresponding output grid.

Here's how to approach the problem:

**1. Analyze the Examples:**
  *   Identify the key objects in the input and output grids (e.g., shapes, lines, regions).
  *   Determine the relationships between these objects (e.g., spatial arrangement, color, size).
  *   Identify the operations that transform the input objects and relationships into the output objects and relationships (e.g., rotation, reflection, color change, object addition/removal).
  *   Consider the grid dimensions, symmetries, and other visual features.

**2. Formulate a Hypothesis:**
  *   Based on your analysis, formulate a transformation rule that works consistently across all examples.
  *   Express the rule as a sequence of image manipulation operations.
  *   Prioritize simpler rules first.
  *   Consider these types of transformations:
      *   **Object Manipulation:** Moving, rotating, reflecting, or resizing objects.
      *   **Color Changes:** Changing the color of specific objects or regions.
      *   **Spatial Arrangements:** Rearranging the objects in a specific pattern.
      *   **Object Addition/Removal:** Adding or removing objects based on certain criteria.

**3. Implement the Code:**
  *   Write a Python function called `transform(grid: np.ndarray) -> np.ndarray` that implements your transformation rule.
  *   Use NumPy for array manipulations. Other standard libraries are also available.
  *   Write modular code with clear variable names and comments to explain the logic behind each step.
  *   Document your code clearly, explaining the transformation rule in the docstring.
  *   Handle edge cases and invalid inputs gracefully.

**4. Test and Refine:**
  *   Test your code on all examples. If it fails for any example, refine your hypothesis and code.
  *   Use debugging techniques to identify and fix errors.
  *   Ensure your code handles edge cases and invalid inputs gracefully.

**5. Output:**
  *   Provide a brief explanation of your solution.
  *   Include the complete Python code for the `transform` function within a single markdown code block.
  *   Do not include any `__name__ == "__main__"` block or any code outside the function definition.

Safety and format constraints:
- Use only NumPy (`import numpy as np` if you need it); no other imports or libraries are allowed.
- Keep all helper logic inside the single code block with `transform`; no extra files, I/O, network, randomness, or subprocesses.
- Ensure `transform` returns a NumPy array of ints matching the expected grid shape unless the rule requires a different shape.
- Avoid any mention (even in comments/strings) of banned tokens: `os`, `sys`, `pathlib`, `subprocess`, `open`, `eval`, `exec`, `requests`, `httpx`, `pickle`, `json`, `importlib`, or `__import__`.
- Use safe NumPy checks: never do `if array:` or array comparisons to scalars without `.any()`/`.all()`; prefer `np.array_equal`, `np.any`, `np.all`.
- Before finalizing, mentally run your code on each training pair: ensure output shape matches exactly, colors are correct, and there are no shape off-by-ones or dtype issues.

Respond with ONE or TWO python code blocks (```python ...```), each defining transform(grid: np.ndarray) -> np.ndarray."""

USER_PROMPT = """Training examples (input -> output):
{training_examples_text}

Shapes (rows x cols):
{shape_summary}

Test inputs:
{test_inputs_text}

Respond with ONE or TWO python code blocks (```python ...```), each defining:
def transform(grid: np.ndarray) -> np.ndarray
"""


def _extract_code_blocks(text: str) -> list[str]:
    """Return all python code blocks found in the LLM response."""
    blocks = re.findall(r"```python\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE)
    _log_debug(f"Found {len(blocks)} code blocks")
    return blocks


def _validate_code_block(code: str) -> tuple[bool, str]:
    """Reject code blocks that import anything other than numpy or use dangerous builtins."""
    disallowed_tokens = [
        "__import__",
        "subprocess",
        "os.",
        "sys.",
        "pathlib",
        "shutil",
        "open(",
        "eval(",
        "exec(",
        "requests",
        "httpx",
        "importlib",
        "pickle",
        "json",
    ]
    for tok in disallowed_tokens:
        if tok in code:
            return False, f"Disallowed token '{tok}'"

    import_re = re.compile(r"^\s*(from\s+([a-zA-Z0-9_\.]+)\s+import|import\s+([a-zA-Z0-9_\.]+))")
    for line in code.splitlines():
        m = import_re.match(line)
        if not m:
            continue
        module = m.group(2) or m.group(3) or ""
        if not module.startswith("numpy"):
            return False, f"Disallowed import '{line.strip()}'"
    return True, ""


def _format_grid(grid: Sequence[Sequence[int]]) -> str:
    return "\n".join(" ".join(str(c) for c in row) for row in grid)


def _grid_shape(grid: Sequence[Sequence[int]]) -> str:
    try:
        rows = len(grid)
        cols = len(grid[0]) if rows else 0
        # If ragged, flag it explicitly
        ragged = any(len(r) != cols for r in grid)
        shape = f"{rows}x{cols}"
        return f"{shape}{' (ragged)' if ragged else ''}"
    except Exception:
        return "unknown"


def _render_shape_summary(train_examples: list[dict[str, Any]], test_inputs: list[list[list[int]]]) -> str:
    parts: list[str] = []
    for idx, ex in enumerate(train_examples):
        parts.append(
            f"train {idx}: { _grid_shape(ex.get('input', [])) } -> { _grid_shape(ex.get('output', [])) }"
        )
    for idx, grid in enumerate(test_inputs):
        parts.append(f"test {idx}: { _grid_shape(grid) }")
    return "\n".join(parts)


def _is_valid_matrix(matrix: Any, gold_matrix: list[list[int]]) -> tuple[bool, str]:
    if not isinstance(matrix, list):
        return False, "Matrix must be a list of lists."
    if not matrix:
        return False, "Matrix must have at least one row."
    if not isinstance(matrix[0], list):
        return False, "Rows must be lists."
    n_rows = len(matrix)
    n_cols = len(matrix[0])
    for row in matrix:
        if not isinstance(row, list):
            return False, "Rows must be lists."
        if len(row) != n_cols:
            return False, "Rows must be the same length."
        for cell in row:
            if not isinstance(cell, int):
                return False, "Cells must be integers."

    gold_rows = len(gold_matrix)
    gold_cols = len(gold_matrix[0])
    if (n_rows, n_cols) != (gold_rows, gold_cols):
        return (
            False,
            f"Shape mismatch; expected {gold_rows}x{gold_cols}, got {n_rows}x{n_cols}.",
        )

    mismatches = [
        (r, c)
        for r in range(n_rows)
        for c in range(n_cols)
        if matrix[r][c] != gold_matrix[r][c]
    ]
    if not mismatches:
        return True, "Exact match."
    preview = mismatches[:10]
    return False, f"{len(mismatches)} mismatches (first few: {preview})."


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
    """Exec transform(grid) defined in code; returns success flag, output or None, and error text."""
    locals_dict: dict[str, Any] = {}
    globals_dict = {"np": np}
    _log_debug("Executing candidate transform on grid:")
    _log_debug(_format_grid(grid))
    # Validate input grid rectangularity to avoid ragged array conversion crashes.
    if grid and any(len(row) != len(grid[0]) for row in grid):
        return False, None, "Input grid is ragged (rows have different lengths)."
    try:
        exec(code, globals_dict, locals_dict)  # noqa: S102 - deliberate sandboxed exec
    except Exception:
        return False, None, f"Exec error: {traceback.format_exc(limit=1)}"

    transform_fn = locals_dict.get("transform") or globals_dict.get("transform")
    if not callable(transform_fn):
        return False, None, "No transform(grid) function defined."

    try:
        arr_in = np.array(grid, dtype=int)
        arr_out = transform_fn(arr_in)
        if not isinstance(arr_out, np.ndarray):
            return False, None, "transform must return a numpy array."
        arr_out = np.asarray(arr_out, dtype=int)
        return True, arr_out, ""
    except Exception:
        return False, None, f"Runtime error: {traceback.format_exc(limit=1)}"


def _evaluate_code_candidate(
    code: str,
    train_in: list[list[list[int]]],
    train_out: list[list[list[int]]],
    test_in: list[list[list[int]]],
) -> dict[str, Any]:
    """Run a single code candidate on train/test and return scores + outputs + feedback."""
    _log_debug("Evaluating code candidate:")
    _log_debug(code[:500])
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

    # Generate test outputs
    test_outputs: list[list[list[int]]] = []
    test_errors: list[str] = []
    for idx, iin in enumerate(test_in):
        ok, pred, err = _run_transform(code, iin)
        if not ok or pred is None:
            test_errors.append(f"Test {idx}: {err}")
            test_outputs.append([])
            continue
        test_outputs.append(pred.astype(int).tolist())

    return {
        "train_exact": float(np.mean(exact_scores)) if exact_scores else 0.0,
        "train_soft": float(np.mean(soft_scores)) if soft_scores else 0.0,
        "train_feedback": " | ".join(train_feedback[:5]),
        "test_outputs": test_outputs,
        "test_errors": test_errors,
    }


def arc_agi2_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    """Score by executing generated code; uses pass@2 over multiple code blocks."""
    gold_outputs = dataset_item.get("test_outputs") or []
    train_examples = dataset_item.get("training_examples") or []
    test_inputs = dataset_item.get("test_inputs") or []

    code_blocks = _extract_code_blocks(llm_output)
    if not code_blocks:
        return score_result.ScoreResult(
            name="arc_agi2_accuracy",
            value=0.0,
            scoring_failed=False,
            reason="No python code block found in response.",
        )

    train_in = [ex.get("input") for ex in train_examples]
    train_out = [ex.get("output") for ex in train_examples]

    valid_blocks: list[str] = []
    rejected: list[str] = []
    for code in code_blocks:
        ok, reason = _validate_code_block(code)
        if ok:
            valid_blocks.append(code)
        else:
            rejected.append(reason)

    if not valid_blocks:
        return score_result.ScoreResult(
            name="arc_agi2_accuracy",
            value=0.0,
            scoring_failed=False,
            reason=f"All code blocks rejected: {' | '.join(rejected[:3])}",
        )

    candidates = [
        _evaluate_code_candidate(code, train_in, train_out, test_inputs)
        for code in valid_blocks
    ]
    # Pick best by train_exact then soft
    candidates_sorted = sorted(
        candidates,
        key=lambda c: (c["train_exact"], c["train_soft"]),
        reverse=True,
    )
    best = candidates_sorted[0]
    reason_parts = [
        f"code_blocks={len(code_blocks)}",
        f"best_train_exact={best['train_exact']:.2f}",
        f"best_train_soft={best['train_soft']:.2f}",
        best["train_feedback"],
    ]
    if best["test_errors"]:
        reason_parts.append(f"test_errors: {' | '.join(best['test_errors'][:3])}")

    if not gold_outputs:
        return score_result.ScoreResult(
            name="arc_agi2_accuracy",
            value=best["train_exact"],
            scoring_failed=False,
            reason=" | ".join(reason_parts),
        )

    # Compute pass@k style: best candidate (pass@1) or any of top K (pass@2)
    evaluated_candidates = candidates_sorted[:PASS_AT_K]
    best_score = 0.0
    best_reason = " | ".join(reason_parts)
    for cand in evaluated_candidates:
        if len(cand["test_outputs"]) != len(gold_outputs):
            continue
        scores = []
        for pred, gold in zip(cand["test_outputs"], gold_outputs, strict=False):
            ok, _ = _is_valid_matrix(pred, gold)
            scores.append(1.0 if ok else 0.0)
        candidate_score = sum(scores) / len(scores) if scores else 0.0
        if candidate_score >= best_score:
            best_score = candidate_score
            best_reason = (
                f"{'pass@2' if PASS_AT_K > 1 else 'pass@1'} score={best_score:.2f} | "
                f"train_exact={cand['train_exact']:.2f} | "
                f"{cand['train_feedback']}"
            )

    return score_result.ScoreResult(
        name="arc_agi2_accuracy",
        value=best_score,
        scoring_failed=False,
        reason=best_reason,
    )


def build_prompt() -> ChatPrompt:
    return ChatPrompt(
        name="arc-agi2-hrpo-baseline",
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
        dataset_name=DATASET_NAME,
        test_mode=TEST_MODE,
        seed=SEED,
    )

    if DEBUG_LOG:
        items = dataset.get_items(1)
        if not items:
            raise RuntimeError("Dataset returned no items")
        first_item = items[0]
        train_text = first_item.get("training_examples_text")
        test_text = first_item.get("test_inputs_text")
        shape_summary = _render_shape_summary(
            first_item.get("training_examples") or [], first_item.get("test_inputs") or []
        )
        _log_debug(f"Sample task id: {first_item.get('task_id')}")
        _log_debug("Train (ASCII):")
        _log_debug(train_text or "<empty>")
        _log_debug("Test (ASCII):")
        _log_debug(test_text or "<empty>")
        _log_debug("Prompt messages with templating applied:")
        prompt = build_prompt()
        msgs = prompt.get_messages(
            {
                **first_item,
                "shape_summary": shape_summary,
            }
        )
        for idx, msg in enumerate(msgs):
            _log_debug(f"msg[{idx}] role={msg['role']} len={len(msg.get('content',''))}")
            _log_debug(msg["content"])

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
        project_name="ARC-AGI-2 HRPO",
    )

    print(
        f"ARC-AGI-2 HRPO complete. Final score: {result.score:.3f} | trials: {len(result.history)}"
    )
    print(f"Best prompt name: {result.prompt.name}")


if __name__ == "__main__":
    main()
