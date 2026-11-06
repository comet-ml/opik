# mypy: disable-error-code=no-untyped-def
"""Tool-optimization flags through the public optimize_prompt entrypoint."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from tests.unit.characterization import harness


@pytest.mark.parametrize(
    "scenario_name,optimize_tools", [("S1_single", False), ("S2_multi", {})]
)
def test_prompt_without_tools_with_falsy_optimize_tools_runs(
    scenario_name: str, optimize_tools: Any, install_fakes
) -> None:
    # A falsy optimize_tools means "not requested" for every prompt shape; it must
    # not demand tools or trip the single-prompt-only guard.
    scenario = harness.build_scenario(scenario_name)
    install_fakes(scenario)
    optimizer = harness.make_optimizer("MetaPromptOptimizer")
    result = optimizer.optimize_prompt(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        agent=scenario.agent,
        max_trials=1,
        optimize_tools=optimize_tools,
    )
    assert isinstance(result.prompt, type(scenario.prompt))


def test_dict_prompt_without_tools_rejects_tool_optimization(install_fakes) -> None:
    # Tool optimization needs at least one selected tool.
    scenario = harness.build_scenario("S2_multi")
    install_fakes(scenario)
    optimizer = harness.make_optimizer("MetaPromptOptimizer")
    with pytest.raises(ValueError, match="must include selected tools"):
        optimizer.optimize_prompt(
            prompt=scenario.prompt,
            dataset=harness.make_dataset(scenario),
            metric=harness.answer_match,
            agent=scenario.agent,
            max_trials=1,
            optimize_tools=True,
        )


@pytest.mark.parametrize(
    "optimizer_name",
    [
        "MetaPromptOptimizer",
        "EvolutionaryOptimizer",
        "HierarchicalReflectiveOptimizer",
        "GepaOptimizer",
    ],
)
def test_bundle_tools_can_select_one_prompt(optimizer_name, install_fakes):
    import copy

    scenario = harness.build_scenario("S2_multi")
    tool_prompt = harness.build_scenario("S4_tools").prompt
    assert isinstance(scenario.prompt, dict)
    assert isinstance(tool_prompt, ChatPrompt)
    for prompt in scenario.prompt.values():
        prompt.tools = copy.deepcopy(tool_prompt.tools)
        prompt.function_map = tool_prompt.function_map
    scenario.tools = True
    install_fakes(scenario)
    optimizer = harness.make_optimizer(optimizer_name)
    result = optimizer.optimize_prompt(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        agent=scenario.agent,
        max_trials=harness.MAX_TRIALS[optimizer_name],
        optimize_tools={f"planner.{harness.TOOL_NAME}": True},
    )
    assert isinstance(result.prompt, dict)
    assert set(result.prompt) == {"planner", "answerer"}
    assert result.prompt["answerer"].tools == scenario.prompt["answerer"].tools
    assert result.prompt["planner"].function_map == tool_prompt.function_map
