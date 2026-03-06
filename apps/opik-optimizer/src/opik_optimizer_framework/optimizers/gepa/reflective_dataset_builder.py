from __future__ import annotations

from typing import Any, TYPE_CHECKING

if TYPE_CHECKING:
    from gepa.core.adapter import EvaluationBatch
    from .failure_aware_sampler import FailureAwareBatchSampler


class ReflectiveDatasetBuilder:
    """Builds structured feedback datasets for the reflection LLM.

    Transforms raw evaluation results (trajectories with per-item assertions)
    into records the reflection LLM can reason about. Handles single-run and
    multi-run consolidation, failure history annotation, and difficulty sorting.
    """

    def __init__(
        self, batch_sampler: FailureAwareBatchSampler | None = None,
    ) -> None:
        self._batch_sampler = batch_sampler

    @staticmethod
    def _build_inputs(dataset_item: dict[str, Any]) -> dict[str, str]:
        return {
            k: str(v) for k, v in dataset_item.items()
            if k != "id"
        }

    @staticmethod
    def _build_run_feedback(assertions: list[dict[str, Any]]) -> str:
        failed = [a for a in assertions if a["value"] < 1.0]
        passed = [a for a in assertions if a["value"] >= 1.0]
        lines: list[str] = []
        if failed:
            lines.append("FAILED assertions (fix these):")
            for a in failed:
                reason = a.get("reason", "")
                lines.append(f"- Assertion: {a['name']}")
                if reason:
                    lines.append(f"  Reason: {reason}")
        if passed:
            lines.append("PASSED assertions (preserve these):")
            for a in passed:
                lines.append(f"- {a['name']}")
        return "\n".join(lines)

    def build(
        self,
        candidate: dict[str, str],
        eval_batch: EvaluationBatch,
        components_to_update: list[str],
    ) -> dict[str, list[dict[str, Any]]]:
        """Build the feedback dataset for GEPA's reflection LLM.

        When runs_per_item > 1, all runs are consolidated into a single
        record per input. Records are sorted by difficulty (most failures first).
        """
        if not components_to_update:
            components_to_update = [
                key
                for key in candidate.keys()
                if not key.startswith("_") and key not in ("source", "id")
            ]

        trajectories = eval_batch.trajectories or []

        records: list[dict[str, Any]] = []
        for traj in trajectories:
            dataset_item = traj.get("input", {})
            runs = traj.get("runs", [])
            total_runs = len(runs)
            inputs = self._build_inputs(dataset_item)

            if total_runs <= 1:
                run = runs[0] if runs else {}
                assertions = run.get("assertions", [])
                max_failed = sum(1 for a in assertions if a["value"] < 1.0)
                records.append({
                    "Inputs": inputs,
                    "Generated Outputs": run.get("output", ""),
                    "Feedback": self._build_run_feedback(assertions),
                    "_max_failed": max_failed,
                })
            else:
                run_sections = []
                max_failed = 0
                per_run_failed_names: list[set[str]] = []
                num_passed_runs = 0

                for run_idx, run in enumerate(runs):
                    assertions = run.get("assertions", [])
                    num_failed = sum(1 for a in assertions if a["value"] < 1.0)
                    max_failed = max(max_failed, num_failed)
                    failed_names = {a["name"] for a in assertions if a["value"] < 1.0}
                    per_run_failed_names.append(failed_names)
                    if num_failed == 0:
                        num_passed_runs += 1

                    section = f"[Run {run_idx + 1}/{total_runs}]\n"
                    section += f"Output: {run.get('output', '')}\n"
                    section += self._build_run_feedback(assertions)
                    run_sections.append(section)

                consistent = set.intersection(*per_run_failed_names) if per_run_failed_names else set()
                summary_parts = [f"{num_passed_runs}/{total_runs} runs passed."]
                if consistent:
                    summary_parts.append(
                        f"Consistent failures: {', '.join(sorted(consistent))}"
                    )

                records.append({
                    "Inputs": inputs,
                    "Runs": "\n\n".join(run_sections),
                    "Summary": " ".join(summary_parts),
                    "_max_failed": max_failed,
                })

        if self._batch_sampler is not None:
            for record, traj in zip(records, trajectories):
                item_id = str(traj.get("input", {}).get("id", ""))
                streak = self._batch_sampler.get_failure_streak(item_id)
                if streak >= 1:
                    stuck = self._batch_sampler.get_failed_assertions(item_id)
                    if stuck:
                        record["Failure History"] = (
                            f"This item has failed {streak} consecutive iteration(s). "
                            f"Still-failing assertions: {', '.join(stuck)}. "
                            f"The current rules for these assertions are not working."
                        )

        records.sort(key=lambda r: r["_max_failed"], reverse=True)
        for r in records:
            del r["_max_failed"]

        return {component: list(records) for component in components_to_update}
