"""Tool-description optimization for agent bundles is keyed by the bundle key."""

from __future__ import annotations

import copy
from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from tests.unit.characterization import harness

TOOL_OPTIMIZERS = [
    "MetaPromptOptimizer",
    "EvolutionaryOptimizer",
    "HierarchicalReflectiveOptimizer",
    "GepaOptimizer",
]


def _default_named_tool_bundle() -> harness.Scenario:
    """S2 with a tool on both prompts, which keep ChatPrompt's default name."""
    scenario = harness.build_scenario("S2_multi")
    tool_prompt = harness.build_scenario("S4_tools").prompt
    assert isinstance(tool_prompt, ChatPrompt)
    assert isinstance(scenario.prompt, dict)
    scenario.prompt = {
        key: ChatPrompt(
            messages=prompt.get_messages(),
            tools=copy.deepcopy(tool_prompt.tools),
            function_map=tool_prompt.function_map,
            model=prompt.model,
        )
        for key, prompt in scenario.prompt.items()
    }
    assert {p.name for p in scenario.prompt.values()} == {"chat-prompt"}
    scenario.tools = True
    return scenario


@pytest.mark.parametrize("optimizer_name", TOOL_OPTIMIZERS)
def test_selected_bundle_tool_is_rewritten_by_key(
    optimizer_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = _default_named_tool_bundle()
    install_fakes(scenario)
    seen: dict[str, set[str]] = {"planner": set(), "answerer": set()}
    original_invoke = harness.TwoStepAgent.invoke_agent

    def invoke(
        self: Any, prompts: Any, dataset_item: Any, *args: Any, **kwargs: Any
    ) -> Any:
        for key in seen:
            seen[key].add(prompts[key].tools[0]["function"]["description"])
        return original_invoke(self, prompts, dataset_item, *args, **kwargs)

    monkeypatch.setattr(harness.TwoStepAgent, "invoke_agent", invoke)

    harness.make_optimizer(optimizer_name).optimize_prompt(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        agent=scenario.agent,
        max_trials=harness.MAX_TRIALS[optimizer_name],
        optimize_tools={f"planner.{harness.TOOL_NAME}": True},
    )

    assert seen["planner"] - {harness.TOOL_DESCRIPTION}
    assert seen["answerer"] == {harness.TOOL_DESCRIPTION}


@pytest.mark.parametrize("optimizer_name", TOOL_OPTIMIZERS)
def test_bundle_selector_that_enables_nothing_leaves_tools_alone(
    optimizer_name: str, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = _default_named_tool_bundle()
    install_fakes(scenario)
    descriptions: set[str] = set()
    original_invoke = harness.TwoStepAgent.invoke_agent

    def invoke(
        self: Any, prompts: Any, dataset_item: Any, *args: Any, **kwargs: Any
    ) -> Any:
        descriptions.update(
            p.tools[0]["function"]["description"] for p in prompts.values()
        )
        return original_invoke(self, prompts, dataset_item, *args, **kwargs)

    monkeypatch.setattr(harness.TwoStepAgent, "invoke_agent", invoke)

    harness.make_optimizer(optimizer_name).optimize_prompt(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        agent=scenario.agent,
        max_trials=harness.MAX_TRIALS[optimizer_name],
        optimize_tools={f"planner.{harness.TOOL_NAME}": False},
    )

    assert descriptions == {harness.TOOL_DESCRIPTION}
