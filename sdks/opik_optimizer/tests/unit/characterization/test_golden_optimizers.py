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
KNOWN_ISSUES: dict[tuple[str, str], str] = {
    # current behaviour: the returned prompt's user message lost its image_url
    # part (content collapsed to the LLM's plain string), so the rescore drops.
    ("EvolutionaryOptimizer", "S3_multimodal"): (
        "drops the user image part: returned user content is a plain string"
    ),
    # current behaviour: no candidate beats the baseline and the original prompt
    # is returned (candidates lose the user image part, as in S3).
    ("EvolutionaryOptimizer", "S3b_multimodal_multitext"): (
        "no candidate beats the baseline; original prompt returned"
    ),
    # current behaviour: the rewritten system text is written into every text
    # part of the system message (duplicated text, second part's text lost).
    ("GepaOptimizer", "S3b_multimodal_multitext"): (
        "duplicates the rewritten text across both system text parts"
    ),
    # current behaviour: the few-shot templated text replaces both system text
    # parts (duplicated example block, "Reply with the code only." lost).
    ("FewShotBayesianOptimizer", "S3b_multimodal_multitext"): (
        "duplicates the templated system text across both text parts"
    ),
    # current behaviour: the two system text parts collapse into one string
    # taken from the LLM's plain-string answer.
    ("MetaPromptOptimizer", "S3b_multimodal_multitext"): (
        "system content parts collapsed into a single string"
    ),
    # current behaviour: the two system text parts collapse into one string.
    ("HierarchicalReflectiveOptimizer", "S3b_multimodal_multitext"): (
        "system content parts collapsed into a single string"
    ),
    # current behaviour: optimize_parameter results carry no finish_reason.
    ("ParameterOptimizer", "S1_single"): "details have no finish_reason",
    ("ParameterOptimizer", "S2_multi"): "details have no finish_reason",
    ("ParameterOptimizer", "S3_multimodal"): "details have no finish_reason",
    ("ParameterOptimizer", "S3b_multimodal_multitext"): "details have no finish_reason",
    ("ParameterOptimizer", "S4_tools"): "details have no finish_reason",
}


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
def test_dict_prompt_with_optimize_tools_false_raises(
    optimizer_name: str, install_fakes
) -> None:
    # current behaviour: passing optimize_tools=False (explicitly *disabling*
    # tool optimization) together with a dict of prompts raises, because setup
    # treats any non-None optimize_tools as "provided".
    scenario = harness.build_scenario("S2_multi")
    install_fakes(scenario)
    optimizer = harness.make_optimizer(optimizer_name)
    with pytest.raises(
        ValueError, match="Tool description optimization only supports single prompts"
    ):
        optimizer.optimize_prompt(
            prompt=scenario.prompt,
            dataset=harness.make_dataset(scenario),
            metric=harness.answer_match,
            agent=scenario.agent,
            max_trials=1,
            optimize_tools=False,
        )
