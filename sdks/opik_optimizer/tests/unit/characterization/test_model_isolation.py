"""Exercise real optimizer flows with deliberately distinct model configurations."""

from __future__ import annotations

from typing import Any
import copy

import optuna
import pytest

from opik_optimizer.core import llm_calls
from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from tests.unit.characterization import harness


@pytest.mark.parametrize("optimizer_name", harness.OPTIMIZERS)
@pytest.mark.parametrize("scenario_name", ["S1_single", "S2_multi"])
def test_agent_and_optimizer_models_are_independent(
    optimizer_name: str,
    scenario_name: str,
    install_fakes: Any,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    scenario = harness.build_scenario(scenario_name)
    prompts = (
        scenario.prompt
        if isinstance(scenario.prompt, dict)
        else {scenario.prompt.name: scenario.prompt}
    )
    expected = {}
    for index, (name, prompt) in enumerate(prompts.items()):
        prompt.model = f"agent-model-{name}"
        prompt.model_kwargs = {
            "temperature": 0.1 + index / 10,
            "max_tokens": 100 + index,
            "metadata": {"owner": name},
        }
        expected[name] = (prompt.model, copy.deepcopy(prompt.model_kwargs))
    install_fakes(scenario)
    options = dict(
        model="optimizer-model",
        model_parameters={
            "temperature": 0.91,
            "max_tokens": 321,
            "metadata": {"owner": "optimizer"},
        },
        n_threads=1,
        verbose=0,
        seed=harness.SEED,
        skip_perfect_score=False,
    )
    if optimizer_name == "EvolutionaryOptimizer":
        options.update(population_size=4, num_generations=2)
    if optimizer_name == "MetaPromptOptimizer":
        options.update(prompts_per_round=2)
    if optimizer_name == "FewShotBayesianOptimizer":
        options.update(min_examples=1, max_examples=3)
    if optimizer_name == "ParameterOptimizer":
        options.update(default_n_trials=6)
    if optimizer_name == "HierarchicalReflectiveOptimizer":
        options.update(
            reasoning_model="reasoning-model",
            reasoning_model_parameters={"temperature": 0.82, "max_tokens": 654},
            max_parallel_batches=1,
        )
    optimizer = getattr(harness, optimizer_name)(**options)
    reasoning_calls = []
    agent_calls = []
    trial_models = []
    trial_kwargs = []
    original_call, original_async = llm_calls.call_model, llm_calls.call_model_async

    def reasoning(**kwargs: Any) -> Any:
        reasoning_calls.append(
            (kwargs["model"], copy.deepcopy(kwargs.get("model_parameters") or {}))
        )
        return original_call(**kwargs)

    async def reasoning_async(**kwargs: Any) -> Any:
        reasoning_calls.append(
            (kwargs["model"], copy.deepcopy(kwargs.get("model_parameters") or {}))
        )
        return await original_async(**kwargs)

    monkeypatch.setattr(llm_calls, "call_model", reasoning)
    monkeypatch.setattr(llm_calls, "call_model_async", reasoning_async)
    if scenario.agent is None:
        original_complete = LiteLLMAgent._llm_complete

        def complete(
            self: Any,
            model: Any,
            messages: Any,
            tools: Any,
            seed: Any = None,
            model_kwargs: Any = None,
        ) -> Any:
            agent_calls.append((model, copy.deepcopy(model_kwargs or {})))
            return original_complete(self, model, messages, tools, seed, model_kwargs)

        monkeypatch.setattr(LiteLLMAgent, "_llm_complete", complete)
    else:
        original_invoke = scenario.agent.invoke_agent

        def invoke(
            prompts: Any,
            dataset_item: Any,
            allow_tool_use: bool = False,
            seed: Any = None,
        ) -> Any:
            agent_calls.extend(
                (prompt.model, copy.deepcopy(prompt.model_kwargs))
                for prompt in prompts.values()
            )
            return original_invoke(
                prompts, dataset_item, allow_tool_use=allow_tool_use, seed=seed
            )

        monkeypatch.setattr(scenario.agent, "invoke_agent", invoke)

    original_attr = optuna.trial.Trial.set_user_attr

    def record_attr(trial: Any, key: str, value: Any) -> None:
        if key == "model":
            trial_models.append(copy.deepcopy(value))
        if key == "model_kwargs":
            trial_kwargs.append(copy.deepcopy(value))
        return original_attr(trial, key, value)

    monkeypatch.setattr(optuna.trial.Trial, "set_user_attr", record_attr)
    kwargs = dict(
        prompt=scenario.prompt,
        dataset=harness.make_dataset(scenario),
        metric=harness.answer_match,
        agent=scenario.agent,
        max_trials=harness.MAX_TRIALS[optimizer_name],
    )
    if optimizer_name == "ParameterOptimizer":
        result = optimizer.optimize_parameter(
            parameter_space={"top_p": {"type": "float", "min": 0.5, "max": 1.0}},
            **kwargs,
        )
        assert not reasoning_calls
    else:
        result = optimizer.optimize_prompt(**kwargs)
        assert reasoning_calls
        for model, params in reasoning_calls:
            if optimizer_name == "HierarchicalReflectiveOptimizer":
                assert model == "reasoning-model"
                assert params == optimizer.reasoning_model_parameters
            else:
                assert model == "optimizer-model"
                assert params == optimizer.model_parameters
    assert agent_calls
    by_model = {model: params for model, params in expected.values()}
    for model, params in agent_calls:
        assert model in by_model
        actual = dict(params)
        if optimizer_name == "ParameterOptimizer":
            actual.pop("top_p", None)
        assert actual == by_model[model]
    returned = (
        result.prompt
        if isinstance(result.prompt, dict)
        else {result.prompt.name: result.prompt}
    )
    assert set(returned) == set(expected)
    for name, prompt in returned.items():
        assert prompt.model == expected[name][0]
        actual = dict(prompt.model_kwargs)
        if optimizer_name == "ParameterOptimizer":
            actual.pop("top_p", None)
        assert actual == expected[name][1]
    for name, prompt in prompts.items():
        assert (prompt.model, prompt.model_kwargs) == expected[name]
    if optimizer_name == "FewShotBayesianOptimizer":
        assert trial_models and trial_kwargs
        expected_models = (
            next(iter(expected.values()))[0]
            if len(expected) == 1
            else {name: value[0] for name, value in expected.items()}
        )
        expected_kwargs = (
            next(iter(expected.values()))[1]
            if len(expected) == 1
            else {name: value[1] for name, value in expected.items()}
        )
        assert all(value == expected_models for value in trial_models)
        assert all(value == expected_kwargs for value in trial_kwargs)
