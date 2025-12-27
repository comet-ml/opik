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
import opik_optimizer
from opik.evaluation.metrics import score_result
from opik_optimizer import (
    ChatPrompt,
    HierarchicalReflectiveOptimizer,
    MultiMetricObjective,
)
from opik_optimizer.datasets import arc_agi2
try:  # Optional pretty debug output
    from rich.console import Console
    from rich.text import Text
    from rich.columns import Columns
    from rich.panel import Panel

    _RICH_AVAILABLE = True
    _rich_console = Console(
        force_terminal=True, color_system="truecolor", force_jupyter=False, soft_wrap=True, width=120
    )
    _RICH_INIT_ERROR = None
except Exception:  # pragma: no cover - rich not always installed
    _RICH_AVAILABLE = False
    _rich_console = None
    _RICH_INIT_ERROR = "rich import failed"

# Config knobs kept flat for simplicity. Pass@2 is handled via multiple code blocks.
DATASET_SPLIT = "train"
DATASET_COUNT = 1  # FIXME: run per-task; add a driver loop to iterate tasks when scaling out
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
N_SAMPLES_PER_TRIAL = 4  # sample multiple completions per trial to approximate pass@2 across runs
EVAL_COMPLETIONS_PER_CALL = 4  # request multiple completions per model call; metric will evaluate all code blocks

DATASET_NAME = os.getenv("ARC_AGI2_DATASET_NAME") or (
    f"arc_agi2_{DATASET_SPLIT}_{DATASET_COUNT}_{int(time.time())}"
)
# Multi-metric weights (normalized) used for the composite objective.
LIKENESS_WEIGHT_TEST = 0.3
LIKENESS_WEIGHT_TRAIN = 0.3
LABEL_IOU_WEIGHT = 0.3
_WEIGHTS_RAW = [1.0, LIKENESS_WEIGHT_TEST, LABEL_IOU_WEIGHT]
_WEIGHTS_NORM = [w / sum(_WEIGHTS_RAW) for w in _WEIGHTS_RAW]

def _log_debug(message: str) -> None:
    if DEBUG_LOG:
        print(message)

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
- CRITICAL OUTPUT ENCODING RULE: each cell must be exactly one plain integer color index. Never emit or represent cell values as strings, floats, fractions/ratios (e.g., `7/5`, `5/7`, `2/7`, `7/2`), tuples, lists, or any mixed/heterogeneous value types. Do not encode uncertainty with composite symbols—choose one integer per cell. Before returning, normalize and validate the output array so it is strictly integer-typed (e.g., `out = np.asarray(out, dtype=int)`) and contains only valid discrete cell values (prefer 0–9 unless the task shows otherwise).
- Avoid any mention (even in comments/strings) of banned tokens: `os`, `sys`, `pathlib`, `subprocess`, `open`, `eval`, `exec`, `requests`, `httpx`, `pickle`, `json`, `importlib`, or `__import__`.
- Output grids must contain only integer values 0–9; never emit overlays like `a/b`, strings, or floats. Do not print diffs—just return the grid.
- Use safe NumPy checks: never do `if array:` or array comparisons to scalars without `.any()`/`.all()`; prefer `np.array_equal`, `np.any`, `np.all`.
- Before finalizing, mentally run your code on each training pair: ensure output shape matches exactly, colors are correct, dtype is integer, and there are no shape off-by-ones.
- Respond ONLY with code blocks; avoid extra prose that could contain banned substrings. Before you answer, scan your entire response (code + comments) for any banned tokens and remove them.

Respond with ONE python code block (```python ...```), defining transform(grid: np.ndarray) -> np.ndarray."""

USER_PROMPT = """Training examples (input -> output):
{training_examples_text}

Shapes (rows x cols):
{shape_summary}

Test inputs:
{test_inputs_text}

Respond with ONE python code block (```python ...```), defining:
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


