from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from opik_optimizer import ChatPrompt


class BenchmarkPackage(Protocol):
    key: str

    def matches(self, dataset_name: str) -> bool:
        """Whether the package handles the provided dataset key."""

    def build_initial_prompt(
        self,
    ) -> ChatPrompt | dict[str, ChatPrompt] | None:
        """Optional package-specific initial prompt."""

    def build_agent(
        self,
        model_name: str,
        model_parameters: dict[str, Any] | None,
    ) -> Any | None:
        """Optional package-specific agent instance."""


@dataclass(frozen=True)
class PackageResolution:
    key: str
    package: BenchmarkPackage
