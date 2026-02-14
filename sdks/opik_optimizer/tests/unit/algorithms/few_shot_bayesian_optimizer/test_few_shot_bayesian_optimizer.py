# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import (
    AlgorithmResult,
    ChatPrompt,
    FewShotBayesianOptimizer,
    OptimizationResult,
)
from opik_optimizer.api_objects import types as api_types
from opik_optimizer.agents.optimizable_agent import OptimizableAgent
from opik_optimizer.utils.multimodal import preserve_multimodal_message_structure
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)
from tests.unit.fixtures import (
    assert_baseline_early_stop,
    assert_invalid_prompt_raises,
    make_baseline_prompt,
    make_two_prompt_bundle,
)


class TestFewShotBayesianOptimizerInit:
    @pytest.mark.parametrize(
        "kwargs,expected",
        [
            ({"model": "gpt-4o"}, {"model": "gpt-4o", "seed": 42}),
            (
                {
                    "model": "gpt-4o-mini",
                    "verbose": 0,
                    "seed": 123,
                    "min_examples": 1,
                    "max_examples": 5,
                },
                {
                    "model": "gpt-4o-mini",
                    "verbose": 0,
                    "seed": 123,
                    "min_examples": 1,
                    "max_examples": 5,
                },
            ),
        ],
    )
    def test_initialization(
        self, kwargs: dict[str, Any], expected: dict[str, Any]
    ) -> None:
        """Test optimizer initialization with defaults and custom params."""
        optimizer = FewShotBayesianOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value