def _rich_color_for_value(value: int) -> str:
    palette = [
        "#ffffff",  # 0 (white)
        "#1f77b4",  # 1
        "#d62728",  # 2 (red)
        "#2ca02c",  # 3
        "#ff7f0e",  # 4
        "#9467bd",  # 5 (purple)
        "#17becf",  # 6 (cyan)
        "#cccccc",  # 7 (light gray background)
        "#8c564b",  # 8
        "#e377c2",  # 9
    ]
    idx = int(value) if 0 <= int(value) < len(palette) else 0
    return palette[idx]


def _render_rich_grid(grid: Sequence[Sequence[int]]) -> Text:
    """Render a grid as colored blocks for debug viewing (requires rich)."""
    text = Text()
    for row in grid:
        for cell in row:
            color = _rich_color_for_value(int(cell))
            text.append("██", style=color)
        text.append("\n")
    return text


def _render_legend(values: set[int]) -> Text:
    legend = Text("Legend: ")
    for v in sorted(values):
        legend.append(f" {v} ", style=f"on {_rich_color_for_value(v)}")
    return legend


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


def _render_ascii_columns(columns: list[str], headers: list[str]) -> str:
    """Render multiple text columns side-by-side with headers."""
    split_cols = [col.splitlines() if col else [] for col in columns]
    widths = [max((len(line) for line in col), default=len(head)) for col, head in zip(split_cols, headers, strict=False)]
    rows = max((len(col) for col in split_cols), default=0)

    lines: list[str] = []
    header_line = " │ ".join(head.center(width) for head, width in zip(headers, widths, strict=False))
    separator = "─┼─".join("─" * width for width in widths)
    lines.append(header_line)
    lines.append(separator)
    for row_idx in range(rows):
        parts = []
        for col_idx, col in enumerate(split_cols):
            value = col[row_idx] if row_idx < len(col) else ""
            parts.append(value.ljust(widths[col_idx]))
        lines.append(" │ ".join(parts))
    return "\n".join(lines)


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


def _approx_match_score(pred: np.ndarray, truth: np.ndarray) -> float:
    """Fraction of matching cells; requires same shape."""
    if pred.shape != truth.shape:
        return 0.0
    if truth.size == 0:
        return 1.0
    raw = np.mean(pred == truth)
    return float(np.nan_to_num(raw, posinf=0.0, neginf=0.0))


def _label_iou(pred: np.ndarray, truth: np.ndarray) -> float:
    """Mean IOU over labels present in truth; requires same shape."""
    if pred.shape != truth.shape or truth.size == 0:
        return 0.0
    labels = np.unique(truth)
    if labels.size == 0:
        return 0.0
    ious = []
    for label in labels:
        pred_mask = pred == label
        truth_mask = truth == label
        union = np.logical_or(pred_mask, truth_mask).sum()
        if union == 0:
            continue
        inter = np.logical_and(pred_mask, truth_mask).sum()
        ious.append(inter / union)
    if not ious:
        return 0.0
    return float(np.mean(ious))


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
                row.append(f"{int(pred[r, c])}|{int(truth[r, c])}")
        lines.append(" ".join(row))
    return "\n".join(lines)


