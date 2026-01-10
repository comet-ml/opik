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
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import traceback
from collections.abc import Sequence
from typing import Any

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
SANDBOX_TIMEOUT_S = float(os.getenv("ARC_AGI2_SANDBOX_TIMEOUT", "5"))
RAISE_SCORING_ERRORS = os.getenv("ARC_AGI2_RAISE_SCORING_ERRORS", "0") not in {
    "",
    "0",
    "false",
    "False",
}

EVAL_MODEL = "gemini/gemini-1.5-flash-002"  # model used for candidate eval calls
REASONING_MODEL = "gemini/gemini-3-pro-preview"  # model used inside HRPO
EVAL_TEMPERATURE = 0.0
REASONING_TEMPERATURE = 0.7
HRPO_MAX_TRIALS = 15
HRPO_THREADS = 8

# Starting system prompt
SYSTEM_PROMPT = """You are an expert ARC-AGI solver. Infer the transformation from training pairs and write Python code:
def transform(grid: np.ndarray) -> np.ndarray
- Analyze objects, colors, symmetries. Keep rules concise.
- You may use numpy, scipy, cv2, and the Python standard library. Return a numpy array.
- Provide one or two code blocks (primary + optional fallback)."""

USER_PROMPT = """Training examples (input -> output):
{training_examples_text}

Test inputs:
{test_inputs_text}

Respond with ONE or TWO python code blocks (```python ...```), each defining transform(grid: np.ndarray) -> np.ndarray."""


def _format_grid(grid: Sequence[Sequence[int]]) -> str:
    return "\n".join(" ".join(str(c) for c in row) for row in grid)


def _render_examples(examples: list[dict[str, Any]]) -> str:
    sections = []
    for idx, ex in enumerate(examples, start=1):
        inp = _format_grid(ex.get("input", []))
        out = _format_grid(ex.get("output", []))
        sections.append(
            f"Example #{idx}\n"
            f"Input:\n<ArcGrid>\n{inp}\n</ArcGrid>\n\n"
            f"Output:\n<ArcGrid>\n{out}\n</ArcGrid>"
        )
    return "\n\n".join(sections)


def _render_test_inputs(test_inputs: list[list[list[int]]]) -> str:
    sections = []
    for idx, grid in enumerate(test_inputs, start=1):
        sections.append(
            f"Challenge #{idx}\nInput:\n<ArcGrid>\n{_format_grid(grid)}\n</ArcGrid>"
        )
    return "\n\n".join(sections)


def _prepare_dataset_item(item: dict[str, Any]) -> dict[str, Any]:
    training_examples = item.get("training_examples") or []
    test_inputs = item.get("test_inputs") or []
    prepared = dict(item)
    prepared.setdefault("training_examples_text", _render_examples(training_examples))
    prepared.setdefault("test_inputs_text", _render_test_inputs(test_inputs))
    return prepared


class _PreparedDataset:
    def __init__(self, dataset: Any) -> None:
        self._dataset = dataset
        self.name = dataset.name
        self.id = dataset.id

    def get_items(self, nb_samples: int | None = None) -> list[dict[str, Any]]:
        items = self._dataset.get_items(nb_samples)
        return [_prepare_dataset_item(item) for item in items]

    def __getattr__(self, name: str) -> Any:
        return getattr(self._dataset, name)


def _extract_code_blocks(text: str) -> list[str]:
    blocks = re.findall(
        r"```(?:python|py|python3)\s*(.*?)```", text, flags=re.DOTALL | re.IGNORECASE
    )
    if not blocks:
        blocks = re.findall(r"```\s*(.*?)```", text, flags=re.DOTALL)
    return blocks


_DANGEROUS_CODE_PATTERNS: list[tuple[str, str]] = [
    (r"\bimport\s+os\b", "os module is not allowed"),
    (r"\bfrom\s+os\b", "os module is not allowed"),
    (r"\bimport\s+sys\b", "sys module is not allowed"),
    (r"\bfrom\s+sys\b", "sys module is not allowed"),
    (r"\bimport\s+subprocess\b", "subprocess module is not allowed"),
    (r"\bfrom\s+subprocess\b", "subprocess module is not allowed"),
    (r"\bimport\s+socket\b", "socket module is not allowed"),
    (r"\bfrom\s+socket\b", "socket module is not allowed"),
    (r"\bimport\s+shutil\b", "shutil module is not allowed"),
    (r"\bfrom\s+shutil\b", "shutil module is not allowed"),
    (r"\bimport\s+pathlib\b", "pathlib module is not allowed"),
    (r"\bfrom\s+pathlib\b", "pathlib module is not allowed"),
    (r"\bimport\s+importlib\b", "importlib module is not allowed"),
    (r"\bfrom\s+importlib\b", "importlib module is not allowed"),
    (r"\bimport\s+pickle\b", "pickle module is not allowed"),
    (r"\bfrom\s+pickle\b", "pickle module is not allowed"),
    (r"\bopen\s*\(", "file I/O is not allowed"),
    (r"\beval\s*\(", "eval is not allowed"),
    (r"\bexec\s*\(", "exec is not allowed"),
    (r"__import__\s*\(", "__import__ is not allowed"),
    (r"\bcompile\s*\(", "compile is not allowed"),
]


