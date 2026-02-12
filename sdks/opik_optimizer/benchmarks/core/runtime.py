from __future__ import annotations

from benchmarks.core.planning import TaskPlan
from benchmarks.core.results import RunSummary
from benchmarks.engines.registry import get_engine


def run_plan(engine_name: str, plan: TaskPlan) -> RunSummary:
    engine = get_engine(engine_name)
    result = engine.run(plan)
    return RunSummary(
        engine=result.engine,
        run_id=result.run_id,
        status="succeeded",
        metadata=result.metadata or {},
    )