def _run_transform(code: str, grid: list[list[int]]) -> tuple[bool, Any, str]:
    """Exec transform(grid) defined in code; returns success flag, output or None, and error text."""
    locals_dict: dict[str, Any] = {}
    globals_dict = {"np": np}
    if _RICH_AVAILABLE and _rich_console:
        _rich_console.print("Executing candidate transform on grid (colorized):")
        _rich_console.print(_render_rich_grid(grid))
    else:
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
        arr_out = np.asarray(arr_out)
        if not np.isfinite(arr_out).all():
            return False, None, "transform returned non-finite values."
        arr_out = arr_out.astype(int, copy=False)
        if arr_out.ndim < 2:
            arr_out = np.expand_dims(arr_out, axis=list(range(2 - arr_out.ndim)))
        if np.any(arr_out < 0) or np.any(arr_out > 9):
            return False, None, "transform returned values outside expected 0–9 range."
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
    if _RICH_AVAILABLE and _rich_console:
        snippet = code.splitlines()
        preview = "\n".join(snippet[: min(8, len(snippet))])
        _rich_console.print(f"Candidate preview (lines={len(snippet)}):\n{preview}")
    else:
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
        mismatches = [
            (r, c)
            for r in range(pred.shape[0])
            for c in range(pred.shape[1])
            if pred[r, c] != truth[r, c]
        ]
        preview = mismatches[:10]
        train_feedback.append(
            f"Train {idx}: exact={exact:.1f} likeness={soft:.2f} "
            f"mismatch_count={len(mismatches)} preview={preview}"
        )
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


