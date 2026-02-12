from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


RunStatus = Literal["pending", "running", "succeeded", "failed"]


@dataclass
class TaskState:
    task_id: str
    status: RunStatus = "pending"


@dataclass
class RunState:
    run_id: str
    status: RunStatus = "pending"
    completed: int = 0
    failed: int = 0
    total: int = 0

    def mark_running(self) -> None:
        self.status = "running"

    def mark_task_success(self) -> None:
        self.completed += 1

    def mark_task_failure(self) -> None:
        self.failed += 1

    def finalize(self) -> None:
        self.status = "failed" if self.failed > 0 else "succeeded"
