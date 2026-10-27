"""The optimizer sees native evidence and can edit only selected text slots."""

from copy import deepcopy
from typing import Any

import pytest

from opik_optimizer import ChatPrompt
from opik_optimizer.core.reasoning import (
    EvidenceCase,
    ReasoningEvidence,
    build_reasoning_messages,
)
from opik_optimizer.utils.prompt_editing import (
    TextEdit,
    apply_text_edits,
    describe_text_segments,
)

from tests.image_fixtures import RED_IMAGE

IMAGE = RED_IMAGE


def vision_prompt() -> ChatPrompt:
    return ChatPrompt(
        name="vision",
        model="agent-model",
        model_parameters={},
        messages=[
            {"role": "system", "content": "Read the picture."},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Question: {question}"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "{image}", "detail": "low"},
                    },
                    {"type": "text", "text": "Give the code."},
                ],
            },
        ],
    )


def test_native_evidence_renders_each_case_and_keeps_scores_and_image_association() -> (
    None
):
    prompt = vision_prompt()
    before = deepcopy(prompt.get_messages())
    evidence = ReasoningEvidence(
        prompts={"vision": prompt},
        cases=[
            EvidenceCase(
                case_id="case-red",
                inputs={"question": "red?", "image": IMAGE},
                output="wrong",
                scores={"accuracy": 0},
            ),
            EvidenceCase(
                case_id="case-blue",
                inputs={"question": "blue?", "image": "https://example.com/blue.png"},
                output="blue",
                scores={"accuracy": 1},
            ),
        ],
    )
    instructions = [{"role": "system", "content": "Improve the instructions."}]
    messages = build_reasoning_messages(instructions, evidence=evidence)
    assert messages[0] == instructions[0]
    case_messages = [
        message for message in messages if isinstance(message["content"], list)
    ]
    assert len(case_messages) == 2
    for message, case_id, url, score in zip(
        case_messages,
        ["case-red", "case-blue"],
        [IMAGE, "https://example.com/blue.png"],
        [0, 1],
    ):
        text = "\n".join(
            part["text"] for part in message["content"] if part["type"] == "text"
        )
        images = [part for part in message["content"] if part["type"] == "image_url"]
        assert case_id in text and "accuracy" in text and str(score) in text
        assert url not in text
        assert images == [
            {"type": "image_url", "image_url": {"url": url, "detail": "low"}}
        ]
    assert prompt.get_messages() == before
    assert prompt.model == "agent-model" and prompt.model_kwargs == {}


def test_explicit_text_only_mode_omits_media_without_changing_the_prompt() -> None:
    prompt = vision_prompt()
    evidence = ReasoningEvidence(
        prompts={"vision": prompt},
        cases=[EvidenceCase(case_id="a", inputs={"question": "hi", "image": IMAGE})],
    )
    native = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "Look"},
                {"type": "image_url", "image_url": {"url": IMAGE}},
            ],
        }
    ]
    original = deepcopy(native)
    messages = build_reasoning_messages(native, evidence=evidence, mode="text_only")
    assert IMAGE not in str(messages)
    assert "image" in str(messages).lower()
    assert not any(isinstance(message["content"], list) for message in messages)
    assert prompt.get_messages()[1]["content"][1]["image_url"]["url"] == "{image}"
    assert native == original


@pytest.mark.parametrize(
    "part",
    [
        {"type": "input_audio", "input_audio": {"data": "aGVsbG8=", "format": "wav"}},
        {"type": "video_url", "video_url": {"url": "https://example.com/movie.mp4"}},
        {"type": "input_file", "file_data": "data:application/pdf;base64,aGVsbG8="},
    ],
)
def test_unsupported_reasoning_modalities_fail_explicitly(part: dict[str, Any]) -> None:
    evidence = ReasoningEvidence(sections={"input": [part]})
    with pytest.raises(ValueError, match="(?i)(audio|video|file).*text_only"):
        build_reasoning_messages([], evidence=evidence)
    assert build_reasoning_messages([], evidence=evidence, mode="text_only")


def test_text_edits_address_each_part_without_reconstructing_media_or_settings() -> (
    None
):
    prompt = vision_prompt()
    original = deepcopy(prompt.get_messages())
    segments = describe_text_segments(prompt, allowed_roles={"user"})
    assert {segment["segment_id"] for segment in segments} == {
        "message:1:part:0",
        "message:1:part:2",
    }
    edited = apply_text_edits(
        prompt,
        [
            TextEdit(
                segment_id="message:1:part:0", text="Inspect {question} carefully."
            ),
            TextEdit(segment_id="message:1:part:2", text="Return just the code."),
        ],
        allowed_roles={"user"},
    )
    assert edited.get_messages()[1]["content"] == [
        {"type": "text", "text": "Inspect {question} carefully."},
        original[1]["content"][1],
        {"type": "text", "text": "Return just the code."},
    ]
    assert edited.model == prompt.model and edited.model_kwargs == {}
    assert prompt.get_messages() == original
    edited.get_messages()[1]["content"][1]["image_url"]["url"] = "changed"
    assert prompt.get_messages() == original


