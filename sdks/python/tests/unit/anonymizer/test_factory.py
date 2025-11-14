import pytest

from opik.anonymizer import factory
from opik.anonymizer import rules_anonymizer


class TestCreateAnonymizer:
    """Test suite for the create_anonymizer factory function."""

    def test_create_anonymizer__single_dict_rule(self):
        """Test creating anonymizer with a single dictionary rule."""
        rules = {"regex": r"\d+", "replace": "***"}
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 1
        assert anonymizer.max_depth == 10

        result = anonymizer.anonymize("Phone: 123-456")
        assert result == "Phone: ***-***"

    def test_create_anonymizer__single_tuple_rule(self):
        """Test creating anonymizer with a single tuple rule."""
        rules = (r"\d+", "***")
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 1

        result = anonymizer.anonymize("Phone: 123-456")
        assert result == "Phone: ***-***"

    def test_create_anonymizer__single_function_rule(self):
        """Test creating anonymizer with a single function rule."""

        def uppercase_rule(text: str) -> str:
            return text.upper()

        rules = uppercase_rule
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 1

        result = anonymizer.anonymize("hello world")
        assert result == "HELLO WORLD"

    def test_create_anonymizer__list_of_dict_rules(self):
        """Test creating anonymizer with a list of dictionary rules."""
        rules = [
            {"regex": r"\d+", "replace": "[NUMBER]"},
            {
                "regex": r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}",
                "replace": "[EMAIL]",
            },
        ]
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 2

        result = anonymizer.anonymize("Contact: test@example.com or 123")
        assert result == "Contact: [EMAIL] or [NUMBER]"

    def test_create_anonymizer__list_of_tuple_rules(self):
        """Test creating anonymizer with a list of tuple rules."""
        rules = [
            (r"\d+", "[NUMBER]"),
            (r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", "[EMAIL]"),
        ]
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 2

        result = anonymizer.anonymize("Contact: test@example.com or 123")
        assert result == "Contact: [EMAIL] or [NUMBER]"

    def test_create_anonymizer__list_of_function_rules(self):
        """Test creating anonymizer with a list of function rules."""

        def uppercase_rule(text: str) -> str:
            return text.upper()

        def replace_spaces(text: str) -> str:
            return text.replace(" ", "_")

        rules = [uppercase_rule, replace_spaces]
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 2

        result = anonymizer.anonymize("hello world")
        assert result == "HELLO_WORLD"

    def test_create_anonymizer__mixed_rules_list(self):
        """Test creating anonymizer with mixed rule types in a list."""

        def prefix_rule(text: str) -> str:
            return f"[PROCESSED] {text}"

        rules = [{"regex": r"\d+", "replace": "***"}, (r"test", "TEST"), prefix_rule]
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 3

        result = anonymizer.anonymize("test 123")
        assert result == "[PROCESSED] TEST ***"

    def test_create_anonymizer__custom_max_depth(self):
        """Test creating anonymizer with custom max_depth."""
        rules = {"regex": r"\d+", "replace": "***"}
        anonymizer = factory.create_anonymizer(rules, max_depth=5)

        assert anonymizer.max_depth == 5

    def test_create_anonymizer__complex_nested_data(self):
        """Test created anonymizer with complex nested data."""
        rules = [
            {"regex": r"\d{3}-\d{3}-\d{4}", "replace": "[PHONE]"},
            {
                "regex": r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}",
                "replace": "[EMAIL]",
            },
        ]
        anonymizer = factory.create_anonymizer(rules)

        data = {
            "users": [
                {"name": "John", "contact": "john@test.com", "phone": "123-456-7890"},
                {"name": "Jane", "contact": "jane@test.org", "phone": "987-654-3210"},
            ]
        }

        result = anonymizer.anonymize(data)

        expected = {
            "users": [
                {"name": "John", "contact": "[EMAIL]", "phone": "[PHONE]"},
                {"name": "Jane", "contact": "[EMAIL]", "phone": "[PHONE]"},
            ]
        }
        assert result == expected

    def test_create_anonymizer__invalid_dict_rule_missing_regex__error_raised(self):
        """Test error handling for a dictionary rule missing the 'regex' key."""
        rules = {"replace": "***"}
        with pytest.raises(
            ValueError, match="Dictionary rule must have 'regex' and 'replace' keys"
        ):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__invalid_dict_rule_missing_replace__error_raised(self):
        """Test error handling for a dictionary rule missing the 'replace' key."""
        rules = {"regex": r"\d+"}
        with pytest.raises(
            ValueError, match="Dictionary rule must have 'regex' and 'replace' keys"
        ):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__invalid_tuple_rule_wrong_length__error_raised(self):
        """Test error handling for tuple rule with the wrong length."""
        rules = (r"\d+",)  # Only one element
        with pytest.raises(ValueError, match="Tuple rule must have exactly 2 elements"):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__invalid_tuple_rule_too_many_elements__error_raised(
        self,
    ):
        """Test error handling for tuple rule with too many elements."""
        rules = (r"\d+", "***", "extra")
        with pytest.raises(ValueError, match="Tuple rule must have exactly 2 elements"):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__invalid_dict_rule_in_list__error_raised(self):
        """Test error handling for invalid dictionary rule in a list."""
        rules = [
            {"regex": r"\d+", "replace": "***"},
            {"regex": r"test"},  # Missing 'replace'
        ]
        with pytest.raises(
            ValueError, match="Dictionary rule must have 'regex' and 'replace' keys"
        ):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__invalid_tuple_rule_in_list__error_raised(self):
        """Test error handling for invalid tuple rule in a list."""
        rules = [
            (r"\d+", "***"),
            (r"test",),  # Wrong length
        ]
        with pytest.raises(ValueError, match="Tuple rule must have exactly 2 elements"):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__unsupported_rule_type_in_list__error_raised(self):
        """Test error handling for an unsupported rule type in the list."""
        rules = [
            {"regex": r"\d+", "replace": "***"},
            123,  # Invalid type
        ]
        with pytest.raises(ValueError, match="Unsupported rule type in list"):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__unsupported_rules_type__error_raised(self):
        """Test error handling for completely unsupported rules type."""
        rules = 123  # Invalid type
        with pytest.raises(ValueError, match="Unsupported rules type"):
            factory.create_anonymizer(rules)

    def test_create_anonymizer__empty_list(self):
        """Test creating anonymizer with an empty rules list."""
        rules = []
        anonymizer = factory.create_anonymizer(rules)

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 0

        # Should return original text with no rules
        result = anonymizer.anonymize("test data")
        assert result == "test data"

    def test_create_anonymizer__lambda_function(self):
        """Test creating anonymizer with lambda function rule."""
        anonymizer = factory.create_anonymizer(
            lambda text: text.replace("secret", "[REDACTED]")
        )

        assert isinstance(anonymizer, rules_anonymizer.RulesAnonymizer)
        assert len(anonymizer.rules) == 1

        result = anonymizer.anonymize("This is secret information")
        assert result == "This is [REDACTED] information"

    def test_create_anonymizer__real_world_pii_rules(self):
        """Test creating anonymizer with realistic PII anonymization rules."""
        rules = [
            # Phone numbers
            {"regex": r"\b\d{3}-\d{3}-\d{4}\b", "replace": "[PHONE]"},
            # Email addresses
            {
                "regex": r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b",
                "replace": "[EMAIL]",
            },
            # SSN
            {"regex": r"\b\d{3}-\d{2}-\d{4}\b", "replace": "[SSN]"},
            # Credit card (simplified)
            {"regex": r"\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b", "replace": "[CARD]"},
        ]
        anonymizer = factory.create_anonymizer(rules)

        text = """
        Personal Information:
        Name: John Doe
        Email: john.doe@company.com
        Phone: 555-123-4567
        SSN: 123-45-6789
        Credit Card: 1234 5678 9012 3456
        """

        result = anonymizer.anonymize(text)

        assert result != text
        assert (
            result
            == """
        Personal Information:
        Name: John Doe
        Email: [EMAIL]
        Phone: [PHONE]
        SSN: [SSN]
        Credit Card: [CARD]
        """
        )