class TestFewShotBayesianOptimizerOptimizePrompt:
    def test_single_prompt_returns_chat_prompt(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = FewShotBayesianOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=42,
            min_examples=1,
            max_examples=2,
        )
        prompt = ChatPrompt(system="Test", user="{question}")
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        def mock_create_fewshot_template(**kwargs):
            prompts = kwargs.get("prompts", {})
            return {
                name: p.copy() for name, p in prompts.items()
            }, "Examples:\n{examples}"

        monkeypatch.setattr(
            optimizer, "_create_few_shot_prompt_template", mock_create_fewshot_template
        )

        def mock_run_optimization(context):
            prompts = context.prompts
            original_prompts = context.initial_prompts
            best_prompt = prompts
            initial_prompt = original_prompts
            return AlgorithmResult(
                best_prompts=best_prompt,
                best_score=0.85,
                history=optimizer.get_history_entries(),
                metadata={
                    "initial_prompt": initial_prompt,
                    "initial_score": 0.5,
                    "metric_name": "test_metric",
                },
            )

        monkeypatch.setattr(optimizer, "run_optimization", mock_run_optimization)

        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, ChatPrompt)
        assert isinstance(result.initial_prompt, ChatPrompt)
        assert result.score == 0.85
        assert result.initial_score == 0.5

    def test_sanitized_prompt_name_collision_raises(self) -> None:
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)

        with pytest.raises(
            ValueError, match="Prompt name collision after sanitization"
        ):
            optimizer._sanitize_prompt_field_names(["chat-prompt", "chat_prompt"])

    def test_create_few_shot_template_preserves_multimodal_structure(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        prompt = ChatPrompt(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "Original text"},
                        {"type": "image_url", "image_url": {"url": "{image}"}},
                    ],
                }
            ]
        )

        class _FakeResponse:
            template = "Template"

            main = [
                api_types.Message(
                    role="user",
                    content=[
                        {"type": "text", "text": "Updated text"},
                        {"type": "image_url", "image_url": {"url": "{image}"}},
                        {"type": "image_url", "image_url": {"url": "{other_image}"}},
                    ],
                )
            ]

        monkeypatch.setattr(
            "opik_optimizer.algorithms.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer._llm_calls.call_model",
            lambda **_kwargs: _FakeResponse(),
        )

        updated_prompts, _ = optimizer._create_few_shot_prompt_template(
            model="openai/gpt-4o-mini",
            prompts={"main": prompt},
            few_shot_examples=[{"input": "x", "output": "y"}],
        )

        updated_message = updated_prompts["main"].get_messages()[0]
        assert isinstance(updated_message["content"], list)
        assert [part["type"] for part in updated_message["content"]] == [
            "text",
            "image_url",
        ]
        assert updated_message["content"][0]["text"] == "Updated text"
        assert updated_message["content"][1]["image_url"]["url"] == "{image}"

    def test_preserve_multimodal_structure_helper_mixed_messages(self) -> None:
        original_messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Original"},
                    {"type": "image_url", "image_url": {"url": "{image}"}},
                ],
            },
            {"role": "assistant", "content": "Original assistant"},
        ]
        generated_messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Updated"},
                    {"type": "image_url", "image_url": {"url": "{image}"}},
                    {"type": "image_url", "image_url": {"url": "{other_image}"}},
                ],
            },
            {"role": "assistant", "content": "Updated assistant"},
        ]

        preserved = preserve_multimodal_message_structure(
            original_messages=original_messages,
            generated_messages=generated_messages,
        )

        assert isinstance(preserved[0]["content"], list)
        assert [part["type"] for part in preserved[0]["content"]] == [
            "text",
            "image_url",
        ]
        assert preserved[0]["content"][0]["text"] == "Updated"
        assert preserved[0]["content"][1]["image_url"]["url"] == "{image}"
        assert preserved[1]["content"] == "Updated assistant"

    def test_preserve_multimodal_structure_helper_skips_role_mismatch(self) -> None:
        original_messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "Original"},
                    {"type": "image_url", "image_url": {"url": "{image}"}},
                ],
            }
        ]
        generated_messages = [
            {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "Updated"},
                    {"type": "image_url", "image_url": {"url": "{image}"}},
                    {"type": "image_url", "image_url": {"url": "{other_image}"}},
                ],
            }
        ]

        preserved = preserve_multimodal_message_structure(
            original_messages=original_messages,
            generated_messages=generated_messages,
        )

        assert preserved == generated_messages

    def test_preserve_multimodal_structure_keeps_original_text_when_generated_empty(
        self,
    ) -> None:
        original_messages = [
            {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "Keep me"},
                    {"type": "image_url", "image_url": {"url": "{image}"}},
                ],
            }
        ]
        generated_messages = [
            {
                "role": "assistant",
                "content": [{"type": "image_url", "image_url": {"url": "{image}"}}],
            }
        ]

        preserved = preserve_multimodal_message_structure(
            original_messages=original_messages,
            generated_messages=generated_messages,
        )

        assert isinstance(preserved[0]["content"], list)
        assert preserved[0]["content"][0]["type"] == "text"
        assert preserved[0]["content"][0]["text"] == "Keep me"

    def test_preserve_structure_falls_back_for_empty_aligned_non_multimodal(
        self,
    ) -> None:
        original_messages = [{"role": "user", "content": "{user_query}"}]
        generated_messages = [{"role": "user", "content": "   "}]

        preserved = preserve_multimodal_message_structure(
            original_messages=original_messages,
            generated_messages=generated_messages,
        )

        assert preserved == original_messages

    def test_preserve_structure_drops_empty_unmatched_generated_message(self) -> None:
        original_messages = [{"role": "system", "content": "System message"}]
        generated_messages = [
            {"role": "system", "content": "System message"},
            {"role": "user", "content": ""},
        ]

        preserved = preserve_multimodal_message_structure(
            original_messages=original_messages,
            generated_messages=generated_messages,
        )

        assert len(preserved) == 1
        assert preserved[0]["role"] == "system"

    def test_dict_prompt_returns_dict(
        self,
        mock_optimization_context,
        monkeypatch,
    ) -> None:
        mock_optimization_context()

        optimizer = FewShotBayesianOptimizer(
            model="gpt-4o-mini",
            verbose=0,
            seed=42,
            min_examples=1,
            max_examples=2,
        )
        prompts = make_two_prompt_bundle()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

        def mock_create_fewshot_template(**kwargs):
            prompts_arg = kwargs.get("prompts", {})
            return {
                name: p.copy() for name, p in prompts_arg.items()
            }, "Examples:\n{examples}"

        monkeypatch.setattr(
            optimizer, "_create_few_shot_prompt_template", mock_create_fewshot_template
        )

        def mock_run_optimization(context):
            prompts_arg = context.prompts
            original_prompts = context.initial_prompts
            is_single = context.is_single_prompt_optimization
            best_prompt = (
                prompts_arg if not is_single else list(prompts_arg.values())[0]
            )
            initial_prompt = (
                original_prompts
                if not is_single
                else list(original_prompts.values())[0]
            )
            return AlgorithmResult(
                best_prompts=best_prompt,
                best_score=0.85,
                history=optimizer.get_history_entries(),
                metadata={
                    "initial_prompt": initial_prompt,
                    "initial_score": 0.5,
                    "metric_name": "test_metric",
                },
            )

        monkeypatch.setattr(optimizer, "run_optimization", mock_run_optimization)

        result = optimizer.optimize_prompt(
            prompt=prompts,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=2,
            n_samples=2,
        )

        assert isinstance(result, OptimizationResult)
        assert isinstance(result.prompt, dict)
        assert isinstance(result.initial_prompt, dict)
        assert result.score == 0.85
        assert result.initial_score == 0.5

    def test_custom_task_respects_allow_tool_use(self) -> None:
        class AgentSpy(OptimizableAgent):
            def __init__(self) -> None:
                self.last_allow_tool_use: bool | None = None
                self.project_name = "test"

            def init_llm(self) -> None:
                return None

            def invoke_agent(
                self,
                prompts: dict[str, ChatPrompt],
                dataset_item: dict[str, Any],
                allow_tool_use: bool = False,
                seed: int | None = None,
            ) -> str:
                _ = prompts, dataset_item, seed
                self.last_allow_tool_use = allow_tool_use
                return "ok"

        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        agent = AgentSpy()
        prompts = {"main": ChatPrompt(system="system", user="{question}")}
        task = optimizer._build_task_from_messages(
            agent=agent,
            prompts=prompts,
            few_shot_examples="Q: a\nA: b",
            allow_tool_use=False,
        )

        task({"question": "Q1"})
        assert agent.last_allow_tool_use is False

    def test_invalid_prompt_raises_error(
        self,
        mock_optimization_context,
    ) -> None:
        mock_optimization_context()
        optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )

        assert_invalid_prompt_raises(
            optimizer,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )


class TestFewShotBayesianOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = FewShotBayesianOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
        monkeypatch.setattr(
            optimizer,
            "run_optimization",
            lambda context: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95)

    def test_early_stop_reports_at_least_one_trial(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify FewShotBayesianOptimizer early stop reports at least 1 trial."""
        mock_opik_client()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = FewShotBayesianOptimizer(model="gpt-4o", perfect_score=0.95)

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )

        assert_baseline_early_stop(
            result,
            perfect_score=0.95,
            trials_completed=1,
            history_len=1,
        )