def _compute_arc_agi2_scores(
    dataset_item: dict[str, Any], llm_output: str
) -> dict[str, Any]:
    """Compute and cache exact / approx / IOU + composite score for a response."""
    cache = dataset_item.setdefault("_arc_metric_cache", {})
    if llm_output in cache:
        return cache[llm_output]

    gold_outputs = dataset_item.get("test_outputs") or []
    train_examples = dataset_item.get("training_examples") or []
    test_inputs = dataset_item.get("test_inputs") or []

    code_blocks = _extract_code_blocks(llm_output)
    if not code_blocks:
        result = {
            "value": 0.0,
            "exact": 0.0,
            "approx": 0.0,
            "label_iou": 0.0,
            "reason": "No python code block found in response.",
            "metadata": {},
        }
        cache[llm_output] = result
        return result

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
        result = {
            "value": 0.0,
            "exact": 0.0,
            "approx": 0.0,
            "label_iou": 0.0,
            "reason": f"All code blocks rejected: {' | '.join(rejected[:3])}",
            "metadata": {},
        }
        cache[llm_output] = result
        return result

    candidates = [
        _evaluate_code_candidate(code, train_in, train_out, test_inputs)
        for code in valid_blocks
    ]
    candidates_sorted = sorted(
        candidates, key=lambda c: (c["train_exact"], c["train_soft"]), reverse=True
    )
    best = candidates_sorted[0]
    reason_parts = [
        f"code_blocks={len(code_blocks)}",
        f"best_train_exact={best['train_exact']:.2f}",
        f"best_train_likeness={best['train_soft']:.2f}",
        best["train_feedback"],
    ]
    if best["test_errors"]:
        reason_parts.append(f"test_errors: {' | '.join(best['test_errors'][:3])}")

    if not gold_outputs:
        soft_reward = best["train_soft"] * LIKENESS_WEIGHT_TRAIN
        value = max(best["train_exact"], soft_reward)
        reason_parts.append(f"train_approx_match_reward={soft_reward:.2f}")
        result = {
            "value": value,
            "exact": best["train_exact"],
            "approx": best["train_soft"],
            "label_iou": 0.0,
            "reason": " | ".join(reason_parts),
            "metadata": {},
        }
        cache[llm_output] = result
        return result

    evaluated_candidates = candidates_sorted[:PASS_AT_K]
    best_score = 0.0
    best_likeness = 0.0
    best_mismatch_summary = ""
    best_reason = " | ".join(reason_parts)
    best_candidate_for_debug: dict[str, Any] | None = None
    best_candidate_likeness = 0.0
    best_candidate_exact = 0.0
    best_candidate_iou = 0.0
    best_swap_summary = ""
    for cand in evaluated_candidates:
        if len(cand["test_outputs"]) != len(gold_outputs):
            continue
        exact_scores = []
        likeness_scores = []
        iou_scores = []
        mismatch_counts: list[int] = []
        mismatch_coords: list[str] = []
        swap_counts: dict[tuple[int, int], int] = {}
        for pred, gold in zip(cand["test_outputs"], gold_outputs, strict=False):
            ok, _ = _is_valid_matrix(pred, gold)
            exact_scores.append(1.0 if ok else 0.0)
            pred_arr = np.array(pred, dtype=int)
            gold_arr = np.array(gold, dtype=int)
            likeness_scores.append(_approx_match_score(pred_arr, gold_arr))
            iou_scores.append(_label_iou(pred_arr, gold_arr))
            if pred_arr.shape == gold_arr.shape:
                mism_idx = np.argwhere(pred_arr != gold_arr)
                mismatch_counts.append(int(mism_idx.shape[0]))
                for coord in mism_idx[:3]:
                    mismatch_coords.append(
                        f"{tuple(int(x) for x in coord)}:{int(pred_arr[tuple(coord)])}|{int(gold_arr[tuple(coord)])}"
                    )
                for coord in mism_idx:
                    key = (int(pred_arr[tuple(coord)]), int(gold_arr[tuple(coord)]))
                    swap_counts[key] = swap_counts.get(key, 0) + 1
            else:
                mismatch_counts.append(-1)
        candidate_exact = sum(exact_scores) / len(exact_scores) if exact_scores else 0.0
        candidate_likeness = (
            sum(likeness_scores) / len(likeness_scores) if likeness_scores else 0.0
        )
        candidate_iou = sum(iou_scores) / len(iou_scores) if iou_scores else 0.0
        if candidate_exact > best_score or (
            candidate_exact == best_score and candidate_likeness > best_likeness
        ):
            best_score = candidate_exact
            best_likeness = candidate_likeness
            best_candidate_iou = candidate_iou
            best_candidate_exact = candidate_exact
            best_candidate_likeness = candidate_likeness
            best_candidate_for_debug = cand
            best_mismatch_summary = (
                f"test_mismatches={mismatch_counts} sample_coords={mismatch_coords[:5]}"
            )
            if swap_counts:
                swap_parts = [f"{k[0]}→{k[1]}:{v}" for k, v in sorted(swap_counts.items())]
                best_swap_summary = "swaps={" + ", ".join(swap_parts) + "}"
            else:
                best_swap_summary = ""
            best_reason = (
                f"{'pass@2' if PASS_AT_K > 1 else 'pass@1'} "
                f"exact={best_score:.2f} approx_match={candidate_likeness:.2f} label_iou={candidate_iou:.2f} | "
                f"train_exact={cand['train_exact']:.2f} | {cand['train_feedback']}"
            )

    likeness_reward = best_likeness * LIKENESS_WEIGHT_TEST
    iou_reward = best_candidate_iou * LABEL_IOU_WEIGHT
    value = max(
        best_score,
        likeness_reward,
        iou_reward,
        best["train_soft"] * LIKENESS_WEIGHT_TRAIN,
    )
    best_reason += (
        f" | {best_mismatch_summary} | {best_swap_summary} | "
        f"approx_match_reward={likeness_reward:.2f} label_iou_reward={iou_reward:.2f}"
    )

    if DEBUG_LOG and gold_outputs and best_candidate_for_debug and dataset_item.get("test_inputs"):
        test_inputs = dataset_item.get("test_inputs") or []
        ascii_input = _format_grid(test_inputs[0])
        ascii_expected = _format_grid(gold_outputs[0])
        ascii_predicted = _format_grid(best_candidate_for_debug["test_outputs"][0])
        if _RICH_AVAILABLE and _rich_console:
            try:
                inp = Panel(_render_rich_grid(test_inputs[0]), title="input", border_style="white")
                exp = Panel(_render_rich_grid(gold_outputs[0]), title="expected", border_style="white")
                pred = Panel(
                    _render_rich_grid(best_candidate_for_debug["test_outputs"][0]),
                    title="predicted",
                    border_style="white",
                )
                _rich_console.print(
                    "\nBest candidate vs expected for test[0] (input | expected | predicted):"
                )
                _rich_console.print(Columns([inp, exp, pred], expand=True, padding=2))
            except Exception:
                pass
        else:
            ascii_table = _render_ascii_columns(
                [ascii_input, ascii_expected, ascii_predicted],
                ["input", "expected", "predicted"],
            )
            _log_debug("Best candidate vs expected for test[0] (input │ expected │ predicted):")
            for line in ascii_table.splitlines():
                _log_debug(line)
        _log_debug(
            f"Test metrics: exact={best_candidate_exact:.2f} approx_match={best_candidate_likeness:.2f} "
            f"label_iou={best_candidate_iou:.2f} | {best_mismatch_summary} {best_swap_summary}"
        )

    result = {
        "value": value,
        "exact": best_score,
        "approx": best_likeness,
        "label_iou": best_candidate_iou,
        "reason": best_reason,
        "metadata": {
            "approx_match_reward": likeness_reward,
            "label_iou_reward": iou_reward,
            "test_mismatches": best_mismatch_summary,
            "swaps": best_swap_summary,
            "train_exact": best.get("train_exact", 0.0),
            "train_soft": best.get("train_soft", 0.0),
        },
    }
    cache[llm_output] = result
    return result


