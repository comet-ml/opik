from __future__ import annotations

from benchmarks.core.planning import TaskPlan
from benchmarks.core.types import RunSummary
from benchmarks.engines.base import DeployNotSupportedError
from benchmarks.engines.registry import get_engine


def run_plan(engine_name: str, plan: TaskPlan) -> RunSummary:
    """Execute a compiled task plan with the selected engine."""
    engine = get_engine(engine_name)
    result = engine.run(plan)
    return RunSummary(
        engine=result.engine,
        run_id=result.run_id,
        status=result.status,
        metadata=result.metadata or {},
    )


def deploy_engine(engine_name: str) -> RunSummary:
    """Deploy engine infrastructure when supported by the engine backend."""
    engine = get_engine(engine_name)
    if not engine.capabilities.supports_deploy:
        raise DeployNotSupportedError(f"Engine '{engine_name}' does not support deploy")
    result = engine.deploy()
    return RunSummary(
        engine=result.engine,
        run_id=result.run_id,
        status="succeeded",
        metadata=result.metadata or {},
    )
