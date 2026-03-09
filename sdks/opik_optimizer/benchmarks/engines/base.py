from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from benchmarks.core.planning import TaskPlan


@dataclass(frozen=True)
class EngineCapabilities:
    supports_deploy: bool = False
    supports_resume: bool = False
    supports_retry_failed: bool = False
    supports_live_logs: bool = False
    supports_remote_storage: bool = False


@dataclass(frozen=True)
class EngineRunResult:
    engine: str
    run_id: str | None = None
    status: str = "succeeded"
    metadata: dict[str, Any] | None = None


class BenchmarkEngine(Protocol):
    name: str
    capabilities: EngineCapabilities

    def run(self, plan: TaskPlan) -> EngineRunResult:
        """Run a compiled benchmark plan."""

    def deploy(self) -> EngineRunResult:
        """Deploy engine infrastructure if supported."""


class DeployNotSupportedError(RuntimeError):
    pass
