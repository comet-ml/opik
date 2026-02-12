from __future__ import annotations

from benchmarks.core.planning import TaskPlan
from benchmarks.engines.base import BenchmarkEngine, EngineCapabilities, EngineRunResult
from benchmarks.runners.run_benchmark_local import run_benchmark


class LocalEngine(BenchmarkEngine):
    name = "local"
    capabilities = EngineCapabilities(
        supports_deploy=False,
        supports_resume=True,
        supports_retry_failed=True,
        supports_live_logs=True,
        supports_remote_storage=False,
    )

    def run(self, plan: TaskPlan) -> EngineRunResult:
        run_benchmark(
            demo_datasets=plan.demo_datasets,
            optimizers=plan.optimizers,
            models=plan.models,
            max_workers=plan.max_concurrent,
            seed=plan.seed,
            test_mode=plan.test_mode,
            checkpoint_dir=plan.checkpoint_dir,
            retry_failed_run_id=plan.retry_failed_run_id,
            resume_run_id=plan.resume_run_id,
            task_specs=plan.tasks,
            skip_confirmation=True,
            manifest_path=plan.manifest_path,
        )
        return EngineRunResult(
            engine=self.name,
            run_id=plan.resume_run_id or plan.retry_failed_run_id,
            metadata={"checkpoint_dir": plan.checkpoint_dir},
        )

    def deploy(self) -> EngineRunResult:
        return EngineRunResult(
            engine=self.name,
            metadata={"message": "Local engine does not require deployment."},
        )
