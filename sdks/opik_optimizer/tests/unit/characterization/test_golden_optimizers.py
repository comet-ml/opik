# mypy: disable-error-code=no-untyped-def
"""Golden characterization tests: pin the current end-to-end optimizer behaviour.

Each (optimizer, scenario) pair runs ``optimize_prompt`` (``optimize_parameter``
for ParameterOptimizer) against the deterministic fakes in this package and
compares a normalised snapshot of the result with ``golden/<opt>__<scn>.json``.

Regenerate snapshots after an intentional behaviour change with::

    UPDATE_GOLDEN=1 pytest tests/unit/characterization
"""

from __future__ import annotations

import pytest

from tests.unit.characterization import harness

# Pairs whose golden snapshot pins a known bug or degraded output. The note is
# copied into the snapshot ("known_issue") so a fix shows up as a deliberate
# golden diff. Remove the entry together with the fix.
KNOWN_ISSUES: dict[tuple[str, str], str] = {}


@pytest.mark.parametrize("scenario_name", harness.SCENARIOS)
@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
def test_golden(optimizer_name: str, scenario_name: str, install_fakes) -> None:
    scenario = harness.build_scenario(scenario_name)
    fake = install_fakes(scenario)
    try:
        result = harness.run_optimizer(optimizer_name, scenario)
    except Exception as exc:  # pinned as "current behaviour" in the snapshot
        snapshot = harness.exception_snapshot(exc)
    else:
        snapshot = harness.result_snapshot(result, scenario)
    # Which optimizer-side LLM operations ran (not how often): catches a code
    # path silently disappearing without pinning retry counts.
    snapshot["llm_call_kinds"] = sorted(set(fake.calls))
    snapshot["known_issue"] = KNOWN_ISSUES.get((optimizer_name, scenario_name))
    assert not fake.unknown_requests, fake.unknown_requests
    harness.assert_matches_golden(optimizer_name, scenario_name, snapshot)


def test_parameter_optimizer_optimize_prompt_raises(install_fakes) -> None:
    # current behaviour: ParameterOptimizer only supports optimize_parameter();
    # optimize_prompt raises NotImplementedError.
    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    optimizer = harness.ParameterOptimizer(model=harness.MODEL, verbose=0)
    with pytest.raises(NotImplementedError, match="optimize_parameter"):
        optimizer.optimize_prompt(
            prompt=scenario.prompt,
            dataset=harness.make_dataset(scenario),
            metric=harness.answer_match,
        )


@pytest.mark.parametrize(
    "optimizer_name",
    [name for name in harness.OPTIMIZERS if name != "ParameterOptimizer"],
)
def test_dict_prompt_with_optimize_tools_false_runs(
    optimizer_name: str, install_fakes
) -> None:
    # optimize_tools=False explicitly *disables* tool optimization, which dict
    # prompts support; it must not trip the single-prompt-only guard.
    scenario = harness.build_scenario("S2_multi")
    install_fakes(scenario)
    optimizer = harness.make_optimizer(optimizer_name)
    result = optimizer.optimize_prompt(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        agent=scenario.agent,
        max_trials=1,
        optimize_tools=False,
    )
    assert isinstance(result.prompt, dict)


@pytest.mark.parametrize(
    "optimizer_name",
    [
        name
        for name in harness.OPTIMIZERS
        if getattr(harness, name).supports_tool_optimization
    ],
)
def test_dict_prompt_without_tools_rejects_tool_optimization(
    optimizer_name: str, install_fakes
) -> None:
    # Tool optimization needs at least one selected tool.
    scenario = harness.build_scenario("S2_multi")
    install_fakes(scenario)
    optimizer = harness.make_optimizer(optimizer_name)
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
        max_trials=3,
        optimize_tools={f"planner.{harness.TOOL_NAME}": True},
    )
    assert set(result.prompt) == {"planner", "answerer"}
    assert result.prompt["answerer"].tools == scenario.prompt["answerer"].tools
    assert result.prompt["planner"].function_map == tool_prompt.function_map
