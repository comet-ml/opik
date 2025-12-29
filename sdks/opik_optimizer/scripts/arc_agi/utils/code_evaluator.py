"""Code execution + metric aggregation for ARC-AGI HRPO runs."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import traceback
from collections import Counter
from dataclasses import dataclass
from typing import Any

import numpy as np

from .logging_utils import CONSOLE, debug_print
from .metrics import approx_match_score, label_iou, foreground_match_score
from .visualization import print_grid_triplet, render_grid


@dataclass
class EvaluationConfig:
    """Configuration describing pass@k sampling and reward weights."""

    pass_at_k: int = 2
    likeness_weight_train: float = 0.3
    likeness_weight_test: float = 0.3
    label_iou_weight: float = 0.3
    foreground_weight_test: float = 0.3
    sandbox_timeout_s: float = 5.0
    debug_log: bool = False
    show_execution_grids: bool = False  # print every grid execution (noisy)

    def log(self, message: str) -> None:
        """Print a debug message when ``debug_log`` is enabled."""
        debug_print(message, self.debug_log)


def extract_code_blocks(text: str) -> list[str]:
    """Return all fenced code blocks from an LLM response."""
    blocks = re.findall(
        r"```(?:python|py|python3)\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE
    )
    if not blocks:
        blocks = re.findall(r"```\s*(.*?)```", text, flags=re.DOTALL)
    return blocks


def validate_code_block(code: str) -> tuple[bool, str]:
    """Check basic invariants for a candidate `transform` definition."""
    if "def transform" not in code:
        return False, "Missing transform(grid) definition."
    return True, ""


def _build_sandbox_script(code: str) -> str:
    """Wrap the candidate code in a small runner script executed in a temp dir."""
    return f"""
# generated file
{code}
if __name__ == "__main__":
    import json
    import numpy as np
    import sys
    data = json.load(sys.stdin)
    grid = np.array(data["input"], dtype=int)
    result = transform(grid)
    if not isinstance(result, np.ndarray):
        result = np.asarray(result)
    print(json.dumps({{"ok": True, "result": result.tolist()}}))