@pytest.mark.parametrize(
    "edit,roles",
    [
        (TextEdit(segment_id="message:1:part:1", text="replace image"), {"user"}),
        (TextEdit(segment_id="message:0", text="change system"), {"user"}),
        (TextEdit(segment_id="message:1:part:0", text="Dropped input"), {"user"}),
        (TextEdit(segment_id="message:99", text="unknown"), {"system"}),
    ],
)
def test_invalid_edits_fail_atomically(edit: TextEdit, roles: set[str]) -> None:
    prompt = vision_prompt()
    original = deepcopy(prompt.get_messages())
    with pytest.raises(ValueError):
        apply_text_edits(prompt, [edit], allowed_roles=roles)
    assert prompt.get_messages() == original


@pytest.mark.parametrize("asynchronous", [False, True])
def test_structured_output_retry_keeps_native_image_blocks(
    asynchronous: bool,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import asyncio
    from pydantic import BaseModel
    from opik_optimizer.core import llm_calls

    class Answer(BaseModel):
        answer: str

    calls = []

    def invoke(_completion: Any, _params: Any, **kwargs: Any) -> Any:
        calls.append(deepcopy(kwargs["messages"]))
        return object()

    async def invoke_async(*args: Any, **kwargs: Any) -> Any:
        return invoke(*args, **kwargs)

    def parse(*args: Any, **kwargs: Any) -> Answer:
        if len(calls) == 1:
            raise llm_calls.StructuredOutputParsingError(
                "invalid", ValueError("invalid JSON")
            )
        return Answer(answer="ok")

    monkeypatch.setattr(llm_calls, "_invoke_traced", invoke)
    monkeypatch.setattr(llm_calls, "_invoke_traced_async", invoke_async)
    monkeypatch.setattr(llm_calls, "_parse_response", parse)
    evidence = ReasoningEvidence(
        prompts={"vision": vision_prompt()},
        cases=[EvidenceCase(case_id="one", inputs={"question": "q", "image": IMAGE})],
    )
    kwargs: dict[str, Any] = dict(
        messages=[{"role": "user", "content": "Improve"}],
        evidence=evidence,
        model="openai/gpt-4o-mini",
        response_model=Answer,
    )
    if asynchronous:
        response = asyncio.run(llm_calls.call_model_async(**kwargs))
    else:
        response = llm_calls.call_model(**kwargs)
    assert response.answer == "ok"
    assert len(calls) == 2
    images = [
        [
            part
            for message in messages
            if isinstance(message.get("content"), list)
            for part in message["content"]
            if part["type"] == "image_url"
        ]
        for messages in calls
    ]
    assert (
        images[0]
        == images[1]
        == [{"type": "image_url", "image_url": {"url": IMAGE, "detail": "low"}}]
    )
    assert any("JSON" in str(message["content"]) for message in calls[1])


def test_gepa_targets_individual_text_parts_without_collapsing_content() -> None:
    from opik_optimizer.algorithms.gepa_optimizer.ops import candidate_ops

    prompt = vision_prompt()
    seed = candidate_ops.build_seed_candidate(
        optimizable_prompts={"vision": prompt}, allowed_roles={"user"}
    )
    assert candidate_ops.count_disallowed_candidate_components(seed, {"user"}) == 0
    assert seed == {
        "vision_user_1_part_0": "Question: {question}",
        "vision_user_1_part_2": "Give the code.",
    }
    updated, rejected = candidate_ops.rebuild_prompts_from_candidate(
        base_prompts={"vision": prompt},
        candidate={"vision_user_1_part_2": "Return only the code."},
        allowed_roles={"user"},
    )
    assert not rejected
    original = prompt.get_messages()[1]["content"]
    assert updated["vision"].get_messages()[1]["content"] == [
        original[0],
        original[1],
        {"type": "text", "text": "Return only the code."},
    ]


def test_recorded_agent_images_are_attached_as_observations() -> None:
    prompt = ChatPrompt(system="Use your camera.", user="{question}")
    evidence = ReasoningEvidence(
        prompts={"agent": prompt},
        cases=[
            EvidenceCase(
                case_id="camera",
                inputs={"question": "What is here?"},
                observations={
                    "agent_messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "image_url", "image_url": {"url": IMAGE}}
                            ],
                        }
                    ]
                },
            )
        ],
    )
    messages = build_reasoning_messages([], evidence=evidence)
    case = messages[-1]["content"]
    assert case[-1] == {"type": "image_url", "image_url": {"url": IMAGE}}
    assert "camera" in case[0]["text"]


