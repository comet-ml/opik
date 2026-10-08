"""Unit tests for the studio model wiring.

The Optimization Studio lets the optimizer/algorithm (GEPA's reflection LM,
hierarchical's reasoning model) run on a different model than the prompt. These
tests verify, deterministically and offline:

- the separate algorithm model is parsed out of the optimizer parameters,
- the prompt is built with its configured model + parameters,
- the optimizer is built with its configured model + parameters,
- the optimizer defaults to the prompt model when none is set.
"""

import litellm
import pytest
from llm_constants import (
    ANTHROPIC_CLAUDE_HAIKU,
    ANTHROPIC_CLAUDE_OPUS,
    GATEWAY_CLAUDE_HAIKU,
    GATEWAY_CLAUDE_OPUS,
    GEMINI_3_FLASH,
)

from opik_backend.jobs import optimizer_runner
from opik_backend.studio.config import OPTIMIZER_TASK_TEMPERATURE
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


def test_task_model_temperature_is_pinned_on_the_prompt():
    """OPIK-7511: the pin must survive all the way onto the object that carries
    the scored completions — asserting the helper alone would not prove the task
    model actually runs pinned, and the reflection model must stay sampled."""
    config = OptimizationConfig.from_dict(_config())

    optimizer, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    assert prompt.model_kwargs.get("temperature") == OPTIMIZER_TASK_TEMPERATURE
    # The reflection model needs sampling diversity — it must NOT be pinned.
    assert "temperature" not in optimizer.model_parameters


def test_gemini_3_task_model_is_not_pinned_on_the_prompt():
    config = OptimizationConfig.from_dict(_config(task_model=GEMINI_3_FLASH))

    _, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    assert "temperature" not in prompt.model_kwargs


_GATEWAY_REPLY = {
    "id": "chatcmpl-test",
    "object": "chat.completion",
    "created": 0,
    "model": "stub",
    "choices": [
        {
            "index": 0,
            "message": {"role": "assistant", "content": "ok"},
            "finish_reason": "stop",
        }
    ],
    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
}


@pytest.mark.parametrize(
    "task_model,expected_temperature",
    [
        (GEMINI_3_FLASH, "absent"),
        ("vertex_ai/gemini-3.1-pro-preview", "absent"),
        ("gemini-2.5-flash", OPTIMIZER_TASK_TEMPERATURE),
        ("custom-llm/acme/gemini-3-chat", OPTIMIZER_TASK_TEMPERATURE),
        ("gpt-4o-mini", OPTIMIZER_TASK_TEMPERATURE),
    ],
)
def test_task_model_request_body_sent_to_the_gateway(
    httpserver, task_model, expected_temperature
):
    httpserver.expect_request(
        "/v1/private/chat/completions", method="POST"
    ).respond_with_json(_GATEWAY_REPLY)
    config = OptimizationConfig.from_dict(_config(task_model=task_model))
    _, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    litellm.completion(
        model=prompt.model,
        messages=[{"role": "user", "content": "hi"}],
        api_base=httpserver.url_for("/v1/private"),
        api_key="test",
        **prompt.model_kwargs,
    )

    body = httpserver.log[-1][0].get_json()
    assert body["model"] == task_model
    assert body.get("temperature", "absent") == expected_temperature


def test_task_model_explicit_temperature_survives_the_pin():
    config = OptimizationConfig.from_dict(_config(task_params={"temperature": 0.4}))

    _, prompt = optimizer_runner.build_optimizer_and_prompt(config)

    assert prompt.model_kwargs.get("temperature") == 0.4


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