"""


def _extract_json_payload(output: str) -> dict[str, Any] | None:
    """Best-effort attempt to parse JSON from sandbox stdout."""
    candidate = output.strip()
    if candidate:
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            pass
    for line in reversed(output.splitlines()):
        line = line.strip()
        if not (line.startswith("{") and line.endswith("}")):
            continue
        try:
            return json.loads(line)
        except json.JSONDecodeError:
            continue
    return None


def _run_transform(
    code: str, grid: list[list[int]], config: EvaluationConfig, label: str | None = None
) -> tuple[bool, Any, str]:
    """Execute ``transform`` and return (ok, np.ndarray | None, error_message)."""
    if config.show_execution_grids:
        prefix = f"{label}: " if label else ""
        CONSOLE.print(f"{prefix}Executing candidate transform on grid (colorized):")
        CONSOLE.print(render_grid(grid))

    if grid and any(len(row) != len(grid[0]) for row in grid):
        return False, None, "Input grid is ragged (rows have different lengths)."

    script = textwrap.dedent(_build_sandbox_script(code))
    payload = json.dumps({"input": grid})
    env = os.environ.copy()
    env["PYTHONHASHSEED"] = "0"
    try:
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "sandbox.py")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(script)
            proc = subprocess.run(
                [sys.executable, path],
                input=payload,
                capture_output=True,
                text=True,
                cwd=td,
                env=env,
                timeout=config.sandbox_timeout_s,
            )
    except subprocess.TimeoutExpired:
        return False, None, "Sandbox timeout."

    if proc.returncode != 0:
        stderr = (proc.stderr or proc.stdout).strip()
        return False, None, f"Sandbox error: {stderr}"

    payload_out = _extract_json_payload(proc.stdout)
    if not payload_out:
        return False, None, "Sandbox returned non-JSON output."
    if not payload_out.get("ok", False):
        return False, None, "Sandbox returned ok=False."
    try:
        arr_out = np.asarray(payload_out.get("result"))
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


def _infer_foreground_colors(train_examples: list[dict[str, Any]]) -> set[int]:
    """Infer which colors should be treated as foreground for this task."""
    colors: set[int] = set()
    background_votes: list[int] = []
    for example in train_examples:
        output = np.array(example.get("output") or [], dtype=int)
        if output.size == 0:
            continue
        vals, counts = np.unique(output, return_counts=True)
        background_color = int(vals[np.argmax(counts)])
        background_votes.append(background_color)
        colors.update(int(v) for v in vals if v != background_color)

        input_grid = example.get("input")
        if input_grid is None:
            continue
        inp = np.array(input_grid, dtype=int)
        if inp.shape != output.shape:
            continue
        diff_mask = inp != output
        if diff_mask.any():
            colors.update(int(v) for v in output[diff_mask])
            colors.update(int(v) for v in inp[diff_mask])

    if not colors and background_votes:
        background_mode = Counter(background_votes).most_common(1)[0][0]
        all_colors: set[int] = set()
        for example in train_examples:
            output = np.array(example.get("output") or [], dtype=int)
            all_colors.update(int(v) for v in np.unique(output))
        colors = {val for val in all_colors if val != background_mode}
    return colors


def _evaluate_code_candidate(
    code: str,
    train_in: list[list[list[int]]],
    train_out: list[list[list[int]]],
    test_in: list[list[list[int]]],
    config: EvaluationConfig,
    cand_idx: int,
    total_cands: int,
) -> dict[str, Any]:
    """Run a candidate transform across train/test grids and collect stats."""
    snippet = code.splitlines()
    preview = "\n".join(snippet[: min(8, len(snippet))])
    debug_print(
        f"Candidate preview (lines={len(snippet)}):\n{preview}", config.debug_log
    )

    train_feedback: list[str] = []
    exact_scores: list[float] = []
    soft_scores: list[float] = []

    for idx, (iin, oout) in enumerate(zip(train_in, train_out, strict=True)):
        ok, pred, err = _run_transform(
            code, iin, config, label=f"cand {cand_idx}/{total_cands} train {idx}"
        )
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
        preview_mismatches = mismatches[:10]
        train_feedback.append(
            f"Train {idx}: exact={exact:.1f} likeness={soft:.2f} "
            f"mismatch_count={len(mismatches)} preview={preview_mismatches}"
        )
        exact_scores.append(exact)
        soft_scores.append(soft)

    test_outputs: list[list[list[int]]] = []
    test_errors: list[str] = []
    for idx, iin in enumerate(test_in):
        ok, pred, err = _run_transform(
            code, iin, config, label=f"cand {cand_idx}/{total_cands} test {idx}"
        )
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
        "code": code,
    }


def _select_attempts_by_test(
    candidates: list[dict[str, Any]], num_tests: int, max_attempts: int
) -> list[list[list[list[int]]]]:
    """Select up to ``max_attempts`` predictions per test case."""
    attempts: list[list[list[list[int]]]] = [[] for _ in range(num_tests)]
    if num_tests <= 0:
        return attempts

    for cand in candidates:
        outputs = cand.get("test_outputs") or []
        if len(outputs) != num_tests:
            continue
        for idx, pred in enumerate(outputs):
            if len(attempts[idx]) >= max_attempts:
                continue
            if pred:
                attempts[idx].append(pred)
        if all(len(attempt) >= max_attempts for attempt in attempts):
            break

    for idx in range(num_tests):
        while len(attempts[idx]) < max_attempts:
            attempts[idx].append([])
    return attempts


def evaluate_arc_response(
    dataset_item: dict[str, Any],
    llm_output: str,
    config: EvaluationConfig,
) -> dict[str, Any]:
    """Compute and cache exact / approx / IOU + composite score for a response."""
    cache = dataset_item.setdefault("_arc_metric_cache", {})
    if llm_output in cache:
        return cache[llm_output]

    gold_outputs = dataset_item.get("test_outputs") or []
    train_examples = dataset_item.get("training_examples") or []
    test_inputs = dataset_item.get("test_inputs") or []

    code_blocks = extract_code_blocks(llm_output)
    if not code_blocks:
        result = {
            "composite_value": 0.0,
            "metrics": {
                "arc_agi2_exact": 0.0,
                "arc_agi2_approx_match": 0.0,
                "arc_agi2_label_iou": 0.0,
                "arc_agi2_foreground_match": 0.0,
            },
            "reason": "No python code block found in response.",
            "metadata": {},
        }
        cache[llm_output] = result
        return result

    train_in = [ex.get("input") for ex in train_examples]
    train_out = [ex.get("output") for ex in train_examples]
    foreground_colors = _infer_foreground_colors(train_examples)

    valid_blocks: list[str] = []
    rejected: list[str] = []
    for code in code_blocks:
        ok, reason = validate_code_block(code)
        if ok:
            valid_blocks.append(code)
        else:
            rejected.append(reason)

    if not valid_blocks:
        result = {
            "composite_value": 0.0,
            "metrics": {
                "arc_agi2_exact": 0.0,
                "arc_agi2_approx_match": 0.0,
                "arc_agi2_label_iou": 0.0,
                "arc_agi2_foreground_match": 0.0,
            },
            "reason": f"All code blocks rejected: {' | '.join(rejected[:3])}",
            "metadata": {},
        }
        cache[llm_output] = result
        return result

    candidates = [
        _evaluate_code_candidate(
            code,
            train_in,
            train_out,
            test_inputs,
            config,
            cand_idx=idx + 1,
            total_cands=len(valid_blocks),
        )
        for idx, code in enumerate(valid_blocks)
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
        soft_reward = best["train_soft"] * config.likeness_weight_train
        value = max(best["train_exact"], soft_reward)
        reason_parts.append(f"train_approx_match_reward={soft_reward:.2f}")
        result = {
            "composite_value": value,
            "metrics": {
                "arc_agi2_exact": best["train_exact"],
                "arc_agi2_approx_match": best["train_soft"],
                "arc_agi2_label_iou": 0.0,
                "arc_agi2_foreground_match": 0.0,
            },
            "reason": " | ".join(reason_parts),
            "metadata": {},
        }
        cache[llm_output] = result
        return result

    evaluated_candidates = candidates_sorted[: config.pass_at_k]
    attempts_by_test = _select_attempts_by_test(
        evaluated_candidates, len(gold_outputs), config.pass_at_k
    )
    exact_scores: list[float] = []
    likeness_scores: list[float] = []
    iou_scores: list[float] = []
    foreground_scores: list[float] = []
    mismatch_counts: list[int] = []
    mismatch_coords: list[str] = []
    swap_counts: dict[tuple[int, int], int] = {}

    for test_idx, gold in enumerate(gold_outputs):
        gold_arr = np.array(gold, dtype=int)
        best_exact = 0.0
        best_likeness = 0.0
        best_iou = 0.0
        best_foreground = 0.0
        best_pred_arr: np.ndarray | None = None

        for pred in attempts_by_test[test_idx]:
            if not pred:
                continue
            pred_arr = np.array(pred, dtype=int)
            candidate_foreground = 0.0
            if pred_arr.shape != gold_arr.shape:
                candidate_exact = 0.0
                candidate_likeness = 0.0
                candidate_iou = 0.0
            else:
                candidate_exact = 1.0 if np.array_equal(pred_arr, gold_arr) else 0.0
                candidate_likeness = approx_match_score(pred_arr, gold_arr)
                candidate_iou = label_iou(pred_arr, gold_arr)
                candidate_foreground = foreground_match_score(
                    pred_arr, gold_arr, foreground_colors
                )

            if candidate_exact > best_exact or (
                candidate_exact == best_exact and candidate_likeness > best_likeness
            ):
                best_exact = candidate_exact
                best_likeness = candidate_likeness
                best_iou = candidate_iou
                best_foreground = candidate_foreground
                best_pred_arr = pred_arr

        exact_scores.append(best_exact)
        likeness_scores.append(best_likeness)
        iou_scores.append(best_iou)
        foreground_scores.append(best_foreground)

        if best_pred_arr is not None and best_pred_arr.shape == gold_arr.shape:
            mism_idx = np.argwhere(best_pred_arr != gold_arr)
            mismatch_counts.append(int(mism_idx.shape[0]))
            for coord in mism_idx[:3]:
                mismatch_coords.append(
                    f"t{test_idx}:{tuple(int(x) for x in coord)}:"
                    f"{int(best_pred_arr[tuple(coord)])}|{int(gold_arr[tuple(coord)])}"
                )
            for coord in mism_idx:
                key = (int(best_pred_arr[tuple(coord)]), int(gold_arr[tuple(coord)]))
                swap_counts[key] = swap_counts.get(key, 0) + 1
        else:
            mismatch_counts.append(-1)

    best_score = sum(exact_scores) / len(exact_scores) if exact_scores else 0.0
    best_likeness = (
        sum(likeness_scores) / len(likeness_scores) if likeness_scores else 0.0
    )
    best_candidate_iou = sum(iou_scores) / len(iou_scores) if iou_scores else 0.0
    best_foreground_match = (
        sum(foreground_scores) / len(foreground_scores) if foreground_scores else 0.0
    )
    best_mismatch_summary = (
        f"test_mismatches={mismatch_counts} sample_coords={mismatch_coords[:5]}"
    )
    if swap_counts:
        swap_parts = [f"{k[0]}→{k[1]}:{v}" for k, v in sorted(swap_counts.items())]
        best_swap_summary = "swaps={" + ", ".join(swap_parts) + "}"
    else:
        best_swap_summary = ""

    best_reason = (
        f"pass@{config.pass_at_k} exact={best_score:.2f} approx_match={best_likeness:.2f} "
        f"label_iou={best_candidate_iou:.2f} foreground={best_foreground_match:.2f} | {' | '.join(reason_parts)}"
    )

    likeness_reward = best_likeness * config.likeness_weight_test
    iou_reward = best_candidate_iou * config.label_iou_weight
    foreground_reward = best_foreground_match * config.foreground_weight_test
    value = max(
        best_score,
        likeness_reward,
        iou_reward,
        foreground_reward,
        best["train_soft"] * config.likeness_weight_train,
    )
    best_reason += (
        f" | {best_mismatch_summary} | {best_swap_summary} | "
        f"approx_match_reward={likeness_reward:.2f} "
        f"label_iou_reward={iou_reward:.2f} "
        f"foreground_reward={foreground_reward:.2f}"
    )

    test_inputs = dataset_item.get("test_inputs")
    if config.debug_log and gold_outputs and test_inputs:
        try:
            for cand_idx, cand in enumerate(
                candidates_sorted[: min(config.pass_at_k, len(candidates_sorted))]
            ):
                preds = cand.get("test_outputs") or []
                if preds and preds[0]:
                    print_grid_triplet(
                        test_inputs[0],
                        gold_outputs[0],
                        preds[0],
                        label=f"Candidate {cand_idx + 1} preview on test[0] (input | expected | predicted):",
                    )
        except Exception:
            pass

    config.log(
        f"Test metrics: exact={best_score:.2f} approx_match={best_likeness:.2f} "
        f"label_iou={best_candidate_iou:.2f} foreground={best_foreground_match:.2f} | "
        f"{best_mismatch_summary} {best_swap_summary}"
    )

    result = {
        "composite_value": value,
        "metrics": {
            "arc_agi2_exact": best_score,
            "arc_agi2_approx_match": best_likeness,
            "arc_agi2_label_iou": best_candidate_iou,
            "arc_agi2_foreground_match": best_foreground_match,
        },
        "reason": best_reason,
        "metadata": {
            "approx_match_reward": likeness_reward,
            "label_iou_reward": iou_reward,
            "foreground_reward": foreground_reward,
            "pass_at_k": config.pass_at_k,
            "test_mismatches": best_mismatch_summary,
            "swaps": best_swap_summary,
            "train_exact": best.get("train_exact", 0.0),
            "train_soft": best.get("train_soft", 0.0),
            "best_code": best.get("code"),
        },
    }
    cache[llm_output] = result
    return result


__all__ = ["EvaluationConfig", "evaluate_arc_response"]