def arc_agi2_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    scores = _compute_arc_agi2_scores(dataset_item, llm_output)
    return score_result.ScoreResult(
        name="arc_agi2_accuracy",
        value=scores["value"],
        scoring_failed=False,
        reason=scores["reason"],
        metadata={
            "exact": scores["exact"],
            "approx_match": scores["approx"],
            "label_iou": scores["label_iou"],
            **scores.get("metadata", {}),
        },
    )


def arc_agi2_exact_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    scores = _compute_arc_agi2_scores(dataset_item, llm_output)
    return score_result.ScoreResult(
        name="arc_agi2_exact",
        value=scores["exact"],
        scoring_failed=False,
        reason=scores["reason"],
        metadata=scores.get("metadata"),
    )


def arc_agi2_approx_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    scores = _compute_arc_agi2_scores(dataset_item, llm_output)
    return score_result.ScoreResult(
        name="arc_agi2_approx_match",
        value=scores["approx"],
        scoring_failed=False,
        reason=scores["reason"],
        metadata=scores.get("metadata"),
    )


def arc_agi2_iou_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    scores = _compute_arc_agi2_scores(dataset_item, llm_output)
    return score_result.ScoreResult(
        name="arc_agi2_label_iou",
        value=scores["label_iou"],
        scoring_failed=False,
        reason=scores["reason"],
        metadata=scores.get("metadata"),
    )

# TODO: Optionally surface dtype/range validation status in the reason string if more diagnostic signal is needed.


def build_prompt() -> ChatPrompt:
    return ChatPrompt(
        name="arc-agi2-hrpo-baseline",
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": USER_PROMPT},
        ],
        model=EVAL_MODEL,
        model_parameters={"temperature": EVAL_TEMPERATURE, "n": EVAL_COMPLETIONS_PER_CALL},
    )


