from opik.evaluation.metrics.llm_judges.structure_output_compliance import template
from opik.evaluation.metrics.llm_judges.structure_output_compliance.schema import (
    FewShotExampleStructuredOutputCompliance,
)


class TestStructuredOutputComplianceTemplate:
    """Test suite for StructuredOutputCompliance template functions."""

    def test_generate_query_basic(self):
        """Test basic query generation without schema or examples."""
        output = '{"name": "John", "age": 30}'

        query = template.generate_query(output=output)

        assert output in query
        assert "You are an expert in structured data validation" in query
        assert "EXPECTED STRUCTURE" in query
        assert "OUTPUT:" in query
        assert "Respond in the following JSON format:" in query
        assert "(No schema provided — assume valid JSON)" in query
        assert "EXAMPLES:" not in query

    def test_generate_query_with_schema(self):
        """Test query generation with schema."""
        output = '{"name": "John", "age": 30}'
        schema = "User(name: str, age: int)"

        query = template.generate_query(output=output, schema=schema)

        assert output in query
        assert schema in query
        assert "(No schema provided — assume valid JSON)" not in query

    def test_generate_query_with_few_shot_examples(self):
        """Test query generation with few-shot examples."""
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

        query = template.generate_query(
            output=output, few_shot_examples=few_shot_examples
        )

        assert output in query
        assert "EXAMPLES:" in query
        assert "Valid JSON" in query
        assert "Invalid JSON" in query
        assert "Alice" in query
        assert "Bob" in query
        assert "true" in query
        assert "false" in query
        assert "Valid JSON format" in query
        assert "Missing quotes around age key" in query
        assert "<example>" in query
        assert "</example>" in query

    def test_generate_query_with_schema_and_examples(self):
        """Test query generation with both schema and few-shot examples."""
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

        query = template.generate_query(
            output=output, schema=schema, few_shot_examples=few_shot_examples
        )

        assert output in query
        assert schema in query
        assert "EXAMPLES:" in query
        assert "Valid Example" in query

    def test_generate_query_empty_examples_list(self):
        """Test query generation with empty examples list."""
        output = '{"name": "John", "age": 30}'
        few_shot_examples = []

        query = template.generate_query(
            output=output, few_shot_examples=few_shot_examples
        )

        assert output in query
        assert "EXAMPLES:" not in query

    def test_generate_query_example_without_schema(self):
        """Test query generation with examples that don't have schema."""
        output = '{"name": "John", "age": 30}'
        few_shot_examples = [
            FewShotExampleStructuredOutputCompliance(
                title="Valid JSON",
                output='{"name": "Alice"}',
                score=True,
                reason="Valid JSON format",
            )
        ]

        query = template.generate_query(
            output=output, few_shot_examples=few_shot_examples
        )

        assert "Expected Schema: None" in query
        assert "Valid JSON" in query
        assert "true" in query

    def test_generate_query_template_structure(self):
        """Test that the generated query has the correct template structure."""
        output = '{"test": "data"}'

        query = template.generate_query(output=output)

        assert "You are an expert in structured data validation" in query
        assert "Guidelines:" in query
        assert "1. OUTPUT must be a valid JSON object" in query
        assert "2. If a schema is provided" in query
        assert "3. If no schema is provided" in query
        assert "4. Common formatting issues" in query
        assert "5. Partial compliance is considered non-compliant" in query
        assert "6. Respond only in the specified JSON format" in query
        assert (
            "7. Score should be true if output fully complies, false otherwise" in query
        )
        assert "EXPECTED STRUCTURE (optional):" in query
        assert "OUTPUT:" in query
        assert '"score": <true or false>' in query
        assert '"reason": ["list of reasons' in query
