from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from opik_optimizer import ChatPrompt

from benchmarks.packages.package import BenchmarkPackage


@dataclass(frozen=True)
class SimpleDatasetPackage(BenchmarkPackage):
    key: str
    prefixes: tuple[str, ...]

    def matches(self, dataset_name: str) -> bool:
        return any(dataset_name.startswith(prefix) for prefix in self.prefixes)

    def build_initial_prompt(self) -> ChatPrompt | dict[str, ChatPrompt] | None:
        return None

    def build_agent(
        self,
        model_name: str,
        model_parameters: dict[str, Any] | None,
    ) -> Any | None:
        del model_name, model_parameters
        return None
