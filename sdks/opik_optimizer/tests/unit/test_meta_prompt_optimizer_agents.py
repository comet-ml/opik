# mypy: ignore-errors

import pytest

from opik_optimizer import MetaPromptOptimizer, ChatPrompt
from opik_optimizer.algorithms.meta_prompt_optimizer.bundle_agent import BundleAgent
from opik_optimizer.mcp_utils.mcp_workflow import MCPExecutionConfig


def test_optimize_prompt_bundle_updates_best(monkeypatch):
    optimizer = MetaPromptOptimizer(model="gpt-4o")

    initial_prompts = {"agent_a": ChatPrompt(name="agent_a", system="do the task")}

    def fake_generate(*args, **kwargs):
        updated = {
            "agent_a": ChatPrompt(
                name="agent_a",
                system="do the task but better",
            )
        }
        return updated, {
            "agent_a": {"improvement_focus": "clarity", "reasoning": "added guidance"}
        }

    monkeypatch.setattr(
        optimizer,
        "_generate_agent_bundle_candidates",
        fake_generate,
    )

    # Fake runner + metric
    def run_bundle_fn(bundle, item):
        return {"final_output": "ignored"}

    def metric(item, output, trace=None):
        return 1.0 if "better" in trace.get("system", "") else 0.0  # type: ignore[union-attr]

    result = optimizer.optimize_prompt(
        prompt=initial_prompts,
        dataset=type(
            "D",
            (),
            {
                "get_items": lambda self: [{}],
                "name": "dummy",
                "id": "dummy",
            },
        )(),
        metric=metric,
        candidate_generator_kwargs={"run_bundle_fn": run_bundle_fn},
        max_trials=1,
    )

    assert result.score == 1.0


def test_optimize_prompt_bundle_rejects_mcp(monkeypatch):
    optimizer = MetaPromptOptimizer(model="gpt-4o")
    prompts = {"agent_a": ChatPrompt(name="agent_a", system="sys")}

    with pytest.raises(ValueError):
        optimizer.optimize_prompt(
            prompt=prompts,
            dataset=type("D", (), {"get_items": lambda self: [{}]})(),
            metric=lambda item, output, trace=None: 0.0,
            candidate_generator_kwargs={
                "run_bundle_fn": lambda bundle, item: {"final_output": "x"}
            },
            max_trials=1,
            mcp_config=MCPExecutionConfig(
                coordinator=None,
                tool_name="tool",
                fallback_arguments=None,
                fallback_invoker=None,
            ),
        )


def test_optimize_prompt_bundle_preserves_non_returned_agent(monkeypatch):
    optimizer = MetaPromptOptimizer(model="gpt-4o")
    initial_prompts = {
        "agent_a": ChatPrompt(name="agent_a", system="keep me"),
        "agent_b": ChatPrompt(name="agent_b", system="unchanged"),
    }

    def fake_generate(*args, **kwargs):
        updated = {
            "agent_a": ChatPrompt(
                name="agent_a",
                system="updated",
            )
        }  # missing agent_b on purpose
        return updated, {
            "agent_a": {"improvement_focus": "clarity", "reasoning": "n/a"}
        }

    monkeypatch.setattr(
        optimizer,
        "_generate_agent_bundle_candidates",
        fake_generate,
    )

    result = optimizer.optimize_prompt(
        prompt=initial_prompts,
        dataset=type("D", (), {"get_items": lambda self: [{}]})(),
        metric=lambda item, output, trace=None: 0.0,
        candidate_generator_kwargs={
            "run_bundle_fn": lambda bundle, item: {"final_output": "x"}
        },
        max_trials=1,
    )

    best_prompts = result.details.get("best_prompts", initial_prompts)  # type: ignore[arg-type]
    assert "agent_b" in best_prompts
    assert best_prompts["agent_b"].system == "unchanged"


def test_optimize_prompt_bundle_metric(monkeypatch):
    optimizer = MetaPromptOptimizer(model="gpt-4o")
    initial_prompts = {
        "step1": ChatPrompt(name="step1", system="do step1"),
        "step2": ChatPrompt(name="step2", system="do step2"),
    }

    dataset_items = [{"q": "a"}, {"q": "b"}]

    class DummyDataset:
        def get_items(self):
            return dataset_items

    def run_bundle_fn(bundle, item):
        joined_names = "-".join(sorted(bundle.keys()))
        return {
            "final_output": f"{item['q']}-{joined_names}",
            "trace": {"agents": list(bundle.keys())},
        }

    def metric(item, output, trace=None):
        assert trace["agents"] == ["step1", "step2"]
        return 1.0 if output.startswith(item["q"]) else 0.0

    monkeypatch.setattr(
        optimizer,
        "_generate_agent_bundle_candidates",
        lambda **kwargs: (initial_prompts, {}),
    )

    result = optimizer.optimize_prompt(
        prompt=initial_prompts,
        dataset=DummyDataset(),
        metric=metric,
        candidate_generator_kwargs={"run_bundle_fn": run_bundle_fn},
        max_trials=1,
    )

    assert result.score == 1.0


def test_optimize_prompt_bundle_uses_bundle_agent(monkeypatch):
    optimizer = MetaPromptOptimizer(model="gpt-4o")
    initial_prompts = {
        "step1": ChatPrompt(name="step1", system="do step1"),
    }

    dataset_items = [{"q": "x"}]

    class DummyDataset:
        def get_items(self):
            return dataset_items

    monkeypatch.setattr(
        optimizer,
        "_generate_agent_bundle_candidates",
        lambda **kwargs: (initial_prompts, {}),
    )

    result = optimizer.optimize_prompt(
        prompt=initial_prompts,
        dataset=DummyDataset(),
        metric=lambda item, output, trace=None: 1.0,
        candidate_generator_kwargs={"bundle_agent_class": BundleAgent},
        max_trials=1,
    )

    assert isinstance(result.prompt, list)
