from __future__ import annotations

import subprocess

from benchmarks.core.planning import TaskPlan
from benchmarks.engines.base import BenchmarkEngine, EngineCapabilities, EngineRunResult


class ModalEngine(BenchmarkEngine):
    name = "modal"
    capabilities = EngineCapabilities(
        supports_deploy=True,
        supports_resume=True,
        supports_retry_failed=True,
        supports_live_logs=True,
        supports_remote_storage=True,
    )

    def run(self, plan: TaskPlan) -> EngineRunResult:
        from benchmarks.runners.run_benchmark_modal import app, submit_benchmark_tasks

        with app.run():
            submit_benchmark_tasks(
                demo_datasets=plan.demo_datasets,
                optimizers=plan.optimizers,
                models=plan.models,
                seed=plan.seed,
                test_mode=plan.test_mode,
                max_concurrent=plan.max_concurrent,
                retry_failed_run_id=plan.retry_failed_run_id,
                resume_run_id=plan.resume_run_id,
                task_specs=plan.tasks,
                manifest_path=plan.manifest_path,
            )

        return EngineRunResult(engine=self.name)

    def deploy(self) -> EngineRunResult:
        subprocess.run(
            ["modal", "deploy", "benchmarks/runners/benchmark_worker.py"],
            check=True,
        )
        subprocess.run(
            ["modal", "deploy", "benchmarks/runners/run_benchmark_modal.py"],
            check=True,
        )
        return EngineRunResult(
            engine=self.name,
            metadata={"deployed": ["benchmark_worker", "run_benchmark_modal"]},
        )
