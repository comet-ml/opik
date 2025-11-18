from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class BenchmarkTaskSpec:
    dataset_name: str
    optimizer_name: str
    model_name: str
    test_mode: bool
    optimizer_params: dict[str, Any] | None = field(default=None)
    optimize_params: dict[str, Any] | None = field(default=None)

    @property
    def task_id(self) -> str:
        return f"{self.dataset_name}_{self.optimizer_name}_{self.model_name}"

    def to_dict(self) -> dict[str, Any]:
        return {
            "dataset_name": self.dataset_name,
            "optimizer_name": self.optimizer_name,
            "model_name": self.model_name,
            "test_mode": self.test_mode,
            "optimizer_params": self.optimizer_params,
            "optimize_params": self.optimize_params,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> BenchmarkTaskSpec:
        return cls(
            dataset_name=str(data["dataset_name"]),
            optimizer_name=str(data["optimizer_name"]),
            model_name=str(data["model_name"]),
            test_mode=bool(data.get("test_mode", False)),
            optimizer_params=data.get("optimizer_params"),
            optimize_params=data.get("optimize_params"),
        )
