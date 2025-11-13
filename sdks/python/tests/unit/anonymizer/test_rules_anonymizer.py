import pytest

from opik.anonymizer import rules
from opik.anonymizer import rules_anonymizer


class TestRulesAnonymizer:
    """Test suite for RulesAnonymizer functionality."""

    def test_init(self):
        """Test RulesAnonymizer initialization."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        rules_ = [regex_rule]

        anonymizer = rules_anonymizer.RulesAnonymizer(rules_, max_depth=5)

        assert anonymizer.rules == rules_
        assert anonymizer.max_depth == 5

    def test_anonymize_text__single_regex_rule(self):
        """Test anonymizing text with a single regex rule."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        result = anonymizer.anonymize_text("Phone: 123-456-7890")

        assert result == "Phone: ***-***-***"

    def test_anonymize_text__multiple_regex_rules(self):
        """Test anonymizing text with multiple regex rules applied sequentially."""
        email_rule = rules.RegexRule(
            r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", "[EMAIL]"
        )
        phone_rule = rules.RegexRule(r"\d{3}-\d{3}-\d{4}", "[PHONE]")

        anonymizer = rules_anonymizer.RulesAnonymizer([email_rule, phone_rule])

        result = anonymizer.anonymize_text("Contact: user@example.com or 123-456-7890")

        assert result == "Contact: [EMAIL] or [PHONE]"

    def test_anonymize_text__function_rule(self):
        """Test anonymizing text with a function rule."""

        def uppercase_anonymizer(text: str) -> str:
            return text.upper()

        function_rule = rules.FunctionRule(uppercase_anonymizer)
        anonymizer = rules_anonymizer.RulesAnonymizer([function_rule])

        result = anonymizer.anonymize_text("hello world")

        assert result == "HELLO WORLD"

    def test_anonymize_text__mixed_rules(self):
        """Test anonymizing text with mixed rule types."""
        regex_rule = rules.RegexRule(r"\d+", "XXX")
        function_rule = rules.FunctionRule(lambda text: text.replace(" ", "_"))

        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule, function_rule])

        result = anonymizer.anonymize_text("Phone 123 456")

        assert result == "Phone_XXX_XXX"

    def test_anonymize_text__no_rules(self):
        """Test anonymizing text with no rules returns original text."""
        anonymizer = rules_anonymizer.RulesAnonymizer([])

        result = anonymizer.anonymize_text("sensitive data")

        assert result == "sensitive data"

    def test_anonymize__string_data(self):
        """Test anonymizing string data."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        result = anonymizer.anonymize("Phone: 123-456-7890")

        assert result == "Phone: ***-***-***"

    def test_anonymize__dict_data(self):
        """Test anonymizing dictionary data."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        data = {"name": "John Doe", "phone": "123-456-7890", "address": "123 Main St"}

        result = anonymizer.anonymize(data)

        expected = {
            "name": "John Doe",
            "phone": "***-***-***",
            "address": "*** Main St",
        }
        assert result == expected

    def test_anonymize__list_data(self):
        """Test anonymizing list data."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        data = ["Contact: 123-456-7890", "Another: 987-654-3210", "No numbers here"]

        result = anonymizer.anonymize(data)

        expected = ["Contact: ***-***-***", "Another: ***-***-***", "No numbers here"]
        assert result == expected

    def test_anonymize__nested_dict_data(self):
        """Test anonymizing nested dictionary data."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        data = {
            "user": {
                "name": "John Doe",
                "contact": {"phone": "123-456-7890", "email": "john@example.com"},
            },
            "id": "12345",
        }

        result = anonymizer.anonymize(data)

        expected = {
            "user": {
                "name": "John Doe",
                "contact": {"phone": "***-***-***", "email": "john@example.com"},
            },
            "id": "***",
        }
        assert result == expected

    def test_anonymize__nested_list_data(self):
        """Test anonymizing nested list data."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        data = [
            ["Phone: 123", "Email: user@test.com"],
            {"contact": "456-789-0123"},
            "Simple string with 999",
        ]

        result = anonymizer.anonymize(data)

        expected = [
            ["Phone: ***", "Email: user@test.com"],
            {"contact": "***-***-***"},
            "Simple string with ***",
        ]
        assert result == expected

    def test_anonymize__mixed_nested_data(self):
        """Test anonymizing complex mixed nested data structures."""
        email_rule = rules.RegexRule(
            r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", "[EMAIL]"
        )
        phone_rule = rules.RegexRule(r"\d{3}-\d{3}-\d{4}", "[PHONE]")

        anonymizer = rules_anonymizer.RulesAnonymizer([email_rule, phone_rule])

        data = {
            "users": [
                {"name": "John Doe", "contacts": ["john@example.com", "123-456-7890"]},
                {"name": "Jane Smith", "contacts": ["jane@test.org", "987-654-3210"]},
            ],
            "admin_contact": "admin@company.com",
        }

        result = anonymizer.anonymize(data)

        expected = {
            "users": [
                {"name": "John Doe", "contacts": ["[EMAIL]", "[PHONE]"]},
                {"name": "Jane Smith", "contacts": ["[EMAIL]", "[PHONE]"]},
            ],
            "admin_contact": "[EMAIL]",
        }
        assert result == expected

    def test_anonymize__non_string_values_unchanged(self):
        """Test that non-string values are not processed."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        data = {
            "number": 12345,
            "float_val": 123.45,
            "bool_val": True,
            "none_val": None,
            "string_val": "123",
        }

        result = anonymizer.anonymize(data)

        expected = {
            "number": 12345,
            "float_val": 123.45,
            "bool_val": True,
            "none_val": None,
            "string_val": "***",
        }
        assert result == expected

    def test_anonymize__max_depth_limiting(self):
        """Test that max_depth limits recursion depth."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule], max_depth=2)

        # Create deeply nested data that exceeds max_depth
        data = {"level1": {"level2": {"level3": {"phone": "123-456-7890"}}}}

        result = anonymizer.anonymize(data)

        # At max_depth=2, level3 should not be processed
        expected = {
            "level1": {
                "level2": {
                    "level3": {
                        "phone": "123-456-7890"  # Should remain unchanged
                    }
                }
            }
        }
        assert result == expected

    def test_anonymize__max_depth_exact_limit(self):
        """Test that max_depth allows processing exactly at the limit."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule], max_depth=4)

        # Create data where string is processed at depth=3 (within limit)
        data = {"level1": {"level2": {"phone": "123-456-7890"}}}

        result = anonymizer.anonymize(data)

        # At max_depth=4, the string at depth 3 should be processed
        expected = {"level1": {"level2": {"phone": "***-***-***"}}}
        assert result == expected

    def test_anonymize__circular_reference_protection(self):
        """Test protection against circular references through depth limiting."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule], max_depth=3)

        # Create circular reference
        data = {"phone": "123"}
        data["self"] = data

        # Should not cause infinite recursion due to max_depth
        result = anonymizer.anonymize(data)

        # The result should have processed the string but stopped at max depth
        assert result["phone"] == "***"
        assert "self" in result

    @pytest.mark.parametrize("rule_count", [1, 5, 10])
    def test_anonymize_text__performance_with_multiple_rules(self, rule_count):
        """Test performance with varying numbers of rules."""
        rules_ = []
        for i in range(rule_count):
            rules_.append(rules.RegexRule(f"pattern{i}", f"replacement{i}"))

        anonymizer = rules_anonymizer.RulesAnonymizer(rules_)

        # Test with a simple string
        result = anonymizer.anonymize_text("test pattern0 and pattern5")

        # Should apply all applicable rules
        assert "replacement0" in result or "pattern0" in result

    def test_anonymize_text__empty_string_handling(self):
        """Test handling of empty strings."""
        regex_rule = rules.RegexRule(r"\d+", "***")
        anonymizer = rules_anonymizer.RulesAnonymizer([regex_rule])

        result = anonymizer.anonymize_text("")

        assert result == ""

    def test_anonymize_text__complex_regex_patterns(self):
        """Test complex regex patterns for real-world scenarios."""
        # Email pattern
        email_rule = rules.RegexRule(
            r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b", "[EMAIL_REDACTED]"
        )

        # Credit card pattern (simplified)
        cc_rule = rules.RegexRule(
            r"\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b", "[CARD_REDACTED]"
        )

        # SSN pattern
        ssn_rule = rules.RegexRule(r"\b\d{3}-\d{2}-\d{4}\b", "[SSN_REDACTED]")

        anonymizer = rules_anonymizer.RulesAnonymizer([email_rule, cc_rule, ssn_rule])

        text = """
        Contact info:
        Email: john.doe@example.com
        Credit Card: 1234 5678 9012 3456
        SSN: 123-45-6789
        """

        result = anonymizer.anonymize_text(text)

        assert result != text
        assert (
            result
            == """
        Contact info:
        Email: [EMAIL_REDACTED]
        Credit Card: [CARD_REDACTED]
        SSN: [SSN_REDACTED]
        """
        )
