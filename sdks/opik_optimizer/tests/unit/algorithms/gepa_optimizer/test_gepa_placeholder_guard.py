# mypy: disable-error-code=no-untyped-def
"""Guard against candidates that drop the seed prompt's template variables.

Substitution in ChatPrompt.get_messages is a plain str.replace of "{key}", so a
candidate that rewrites a message without the token loses the dataset value with
no error at all. These tests pin the reject-and-revert behaviour that keeps such
a candidate from ever being evaluated as-is.
"""

from unittest.mock import MagicMock

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.algorithms.gepa_optimizer.adapter import OpikGEPAAdapter
from opik_optimizer.algorithms.gepa_optimizer.ops import candidate_ops
from tests.unit.fixtures.builders import make_mock_dataset, make_simple_metric


def _prompt(messages: list[dict[str, str]]) -> chat_prompt.ChatPrompt:
    return chat_prompt.ChatPrompt(name="p", messages=messages)


def _make_adapter(
    base_prompts: dict[str, chat_prompt.ChatPrompt],
    allowed_roles: list[str] | None = None,
) -> OpikGEPAAdapter:
    context = MagicMock()
    context.extra_params = (
        {"optimizable_roles": allowed_roles} if allowed_roles is not None else {}
    )
    return OpikGEPAAdapter(
        base_prompts=base_prompts,
        agent=MagicMock(),
        optimizer=MagicMock(),
        context=context,
        metric=make_simple_metric(),
        dataset=make_mock_dataset([{"id": "t1", "question": "q", "answer": "a"}]),
        experiment_config=None,
    )


class TestExtractPlaceholders:
    def test_extracts_identifier_tokens(self) -> None:
        assert candidate_ops.extract_placeholders(
            "Answer {question} for {user_id} and {a.b} and {user-name}"
        ) == {"question", "user_id", "a.b", "user-name"}

    def test_ignores_json_and_code_braces(self) -> None:
        """A prompt full of JSON must not read as a prompt full of variables.

        Otherwise the guard would revert every legitimate reformatting edit and
        stall the optimization entirely.
        """
        assert (
            candidate_ops.extract_placeholders(
                'Reply as {"answer": 1} or {} or { spaced } or {2bad}'
            )
            == set()
        )

    def test_reads_text_parts_of_multimodal_content(self) -> None:
        content = [
            {"type": "text", "text": "Describe {image_topic}"},
            {"type": "image_url", "image_url": {"url": "http://x/y.png"}},
        ]
        assert candidate_ops.extract_placeholders(content) == {"image_topic"}


class TestEnforcePlaceholderPreservation:
    def test_reverts_message_that_dropped_a_variable(self) -> None:
        original = [{"role": "user", "content": "Answer {question}"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original,
            new_messages=[{"role": "user", "content": "Answer the capital of France"}],
            prompt_name="p",
        )
        assert guarded == original
        assert reverted == ["p_user_0"]

    def test_keeps_edit_that_preserves_the_variable(self) -> None:
        original = [{"role": "user", "content": "Answer {question}"}]
        new = [{"role": "user", "content": "Think step by step, then answer {question}"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded == new
        assert reverted == []

    def test_variable_moved_between_messages_is_not_a_loss(self) -> None:
        """Substitution runs over every message, so a relocated variable is fine."""
        original = [
            {"role": "system", "content": "Answer {question}"},
            {"role": "user", "content": "Go"},
        ]
        new = [
            {"role": "system", "content": "Be concise."},
            {"role": "user", "content": "Go: {question}"},
        ]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded == new
        assert reverted == []

    def test_reverts_only_the_offending_message(self) -> None:
        original = [
            {"role": "system", "content": "Use {context}"},
            {"role": "user", "content": "Answer {question}"},
        ]
        new = [
            {"role": "system", "content": "Use {context} carefully"},  # good edit
            {"role": "user", "content": "Answer it"},  # dropped {question}
        ]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded[0]["content"] == "Use {context} carefully"
        assert guarded[1]["content"] == "Answer {question}"
        assert reverted == ["p_user_1"]

    def test_no_variables_in_seed_is_a_no_op(self) -> None:
        original = [{"role": "system", "content": "Be nice"}]
        new = [{"role": "system", "content": "Be very nice"}]
        guarded, reverted = candidate_ops.enforce_placeholder_preservation(
            original_messages=original, new_messages=new, prompt_name="p"
        )
        assert guarded == new
        assert reverted == []


class TestRebuildAppliesGuard:
    """The guard must hold on every rebuild path, not just one."""

    def test_shared_rebuild_rejects_stripped_variable(self) -> None:
        prompt = _prompt(
            [
                {"role": "system", "content": "You answer questions."},
                {"role": "user", "content": "Answer {question}"},
            ]
        )
        rebuilt = candidate_ops.rebuild_prompts_from_candidate(
            base_prompts={"p": prompt},
            candidate={
                "p_system_0": "You are an expert geographer.",
                "p_user_1": "What is the capital of France?",  # inlined the example
            },
        )
        messages = rebuilt["p"].get_messages()
        assert messages[0]["content"] == "You are an expert geographer."  # kept
        assert messages[1]["content"] == "Answer {question}"  # rejected

    def test_adapter_rebuild_rejects_and_records(self) -> None:
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        rebuilt = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "What is the capital of France?"}
        )

        assert rebuilt["p"].get_messages()[0]["content"] == "Answer {question}"
        assert adapter._last_placeholder_reverts == ["p_user_0"]

    def test_adapter_records_nothing_when_candidate_is_clean(self) -> None:
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        rebuilt = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "Answer concisely: {question}"}
        )

        assert rebuilt["p"].get_messages()[0]["content"] == "Answer concisely: {question}"
        assert adapter._last_placeholder_reverts == []

    def test_guarded_prompt_still_substitutes_the_dataset_value(self) -> None:
        """End-to-end point of the guard: the user's input reaches the model."""
        prompt = _prompt([{"role": "user", "content": "Answer {question}"}])
        adapter = _make_adapter({"p": prompt})

        rebuilt = adapter._rebuild_prompts_from_candidate(
            {"p_user_0": "Answer the capital of France"}
        )

        rendered = rebuilt["p"].get_messages({"question": "What is 2+2?"})
        assert rendered[0]["content"] == "Answer What is 2+2?"
