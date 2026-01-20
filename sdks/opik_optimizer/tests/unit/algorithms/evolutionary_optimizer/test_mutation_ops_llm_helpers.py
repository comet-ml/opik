import pytest
from typing import Any, cast

from opik_optimizer.algorithms.evolutionary_optimizer.ops import mutation_ops
from opik_optimizer import ChatPrompt
from opik_optimizer.api_objects.types import Content
from tests.unit.test_helpers import make_fake_llm_call
from tests.unit.algorithms.evolutionary_optimizer._mutation_test_helpers import (
    force_random,
    patch_get_synonym,
    patch_modify_phrase,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestGetSynonym:
    """Tests for _get_synonym function."""

    @pytest.mark.parametrize(
        "llm_response,raises,expected",
        [
            ("quick", None, "quick"),
            ("  quick  \n", None, "quick"),
            (None, Exception("API error"), "fast"),
        ],
    )
    def test_get_synonym_outputs_expected_value(
        self,
        monkeypatch: pytest.MonkeyPatch,
        evo_prompts: Any,
        llm_response: str | None,
        raises: Exception | None,
        expected: str,
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(llm_response, raises=raises),
        )

        result = mutation_ops._get_synonym(
            word="fast", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == expected


class TestModifyPhrase:
    """Tests for _modify_phrase function."""

    @pytest.mark.parametrize(
        "llm_response,raises,expected",
        [
            ("rapidly", None, "rapidly"),
            (None, Exception("API error"), "quickly"),
        ],
    )
    def test_modify_phrase_outputs_expected_value(
        self,
        monkeypatch: pytest.MonkeyPatch,
        evo_prompts: Any,
        llm_response: str | None,
        raises: Exception | None,
        expected: str,
    ) -> None:
        monkeypatch.setattr(
            "opik_optimizer.core.llm_calls.call_model",
            make_fake_llm_call(llm_response, raises=raises),
        )

        result = mutation_ops._modify_phrase(
            phrase="quickly", model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == expected


class TestWordLevelMutation:
    """Tests for _word_level_mutation function."""

    def test_returns_original_for_single_word(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        content = "Hello"
        result = mutation_ops._word_level_mutation(
            msg_content=content, model="gpt-4", model_parameters={}, prompts=evo_prompts
        )
        assert result == content

    def test_synonym_mutation_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1, randint_value=0)
        patch_get_synonym(monkeypatch, return_value="great")

        result = mutation_ops._word_level_mutation(
            msg_content="Hello world",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        assert "great" in result or "world" in result

    def test_word_swap_mutation_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.4, sample_value=[0, 2])

        result = mutation_ops._word_level_mutation(
            msg_content="Hello beautiful world",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        assert isinstance(result, str)

    def test_modify_phrase_mutation_path(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.8, randint_value=0)
        patch_modify_phrase(monkeypatch, return_value="Hi")

        result = mutation_ops._word_level_mutation(
            msg_content="Hello world",
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        assert isinstance(result, str)

    def test_handles_content_parts(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1, randint_value=0)
        patch_get_synonym(monkeypatch, return_value="Greetings")

        content_parts: Content = cast(
            Content,
            [
                {"type": "text", "text": "Hello world"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64,abc"}},
            ],
        )

        result = mutation_ops._word_level_mutation(
            msg_content=content_parts,
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )
        assert isinstance(result, list)


class TestWordLevelMutationPrompt:
    """Tests for _word_level_mutation_prompt function."""

    def test_mutates_all_messages(
        self, monkeypatch: pytest.MonkeyPatch, evo_prompts: Any
    ) -> None:
        force_random(monkeypatch, random_value=0.1, randint_value=0)
        patch_get_synonym(monkeypatch, return_value="modified")

        prompt = ChatPrompt(system="You are helpful.", user="Answer the question.")

        result = mutation_ops._word_level_mutation_prompt(
            prompt=prompt,
            model="gpt-4",
            model_parameters={},
            prompts=evo_prompts,
        )

        assert isinstance(result, ChatPrompt)
        assert len(result.get_messages()) == 2