def _validate_code_block(code: str) -> tuple[bool, str]:
    if "def transform" not in code:
        return False, "Missing transform(grid) definition."
    for pattern, reason in _DANGEROUS_CODE_PATTERNS:
        if re.search(pattern, code, flags=re.IGNORECASE):
            return False, reason
    return True, ""


def _handle_scoring_exception(
    metric_name: str, exc: Exception
) -> score_result.ScoreResult:
    tb = traceback.format_exc()
    message = f"Scoring error: {type(exc).__name__}: {exc}"
    if RAISE_SCORING_ERRORS:
        raise exc
    return score_result.ScoreResult(
        name=metric_name,
        value=0.0,
        scoring_failed=True,
        reason=message,
        metadata={"exception": str(exc), "traceback": tb},
    )


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


def _build_sandbox_script(code: str) -> str:
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


def _run_transform(code: str, grid: list[list[int]]) -> tuple[bool, Any, str]:
    if grid and any(len(row) != len(grid[0]) for row in grid):
        return False, None, "Input grid is ragged (rows have different lengths)."

    script = textwrap.dedent(_build_sandbox_script(code))
    payload = json.dumps({"input": grid})
    env_allowlist = {
        "PATH",
        "LANG",
        "LC_ALL",
        "LC_CTYPE",
        "SYSTEMROOT",
        "TMPDIR",
        "TEMP",
        "TMP",
    }
    env = {key: value for key, value in os.environ.items() if key in env_allowlist}
    env["PYTHONHASHSEED"] = "0"
    env["PYTHONIOENCODING"] = "utf-8"
    try:
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "sandbox.py")
            with open(path, "w", encoding="utf-8") as f:
                f.write(script)
            proc = subprocess.run(
                [sys.executable, path],
                input=payload,
                capture_output=True,
                text=True,
                cwd=td,
                env=env,
                timeout=SANDBOX_TIMEOUT_S,
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
            arr_out = np.expand_dims(arr_out, axis=tuple(range(2 - arr_out.ndim)))
        if np.any(arr_out < 0) or np.any(arr_out > 9):
            return False, None, "transform returned values outside expected 0–9 range."
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


def _select_attempts_by_test(
    candidates: list[dict[str, Any]], num_tests: int, max_attempts: int
) -> list[list[list[list[int]]]]:
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


def arc_agi2_metric(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    try:
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

        valid_code_blocks: list[str] = []
        invalid_reasons: list[str] = []
        for code in code_blocks:
            ok, reason = _validate_code_block(code)
            if ok:
                valid_code_blocks.append(code)
            else:
                invalid_reasons.append(reason)

        if not valid_code_blocks:
            reason = invalid_reasons[0] if invalid_reasons else "No valid code blocks."
            return score_result.ScoreResult(
                name="arc_agi2_accuracy",
                value=0.0,
                scoring_failed=False,
                reason=f"No safe code blocks found: {reason}",
            )

        train_in = [ex.get("input") for ex in train_examples]
        train_out = [ex.get("output") for ex in train_examples]

        candidates = [
            _evaluate_code_candidate(code, train_in, train_out, test_inputs)
            for code in valid_code_blocks
        ]
        candidates_sorted = sorted(
            candidates, key=lambda c: (c["train_exact"], c["train_soft"]), reverse=True
        )
        best = candidates_sorted[0]

        # Pass@K per test input if outputs are available.
        best_score = 0.0
        if gold_outputs:
            attempts_by_test = _select_attempts_by_test(
                candidates_sorted[:PASS_AT_K], len(gold_outputs), PASS_AT_K
            )
            exact_scores: list[float] = []
            for test_idx, gold in enumerate(gold_outputs):
                attempts = attempts_by_test[test_idx]
                exact = 0.0
                for pred in attempts:
                    if _is_valid_matrix(pred, gold):
                        exact = 1.0
                        break
                exact_scores.append(exact)
            best_score = sum(exact_scores) / len(exact_scores) if exact_scores else 0.0
        else:
            best_score = best["train_exact"]

        reason = (
            f"code_blocks={len(code_blocks)} pass@{PASS_AT_K} "
            f"train_exact={best['train_exact']:.2f} train_soft={best['train_soft']:.2f} "
            f"{best['train_feedback']}"
        )
        return score_result.ScoreResult(
            name="arc_agi2_accuracy",
            value=best_score,
            scoring_failed=False,
            reason=reason,
        )
    except Exception as exc:
        return _handle_scoring_exception("arc_agi2_accuracy", exc)


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
    dataset = _PreparedDataset(dataset)

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
    prompt_result = result.prompt
    if isinstance(prompt_result, dict):
        prompt_messages = next(iter(prompt_result.values())).messages
    else:
        prompt_messages = prompt_result.messages
    print(json.dumps(prompt_messages, indent=2))
    print(f"Final score: {result.score:.3f}")


if __name__ == "__main__":
    main()
