from opik.evaluation.metrics.llm_judges.structure_output_compliance import template
from opik.evaluation.metrics.llm_judges.structure_output_compliance.schema import (
    FewShotExampleStructuredOutputCompliance,
)


def _system_user(messages):
    assert messages[0]["role"] == "system"
    assert messages[1]["role"] == "user"
    return messages[0]["content"], messages[1]["content"]


class TestStructuredOutputComplianceTemplate:
    """Test suite for StructuredOutputCompliance template functions."""

    def test_build_messages__no_schema_no_examples__placeholder_note_in_user_content(
        self,
    ):
        """Without schema/examples, the user prompt embeds the no-schema placeholder."""
        output = '{"name": "John", "age": 30}'

        messages = template.build_messages(output=output)
        system_content, user_content = _system_user(messages)

        assert output in user_content
        assert "You are an expert in structured data validation" in system_content
        assert "<schema>" in user_content and "</schema>" in user_content
        assert "<output>" in user_content and "</output>" in user_content
        assert "Respond in the following JSON format:" in system_content
        assert "(No schema provided — assume valid JSON.)" in user_content
        assert "Examples:" not in system_content

    def test_build_messages__schema_provided__schema_appears_in_user_content(self):
        output = '{"name": "John", "age": 30}'
        schema = "User(name: str, age: int)"

        messages = template.build_messages(output=output, schema=schema)
        system_content, user_content = _system_user(messages)

        assert output in user_content
        assert schema in user_content
        assert "(No schema provided — assume valid JSON.)" not in user_content

    def test_build_messages__few_shot_examples_provided__examples_appear_in_system_content(
        self,
    ):
        output = '{"name": "John", "age": 30}'
        few_shot_examples = [
            FewShotExampleStructuredOutputCompliance(
                title="Valid JSON",
                output='{"name": "Alice", "age": 25}',
                output_schema="User(name: str, age: int)",
                score=True,
                reason="Valid JSON format",
            ),
            FewShotExampleStructuredOutputCompliance(
                title="Invalid JSON",
                output='{"name": "Bob", age: 30}',
                output_schema="User(name: str, age: int)",
                score=False,
                reason="Missing quotes around age key",
            ),
        ]

        messages = template.build_messages(
            output=output, few_shot_examples=few_shot_examples
        )
        system_content, user_content = _system_user(messages)

        assert output in user_content
        assert "Examples:" in system_content
        assert "Valid JSON" in system_content
        assert "Invalid JSON" in system_content
        assert "Alice" in system_content
        assert "Bob" in system_content
        assert "true" in system_content
        assert "false" in system_content
        assert "Valid JSON format" in system_content
        assert "Missing quotes around age key" in system_content
        assert "<example>" in system_content
        assert "</example>" in system_content
        assert "<title>Valid JSON</title>" in system_content
        assert "<verdict>" in system_content and "</verdict>" in system_content

    def test_build_messages__schema_and_examples_provided__both_appear_in_messages(
        self,
    ):
        output = '{"name": "John", "age": 30}'
        schema = "User(name: str, age: int)"
        few_shot_examples = [
            FewShotExampleStructuredOutputCompliance(
                title="Valid Example",
                output='{"name": "Alice", "age": 25}',
                output_schema="User(name: str, age: int)",
                score=True,
                reason="Valid format",
            )
        ]

        messages = template.build_messages(
            output=output, schema=schema, few_shot_examples=few_shot_examples
        )
        system_content, user_content = _system_user(messages)

        assert output in user_content
        assert schema in user_content
        assert "Examples:" in system_content
        assert "Valid Example" in system_content

    def test_build_messages__empty_examples_list__no_examples_section_in_system_content(
        self,
    ):
        output = '{"name": "John", "age": 30}'

        messages = template.build_messages(output=output, few_shot_examples=[])
        system_content, user_content = _system_user(messages)

        assert output in user_content
        assert "Examples:" not in system_content

    def test_build_messages__example_without_schema__schema_rendered_as_none(self):
        output = '{"name": "John", "age": 30}'
        few_shot_examples = [
            FewShotExampleStructuredOutputCompliance(
                title="Valid JSON",
                output='{"name": "Alice"}',
                score=True,
                reason="Valid JSON format",
            )
        ]

        messages = template.build_messages(
            output=output, few_shot_examples=few_shot_examples
        )
        system_content, _ = _system_user(messages)

        assert "<schema>None</schema>" in system_content
        assert "Valid JSON" in system_content
        assert "true" in system_content

    def test_build_messages__happyflow(self):
        output = '{"test": "data"}'

        messages = template.build_messages(output=output)
        system_content, user_content = _system_user(messages)

        assert "You are an expert in structured data validation" in system_content
        assert "Guidelines:" in system_content
        assert "1. The OUTPUT must be a valid JSON object" in system_content
        assert "2. If a schema is provided" in system_content
        assert "3. If no schema is provided" in system_content
        assert "4. Common formatting issues" in system_content
        assert "5. Partial compliance is considered non-compliant" in system_content
        assert "6. Respond only in the specified JSON format" in system_content
        assert (
            "7. Score should be true if the OUTPUT fully complies, false otherwise"
            in system_content
        )
        assert "<schema>" in user_content and "</schema>" in user_content
        assert "<output>" in user_content and "</output>" in user_content
        assert '"score": <true or false>' in system_content
        assert '"reason": ["list of reasons' in system_content
