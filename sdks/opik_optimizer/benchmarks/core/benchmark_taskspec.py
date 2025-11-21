from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class BenchmarkTaskSpec:
    dataset_name: str
    optimizer_name: str
    model_name: str
    test_mode: bool
    model_parameters: dict[str, Any] | None = field(default=None)
    optimizer_params: dict[str, Any] | None = field(default=None)
    optimizer_prompt_params: dict[str, Any] | None = field(default=None)
    datasets: dict[str, Any] | None = field(default=None)
    metrics: list[str | dict[str, Any]] | None = field(default=None)
    prompt_messages: list[dict[str, Any]] | None = field(default=None)

    @property
    def task_id(self) -> str:
        return f"{self.dataset_name}_{self.optimizer_name}_{self.model_name}"

    def to_dict(self) -> dict[str, Any]:
        return {
            "dataset_name": self.dataset_name,
            "optimizer_name": self.optimizer_name,
            "model_name": self.model_name,
            "test_mode": self.test_mode,
            "model_parameters": self.model_parameters,
            "optimizer_params": self.optimizer_params,
            "optimizer_prompt_params": self.optimizer_prompt_params,
            "datasets": self.datasets,
            "metrics": self.metrics,
            "prompt_messages": self.prompt_messages,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> BenchmarkTaskSpec:
        return cls(
            dataset_name=str(data["dataset_name"]),
            optimizer_name=str(data["optimizer_name"]),
            model_name=str(data["model_name"]),
            test_mode=bool(data.get("test_mode", False)),
            model_parameters=data.get("model_parameters"),
            optimizer_params=data.get("optimizer_params"),
            optimizer_prompt_params=data.get("optimizer_prompt_params"),
            datasets=data.get("datasets"),
            metrics=data.get("metrics"),
            prompt_messages=data.get("prompt_messages"),
        )
