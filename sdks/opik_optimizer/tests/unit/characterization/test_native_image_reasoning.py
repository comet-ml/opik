"""Exercise every optimizer through the shared client down to the provider boundary."""

from copy import deepcopy
from types import SimpleNamespace
from typing import Any

import pytest

from opik_optimizer import MetaPromptOptimizer
from opik_optimizer.core import llm_calls
from tests.unit.characterization import harness

REAL_CALL = llm_calls.call_model
REAL_ASYNC_CALL = llm_calls.call_model_async


def native_images(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        part
        for message in messages
        if isinstance(message.get("content"), list)
        for part in message["content"]
        if part.get("type") == "image_url"
    ]


def capture_provider(
    scenario: Any, install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> list[dict[str, Any]]:
    fake = install_fakes(scenario)
    captured: list[dict[str, Any]] = []

    def invoke(_completion: Any, _params: Any, **kwargs: Any) -> Any:
        captured.append(deepcopy(kwargs))
        return SimpleNamespace(messages=kwargs["messages"])

    async def invoke_async(*args: Any, **kwargs: Any) -> Any:
        return invoke(*args, **kwargs)

    def parse(
        response: Any, response_model: Any = None, return_all: bool = False
    ) -> Any:
        captured[-1]["response_model"] = (
            response_model.__name__ if response_model else None
        )
        return fake.call_model(
            messages=response.messages,
            response_model=response_model,
            return_all=return_all,
        )

    monkeypatch.setattr(llm_calls, "call_model", REAL_CALL)
    monkeypatch.setattr(llm_calls, "call_model_async", REAL_ASYNC_CALL)
    monkeypatch.setattr(llm_calls, "_invoke_traced", invoke)
    monkeypatch.setattr(llm_calls, "_invoke_traced_async", invoke_async)
    monkeypatch.setattr(llm_calls, "_parse_response", parse)
    return captured


@pytest.mark.parametrize(
    "optimizer_name",
    [
        "MetaPromptOptimizer",
        "HierarchicalReflectiveOptimizer",
        "FewShotBayesianOptimizer",
        "GepaOptimizer",
    ],
)
def test_public_run_sends_native_images_to_optimizer_and_preserves_agent_media(
    optimizer_name: str,
    install_fakes: Any,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    scenario = harness.build_scenario("S3_multimodal")
    assert isinstance(scenario.prompt, harness.ChatPrompt)
    scenario.prompt.model = "agent-model"
    scenario.prompt.model_kwargs = {}
    original = deepcopy(scenario.prompt.get_messages())
    captured = capture_provider(scenario, install_fakes, monkeypatch)
    result = harness.run_optimizer(optimizer_name, scenario)
    assert captured, "optimizer must reach its own LLM"
    assert all(call["model"] != "agent-model" for call in captured)
    images = [part for call in captured for part in native_images(call["messages"])]
    assert any(part["image_url"]["url"] == harness.IMAGE_DATA_URL for part in images)
    for call in captured:
        for message in call["messages"]:
            text = (
                message["content"]
                if isinstance(message["content"], str)
                else "\n".join(part.get("text", "") for part in message["content"])
            )
            assert harness.IMAGE_DATA_URL not in text
    assert scenario.prompt.get_messages() == original
    assert result.prompt.model == "agent-model" and result.prompt.model_kwargs == {}
    assert result.prompt.get_messages()[-1]["content"][1] == original[1]["content"][1]


def test_image_reasoning_rejects_text_only_optimizer_model_without_using_agent_model(
    install_fakes: Any,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    scenario = harness.build_scenario("S3_multimodal")
    install_fakes(scenario)
    optimizer = MetaPromptOptimizer(model="openai/gpt-3.5-turbo", verbose=0)
    monkeypatch.setattr(llm_calls, "call_model", REAL_CALL)
    reached_provider = []
    monkeypatch.setattr(
        llm_calls, "_invoke_traced", lambda *a, **kw: reached_provider.append(kw)
    )
    with pytest.raises(ValueError, match="cannot read images"):
        optimizer.optimize_prompt(
            prompt=scenario.prompt,
            dataset=harness.make_dataset(scenario),
            metric=harness.answer_match,
            max_trials=2,
        )
    assert not reached_provider
