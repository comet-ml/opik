from __future__ import annotations

from benchmarks.core.planning import TaskPlan
from benchmarks.engines.base import BenchmarkEngine, EngineCapabilities, EngineRunResult
from benchmarks.local.runner import BenchmarkRunner


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
        runner = BenchmarkRunner(
            max_workers=plan.max_concurrent,
            seed=plan.seed,
            test_mode=plan.test_mode,
            checkpoint_dir=plan.checkpoint_dir,
        )
        runner.run_benchmarks(
            demo_datasets=plan.demo_datasets,
            optimizers=plan.optimizers,
            models=plan.models,
            retry_failed_run_id=plan.retry_failed_run_id,
            resume_run_id=plan.resume_run_id,
            task_specs=plan.tasks,
            preflight_info={
                "manifest_path": plan.manifest_path,
                "checkpoint_dir": plan.checkpoint_dir,
                "test_mode": plan.test_mode,
            },
        )
        return EngineRunResult(
            engine=self.name,
            run_id=runner.run_id,
            metadata={"checkpoint_dir": plan.checkpoint_dir},
        )

    def deploy(self) -> EngineRunResult:
        return EngineRunResult(
            engine=self.name,
            metadata={"message": "Local engine does not require deployment."},
        )
