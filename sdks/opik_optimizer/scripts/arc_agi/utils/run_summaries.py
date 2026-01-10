"""Helpers for persisting per-task ARC-AGI run summaries."""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any


def _extract_metrics_from_eval(
    evaluation_result: Any, composite_name: str
) -> tuple[dict[str, float], str | None, str | None]:
    """
    Pull composite/per-metric values and best_code from an evaluation result.

    Returns (metrics_map, composite_reason, best_code).
    """
    metrics: dict[str, float] = {}
    composite_reason: str | None = None
    best_code: str | None = None
    if not getattr(evaluation_result, "test_results", None):
        return metrics, composite_reason, best_code

    first_test = evaluation_result.test_results[0]
    for sr in getattr(first_test, "score_results", []) or []:
        metrics[getattr(sr, "name", "")] = getattr(sr, "value", 0.0)
        if sr.name == composite_name:
            composite_reason = getattr(sr, "reason", None)
            raw = (getattr(sr, "metadata", {}) or {}).get("raw_score_results") or []
            for raw_sr in raw:
                metrics[getattr(raw_sr, "name", "")] = getattr(raw_sr, "value", 0.0)
                if not best_code:
                    best_code = (
                        (getattr(raw_sr, "metadata", {}) or {}).get("best_code")
                    )
    return metrics, composite_reason, best_code


def persist_run_summary(
    *,
    task_id: str | None,
    composite_name: str,
    baseline_score: float,
    baseline_eval: Any,
    final_score: float | None,
    trials_used: int | str | None,
    llm_calls: int | None,
    final_cost: Any | None,
    output_dir: Path = Path("arc_agi/runs"),
    model: str,
    reasoning_model: str,
    pass_at_k: int,
    n_samples: int,
    include_images: bool,
    include_images_hrpo_eval: bool,
) -> None:
    """Write a per-task JSONL summary for later aggregation."""
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics, composite_reason, best_code = _extract_metrics_from_eval(
        baseline_eval, composite_name
    )
    now = datetime.utcnow().isoformat() + "Z"
    summary = {
        "timestamp": now,
        "task_id": task_id,
        "model": model,
        "reasoning_model": reasoning_model,
        "pass_at_k": pass_at_k,
        "n_samples": n_samples,
        "include_images": include_images,
        "include_images_hrpo_eval": include_images_hrpo_eval,
        "baseline": {
            "score": baseline_score,
            "metrics": metrics,
            "reason": composite_reason,
            "best_code": best_code,
            "cost": getattr(baseline_eval, "cost", None),
        },
        "final": {
            "score": final_score,
            "trials_used": trials_used,
            "llm_calls": llm_calls,
            "cost": final_cost,
        },
    }
    task_slug = task_id or "unknown_task"
    fname = output_dir / f"{task_slug}.jsonl"
    with fname.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(summary, ensure_ascii=True) + "\n")


__all__ = ["persist_run_summary"]
