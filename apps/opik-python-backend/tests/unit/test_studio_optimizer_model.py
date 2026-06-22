"""Unit tests for the studio model wiring.

The Optimization Studio lets the optimizer/algorithm (GEPA's reflection LM,
hierarchical's reasoning model) run on a different model than the prompt. These
tests verify, deterministically and offline:

- the separate algorithm model is parsed out of the optimizer parameters,
- the prompt is built with its configured model + parameters,
- the optimizer is built with its configured model + parameters,
- the optimizer defaults to the prompt model when none is set.
"""

from llm_constants import (
    ANTHROPIC_CLAUDE_HAIKU,
    ANTHROPIC_CLAUDE_OPUS,
    GATEWAY_CLAUDE_HAIKU,
    GATEWAY_CLAUDE_OPUS,
)

from opik_backend.jobs import optimizer_runner
from opik_backend.studio.types import OptimizationConfig


def _config(
    task_model: str = ANTHROPIC_CLAUDE_HAIKU,
    task_params: dict | None = None,
    optimizer_params: dict | None = None,
) -> dict:
    return {
        "dataset_name": "ds",
        "prompt": {"messages": [{"role": "user", "content": "{{text}}"}]},
        "llm_model": {"model": task_model, "parameters": task_params or {}},
        "evaluation": {
            "metrics": [{"type": "equals", "parameters": {"reference_key": "label"}}]
        },
        "optimizer": {"type": "gepa", "parameters": optimizer_params or {"seed": 42}},
    }


def test_optimizer_model_extracted_from_optimizer_params():
    config = OptimizationConfig.from_dict(
        _config(
            optimizer_params={
                "seed": 42,
                "model": ANTHROPIC_CLAUDE_OPUS,
                "model_parameters": {"temperature": 0.5},
            }
        )
    )

    # The separate algorithm model + its params are surfaced...
    assert config.optimizer_model == ANTHROPIC_CLAUDE_OPUS
    assert config.optimizer_model_params == {"temperature": 0.5}
    # ...and removed from the kwargs passed to the optimizer constructor.
    assert config.optimizer_params == {"seed": 42}
    # The prompt/task model is untouched.
    assert config.model == ANTHROPIC_CLAUDE_HAIKU


def test_optimizer_model_defaults_to_none_when_absent():
    config = OptimizationConfig.from_dict(_config(optimizer_params={"seed": 7}))

    assert config.optimizer_model is None
    assert config.optimizer_model_params is None
    assert config.optimizer_params == {"seed": 7}


def test_prompt_and_algorithm_use_their_configured_models_and_params():
    config = OptimizationConfig.from_dict(
        _config(
            task_model=ANTHROPIC_CLAUDE_HAIKU,
            task_params={"temperature": 0.3},
            optimizer_params={
                "seed": 42,
                "model": ANTHROPIC_CLAUDE_OPUS,
                "model_parameters": {"temperature": 0.7},
            },
        )
    )

    optimizer, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    # Prompt (task evaluation) uses the configured prompt model + params,
    # gateway-routed, with the studio defaults applied.
    assert prompt.model == GATEWAY_CLAUDE_HAIKU
    assert prompt.model_kwargs.get("temperature") == 0.3
    assert prompt.model_kwargs.get("stream") is False
    assert "max_tokens" in prompt.model_kwargs

    # Optimizer (algorithm) uses its own configured model + params.
    assert optimizer.model == GATEWAY_CLAUDE_OPUS
    assert optimizer.model_parameters.get("temperature") == 0.7
    assert optimizer.model_parameters.get("stream") is False
    assert "max_tokens" in optimizer.model_parameters


def test_algorithm_defaults_to_prompt_model_when_not_set():
    config = OptimizationConfig.from_dict(
        _config(
            task_model=ANTHROPIC_CLAUDE_HAIKU,
            task_params={"temperature": 0.3},
            optimizer_params={"seed": 42},
        )
    )

    optimizer, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    assert prompt.model == GATEWAY_CLAUDE_HAIKU
    # No separate algorithm model → optimizer falls back to the prompt model
    # and its parameters.
    assert optimizer.model == GATEWAY_CLAUDE_HAIKU
    assert optimizer.model_parameters.get("temperature") == 0.3


def test_optimizer_params_preserved_without_separate_model():
    # model_parameters set on the optimizer but no model — the optimizer should
    # still default to the prompt model yet keep its own configured params
    # (not silently drop them).
    config = OptimizationConfig.from_dict(
        _config(
            task_model=ANTHROPIC_CLAUDE_HAIKU,
            task_params={"temperature": 0.3},
            optimizer_params={"seed": 42, "model_parameters": {"temperature": 0.9}},
        )
    )

    optimizer, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    assert optimizer.model == GATEWAY_CLAUDE_HAIKU
    assert optimizer.model_parameters.get("temperature") == 0.9
    # The prompt keeps its own params, independent of the optimizer's.
    assert prompt.model_kwargs.get("temperature") == 0.3
