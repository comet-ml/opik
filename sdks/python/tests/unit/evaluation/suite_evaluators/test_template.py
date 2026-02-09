from opik.evaluation.suite_evaluators import template


class TestGenerateQuery:
    def test_generate_query__single_assertion__formats_correctly(self):
        assertions = [
            {"name": "factual", "description": "The response is factually correct"}
        ]

        query = template.generate_query(
            input="What is 2+2?",
            output="4",
            assertions=assertions,
        )

        assert "What is 2+2?" in query
        assert "4" in query
        assert "**factual**" in query
        assert "The response is factually correct" in query
        assert "JSON format" in query

    def test_generate_query__multiple_assertions__includes_all(self):
        assertions = [
            {"name": "accurate", "description": "Response is accurate"},
            {"name": "helpful", "description": "Response is helpful"},
            {"name": "concise", "description": "Response is concise"},
        ]

        query = template.generate_query(
            input="Test input",
            output="Test output",
            assertions=assertions,
        )

        assert "**accurate**" in query
        assert "**helpful**" in query
        assert "**concise**" in query
        assert "Response is accurate" in query
        assert "Response is helpful" in query
        assert "Response is concise" in query

    def test_generate_query__empty_assertions__generates_valid_query(self):
        query = template.generate_query(
            input="Input",
            output="Output",
            assertions=[],
        )

        assert "Input" in query
        assert "Output" in query
        assert "## Assertions" in query

    def test_generate_query__special_characters_in_content__preserved(self):
        assertions = [
            {"name": "test", "description": "Check for special chars: <>&\"'"}
        ]

        query = template.generate_query(
            input="Input with <html> tags",
            output="Output with \"quotes\" and 'apostrophes'",
            assertions=assertions,
        )

        assert "<html>" in query
        assert "quotes" in query
        assert "apostrophes" in query
        assert "<>&" in query

    def test_generate_query__dict_input__formats_as_json(self):
        assertions = [{"name": "test", "description": "Test assertion"}]

        query = template.generate_query(
            input={"user_query": "What is AI?", "context": {"source": "docs"}},
            output={"response": "AI is...", "confidence": 0.95},
            assertions=assertions,
        )

        assert '"user_query": "What is AI?"' in query
        assert '"response": "AI is..."' in query
        assert '"confidence": 0.95' in query

    def test_generate_query__list_input__formats_as_json(self):
        assertions = [{"name": "test", "description": "Test assertion"}]

        query = template.generate_query(
            input=[{"role": "user", "content": "Hello"}],
            output=[{"role": "assistant", "content": "Hi there!"}],
            assertions=assertions,
        )

        assert '"role": "user"' in query
        assert '"content": "Hello"' in query
        assert '"role": "assistant"' in query

    def test_generate_query__mixed_types__handles_correctly(self):
        assertions = [{"name": "test", "description": "Test assertion"}]

        query = template.generate_query(
            input="Simple string input",
            output={"complex": "output", "with": ["nested", "data"]},
            assertions=assertions,
        )

        assert "Simple string input" in query
        assert '"complex": "output"' in query