def test_reasoning_case_budget_is_applied_at_shared_client_boundary(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from types import SimpleNamespace
    from opik_optimizer.core import llm_calls

    evidence = ReasoningEvidence(
        prompts={"vision": vision_prompt()},
        cases=[
            EvidenceCase(
                case_id=str(i),
                inputs={"question": str(i), "image": f"https://example.com/{i}.png"},
            )
            for i in range(4)
        ],
    )
    token = llm_calls._active_optimizer.set(
        SimpleNamespace(reasoning_input="multimodal", max_reasoning_examples=2)
    )
    try:
        messages = llm_calls._prepare_reasoning_request(
            [], evidence, "openai/gpt-4o-mini"
        )
    finally:
        llm_calls._active_optimizer.reset(token)
    images = [
        part
        for message in messages
        if isinstance(message["content"], list)
        for part in message["content"]
        if part["type"] == "image_url"
    ]
    assert [part["image_url"]["url"] for part in images] == [
        "https://example.com/0.png",
        "https://example.com/1.png",
    ]


def test_few_shot_demonstrations_keep_images_in_native_blocks() -> None:
    from opik_optimizer import FewShotBayesianOptimizer
    from opik_optimizer.algorithms.few_shot_bayesian_optimizer.prompts import (
        FEW_SHOT_EXAMPLE_PLACEHOLDER,
    )

    prompt = vision_prompt()
    messages = prompt.get_messages()
    messages[0]["content"] += "\n" + FEW_SHOT_EXAMPLE_PLACEHOLDER
    prompt.set_messages(messages)
    original = deepcopy(prompt.get_messages())
    result = FewShotBayesianOptimizer(verbose=0)._reconstruct_prompts_with_examples(
        {"vision": prompt},
        demo_examples=[{"question": "What color?", "image": IMAGE, "answer": "red"}],
        few_shot_prompt_template="Question: {question}\nImage: {image}\nAnswer: {answer}",
        allowed_roles={"system"},
    )["vision"]
    native = [
        part
        for message in result.get_messages()
        if isinstance(message["content"], list)
        for part in message["content"]
        if part["type"] == "image_url"
    ]
    assert any(part["image_url"]["url"] == IMAGE for part in native)
    assert result.get_messages()[-1] == original[-1]
    for message in result.get_messages():
        text = (
            message["content"]
            if isinstance(message["content"], str)
            else "\n".join(part.get("text", "") for part in message["content"])
        )
        assert IMAGE not in text
    assert prompt.get_messages() == original


def test_evolutionary_programmatic_crossover_keeps_each_native_block() -> None:
    import random
    from opik_optimizer.algorithms.evolutionary_optimizer.ops.crossover_ops import (
        _crossover_messages,
    )

    prompt = vision_prompt()
    first, second = _crossover_messages(
        prompt.get_messages(), prompt.get_messages(), random.Random(42)
    )
    for result in [first, second]:
        parts = result[1]["content"]
        assert [part["type"] for part in parts] == ["text", "image_url", "text"]
        assert parts[1] == prompt.get_messages()[1]["content"][1]


def test_case_text_is_bounded_while_native_image_payload_is_preserved() -> None:
    evidence = ReasoningEvidence(
        prompts={"vision": vision_prompt()},
        cases=[
            EvidenceCase(
                case_id="large",
                inputs={"question": "X" * 100_000, "image": IMAGE},
                output="Y" * 100_000,
                observations={"tool": "Z" * 100_000},
            )
        ],
    )
    case = build_reasoning_messages([], evidence=evidence)[-1]["content"]
    assert len(case[0]["text"]) < 16_000
    assert case[-1]["image_url"]["url"] == IMAGE


def test_image_url_templates_are_rendered_before_attaching_native_evidence() -> None:
    prompt = ChatPrompt(
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Describe"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "https://example.com/{asset}.png"},
                    },
                ],
            }
        ]
    )
    evidence = ReasoningEvidence(
        prompts={"vision": prompt},
        cases=[EvidenceCase(case_id="one", inputs={"asset": "red"})],
    )
    messages = build_reasoning_messages([], evidence=evidence)
    images = [
        part
        for message in messages
        if isinstance(message["content"], list)
        for part in message["content"]
        if part["type"] == "image_url"
    ]
    assert images == [
        {"type": "image_url", "image_url": {"url": "https://example.com/red.png"}}
    ]


@pytest.mark.parametrize(
    "part",
    [
        {"type": "image_url", "image_url": IMAGE},
        {"type": "input_image", "image_url": IMAGE},
        {
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": "image/png",
                "data": IMAGE.split(",", 1)[1],
            },
        },
    ],
)
def test_recorded_image_sources_become_native_blocks_not_encoded_text(
    part: dict[str, Any],
) -> None:
    original = deepcopy(part)
    messages = build_reasoning_messages(
        [], evidence=ReasoningEvidence(sections={"agent_image": part})
    )
    content = messages[-1]["content"]
    assert content[-1] == {"type": "image_url", "image_url": {"url": IMAGE}}
    assert IMAGE.split(",", 1)[1] not in content[0]["text"]
    assert part == original
    text_only = build_reasoning_messages(
        [], evidence=ReasoningEvidence(sections={"agent_image": part}), mode="text_only"
    )
    assert IMAGE.split(",", 1)[1] not in str(text_only)