def main() -> None:
    # Use MultiMetricObjective so exact / approx_match / IOU are logged separately while
    # preserving a normalized composite score (weighted average).
    composite_metric = MultiMetricObjective(
        metrics=[arc_agi2_exact_metric, arc_agi2_approx_metric, arc_agi2_iou_metric],
        weights=_WEIGHTS_NORM,
        name="arc_agi2_multi",
    )

    dataset = arc_agi2(
        split=DATASET_SPLIT,
        count=DATASET_COUNT,
        start=DATASET_START,
        dataset_name=DATASET_NAME,
        test_mode=TEST_MODE,
        seed=SEED,
        prefer_presets=False,
    )

    if DEBUG_LOG:
        items = dataset.get_items(1)
        if not items:
            raise RuntimeError("Dataset returned no items")
        first_item = items[0]
        train_examples = first_item.get("training_examples") or []
        test_inputs = first_item.get("test_inputs") or []
        _log_debug(f"Sample task id: {first_item.get('task_id')}")
        if _RICH_AVAILABLE and _rich_console:
            if train_examples:
                _rich_console.print("\nSample ARC-AGI-2 grids (train examples):")
                for idx, example in enumerate(train_examples):
                    row_panels = [
                        Panel(
                            _render_rich_grid(example.get("input", [])),
                            title=f"train input #{idx}",
                            border_style="white",
                        ),
                        Panel(
                            _render_rich_grid(example.get("output", [])),
                            title=f"train output #{idx}",
                            border_style="white",
                        ),
                    ]
                    _rich_console.print(Columns(row_panels, expand=True, padding=2))
            if test_inputs:
                _rich_console.print("\nSample ARC-AGI-2 grids (test inputs):")
                for idx, grid in enumerate(test_inputs):
                    _rich_console.print(
                        Columns(
                            [
                                Panel(
                                    _render_rich_grid(grid),
                                    title=f"test input #{idx}",
                                    border_style="white",
                                )
                            ],
                            expand=True,
                            padding=2,
                        )
                    )
        else:
            ascii_columns: list[str] = []
            headers: list[str] = []
            for idx, example in enumerate(train_examples):
                ascii_columns.append(_format_grid(example.get("input", [])))
                headers.append(f"train input #{idx}")
                ascii_columns.append(_format_grid(example.get("output", [])))
                headers.append(f"train output #{idx}")
            for idx, grid in enumerate(test_inputs):
                ascii_columns.append(_format_grid(grid))
                headers.append(f"test input #{idx}")
            if ascii_columns:
                ascii_table = _render_ascii_columns(ascii_columns, headers)
                _log_debug(ascii_table)

    prompt = build_prompt()

    optimizer = HierarchicalReflectiveOptimizer(
        model=EVAL_MODEL,
        model_parameters={"temperature": EVAL_TEMPERATURE},
        reasoning_model=REASONING_MODEL,
        reasoning_model_parameters={"temperature": REASONING_TEMPERATURE},
        n_threads=HRPO_THREADS,
    )

    # Early baseline evaluation: if perfect, skip optimization loops.
    baseline_eval = optimizer.evaluate_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=composite_metric,
        n_samples=N_SAMPLES_PER_TRIAL,
        return_evaluation_result=True,
        verbose=1,
    )
    baseline_score = getattr(baseline_eval, "score", None)
    if baseline_score is None:
        baseline_score = 0.0
    if baseline_score == 0.0 and getattr(baseline_eval, "test_results", None):
        baseline_scores = [
            sr.value
            for test in baseline_eval.test_results
            for sr in getattr(test, "score_results", [])
        ]
        if baseline_scores:
            baseline_score = sum(baseline_scores) / len(baseline_scores)
    if DEBUG_LOG and getattr(baseline_eval, "test_results", None):
        first_sr = (
            baseline_eval.test_results[0].score_results[0]
            if baseline_eval.test_results[0].score_results
            else None
        )
        if first_sr:
            _log_debug(
                f"Baseline composite metric reason: {first_sr.reason or '<none>'}"
            )
    if baseline_score >= 0.999:
        print(f"Baseline is perfect (score={baseline_score:.3f}); skipping HRPO trials.")
        return

    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=composite_metric,
        n_samples=N_SAMPLES_PER_TRIAL,
        max_trials=HRPO_MAX_TRIALS,
        project_name="ARC-AGI-2 HRPO",
    )

    trials_used = len(result.history) if getattr(result, "history", None) else "unknown"
    print(
        f"ARC-AGI-2 HRPO complete. Final score: {result.score:.3f} | trials: {trials_used}"
    )
    print(f"Best prompt name: {result.prompt.name}")


if __name__ == "__main__":
    main()
