from __future__ import annotations

from typing import Any

from opik_optimizer import ChatPrompt

from benchmarks.packages.package import BenchmarkPackage
from benchmarks.packages.hotpot.agent import build_hotpot_agent
from benchmarks.packages.hotpot.prompts import build_hotpot_prompts


class HotpotPackage(BenchmarkPackage):
    key = "hotpot"

    def matches(self, dataset_name: str) -> bool:
        return dataset_name.startswith("hotpot")

    def build_initial_prompt(self) -> dict[str, ChatPrompt]:
        return build_hotpot_prompts()

    def build_agent(
        self,
        model_name: str,
        model_parameters: dict[str, Any] | None,
    ) -> Any:
        return build_hotpot_agent(model_name, model_parameters)
