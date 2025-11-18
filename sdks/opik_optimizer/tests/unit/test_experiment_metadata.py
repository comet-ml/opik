from __future__ import annotations

from typing import Any
from collections.abc import Callable

import pytest

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.optimizable_agent import OptimizableAgent
from opik_optimizer import FewShotBayesianOptimizer, EvolutionaryOptimizer, ChatPrompt


class _DummyDataset:
    name = "dummy-dataset"
    id = "dataset-id"

    def get_items(self, nb_samples: int | None = None) -> list[dict[str, Any]]:
        return []


class _DummyAgent(OptimizableAgent):
    project_name = "agent-project"


class _StubOptimizer(BaseOptimizer):
    def optimize_prompt(  # type: ignore[override]
        self,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Callable,
        experiment_config: dict[str, Any] | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        **kwargs: Any,
    ) -> Any:
        raise NotImplementedError

    def optimize_mcp(  # type: ignore[override]
        self,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Callable,
        *,
        tool_name: str,
        second_pass: Any,
        experiment_config: dict[str, Any] | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        fallback_invoker: Any | None = None,
        fallback_arguments: Any | None = None,
        allow_tool_use_on_second_pass: bool = False,
        **kwargs: Any,
    ) -> Any:
        raise NotImplementedError


@pytest.fixture()
def dummy_prompt() -> ChatPrompt:
    def sample_tool(query: str, limit: int = 3) -> str:
        """Sample tool."""
        return f"{query}:{limit}"

    tool_schema = {
        "type": "function",
        "function": {
            "name": "sample_tool",
            "description": "Lookup data",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Search query"},
                    "limit": {"type": "integer", "description": "Max results"},
                },
                "required": ["query"],
            },
        },
    }

    return ChatPrompt(
        system="You are helpful.",
        user="{question}",
        tools=[tool_schema],
        function_map={"sample_tool": sample_tool},
        model="test-model",
    )


def _dummy_metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 0.5


def test_prepare_experiment_config_merges_metadata(dummy_prompt: ChatPrompt) -> None:
    optimizer = _StubOptimizer(model="test-model")
    optimizer.agent_class = _DummyAgent

    dataset = _DummyDataset()
    metric = _dummy_metric
    metric.__name__ = "dummy_metric"  # help with metadata

    configuration_updates = {"custom": "value"}
    additional_metadata = {"extra": {"flag": True}}
    user_config = {
        "tracking": {"run_id": "123"},
        "optimizer_metadata": {"custom": "override"},
    }

    config = optimizer._prepare_experiment_config(
        prompt=dummy_prompt,
        dataset=dataset,
        metric=metric,
        experiment_config=user_config,
        configuration_updates=configuration_updates,
        additional_metadata=additional_metadata,
    )

    assert config["project_name"] == _DummyAgent.project_name
    assert (
        "project_name" not in config["agent_config"]
    )  # ChatPrompt doesn't have project_name, gets dropped
    assert config["agent_config"]["model"] == dummy_prompt.model
    assert config["configuration"]["custom"] == "value"
    assert config["extra"] == {"flag": True}
    assert config["tracking"]["run_id"] == "123"
    assert config["optimizer_metadata"]["custom"] == "override"
    assert config["optimizer_metadata"]["name"] == "_StubOptimizer"
    assert "version" in config["optimizer_metadata"]

    # verify tool metadata captured
    tool_signatures = config["tool_signatures"]
    assert len(tool_signatures) == 1
    signature = tool_signatures[0]
    assert signature["name"] == "sample_tool"
    parameter_names = {param["name"] for param in signature.get("parameters", [])}
    assert {"query", "limit"}.issubset(parameter_names)


def test_prepare_experiment_config_user_overrides_prompt_name(
    dummy_prompt: ChatPrompt,
) -> None:
    optimizer = _StubOptimizer(model="test-model")
    optimizer.agent_class = _DummyAgent

    dataset = _DummyDataset()
    metric = _dummy_metric
    metric.__name__ = "dummy_metric"

    user_config = {
        "configuration": {
            "prompt_name": "custom-name",
        }
    }

    config = optimizer._prepare_experiment_config(
        prompt=dummy_prompt,
        dataset=dataset,
        metric=metric,
        experiment_config=user_config,
    )

    assert config["configuration"]["prompt_name"] == "custom-name"
    # prompt contents are still present after merge
    assert isinstance(config["configuration"]["prompt"], list)


def test_fewshot_optimizer_metadata_exposes_bounds() -> None:
    optimizer = FewShotBayesianOptimizer(
        model="gpt-test",
        min_examples=2,
        max_examples=7,
    )
    metadata = optimizer.get_optimizer_metadata()
    assert metadata == {"min_examples": 2, "max_examples": 7}


def test_evolutionary_optimizer_metadata_includes_core_params() -> None:
    optimizer = EvolutionaryOptimizer(model="gpt-test")
    metadata = optimizer.get_optimizer_metadata()
    expected_keys = {
        "population_size",
        "num_generations",
        "mutation_rate",
        "crossover_rate",
        "tournament_size",
        "elitism_size",
        "adaptive_mutation",
        "enable_moo",
        "enable_llm_crossover",
        "infer_output_style",
        "output_style_guidance",
    }
    assert expected_keys.issubset(metadata.keys())
