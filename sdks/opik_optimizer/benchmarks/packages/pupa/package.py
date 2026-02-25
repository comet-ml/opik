from __future__ import annotations

from typing import Any

from opik_optimizer import ChatPrompt

from benchmarks.packages.package import BenchmarkPackage


class PupaPackage(BenchmarkPackage):
    key = "pupa"

    def matches(self, dataset_name: str) -> bool:
        return dataset_name.startswith("pupa")

    def build_initial_prompt(self) -> ChatPrompt | dict[str, ChatPrompt] | None:
        return None

    def build_agent(
        self,
        model_name: str,
        model_parameters: dict[str, Any] | None,
    ) -> Any | None:
        del model_name, model_parameters
        return None
