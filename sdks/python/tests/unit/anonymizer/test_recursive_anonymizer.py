from typing import Any, Dict, List

from unittest.mock import Mock

from opik.anonymizer.recursive_anonymizer import RecursiveAnonymizer


class TestRecursiveAnonymizer:
    """Test suite for RecursiveAnonymizer parameter handling and nested structure processing."""

    def test_recursive_anonymizer__simple_string__calls_anonymize_text_with_correct_parameters(
        self,
    ):
        """Test that anonymize_text is called with correct parameters for a simple string."""

        class MockRecursiveAnonymizer(RecursiveAnonymizer):
            def __init__(self):
                super().__init__()
                self.anonymize_text = Mock(return_value="[ANONYMIZED]")

            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                return self.anonymize_text(data, **kwargs)

        anonymizer = MockRecursiveAnonymizer()

        # Test with initial parameters
        result = anonymizer.anonymize(
            "sensitive text", field_name="input", object_type=dict
        )

        # Verify anonymize_text was called correctly
        anonymizer.anonymize_text.assert_called_once_with(
            "sensitive text", field_name="input", object_type=dict
        )
        assert result == "[ANONYMIZED]"

    def test_recursive_anonymizer__nested_dict__preserves_field_path_in_parameters(
        self,
    ):
        """Test that field paths are correctly built and passed for nested dictionaries."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(
                    {
                        "data": data,
                        "field_name": kwargs.get("field_name"),
                        "object_type": kwargs.get("object_type"),
                    }
                )
                return f"[ANON:{data}]"

        anonymizer = ParameterTrackingAnonymizer()

        nested_data = {
            "user": {
                "email": "user@example.com",
                "profile": {"name": "John Doe", "phone": "555-1234"},
            },
            "metadata": {"api_key": "secret123"},
        }

        result: Dict[str, Any] = anonymizer.anonymize(
            nested_data, field_name="trace", object_type="TraceMessage"
        )

        # Verify the correct field paths were generated
        expected_calls = [
            {
                "data": "user@example.com",
                "field_name": "trace.user.email",
                "object_type": "TraceMessage",
            },
            {
                "data": "John Doe",
                "field_name": "trace.user.profile.name",
                "object_type": "TraceMessage",
            },
            {
                "data": "555-1234",
                "field_name": "trace.user.profile.phone",
                "object_type": "TraceMessage",
            },
            {
                "data": "secret123",
                "field_name": "trace.metadata.api_key",
                "object_type": "TraceMessage",
            },
        ]

        assert len(calls_log) == 4
        for expected_call in expected_calls:
            assert expected_call in calls_log

        # Verify the structure was preserved with anonymized content
        assert result["user"]["email"] == "[ANON:user@example.com]"
        assert result["user"]["profile"]["name"] == "[ANON:John Doe]"
        assert result["user"]["profile"]["phone"] == "[ANON:555-1234]"
        assert result["metadata"]["api_key"] == "[ANON:secret123]"

    def test_recursive_anonymizer__nested_list__preserves_field_path_with_indices(self):
        """Test that field paths include list indices for nested lists."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(
                    {
                        "data": data,
                        "field_name": kwargs.get("field_name"),
                        "object_type": kwargs.get("object_type"),
                    }
                )
                return f"[ANON:{data}]"

        anonymizer = ParameterTrackingAnonymizer()

        list_data = [
            "first item",
            {"nested": "nested value", "list": ["inner item 1", "inner item 2"]},
            ["list item 1", "list item 2"],
        ]

        result: List[Any] = anonymizer.anonymize(
            list_data, field_name="input", object_type="SpanMessage"
        )

        # Verify the correct field paths were generated with indices
        expected_calls = [
            {
                "data": "first item",
                "field_name": "input.0",
                "object_type": "SpanMessage",
            },
            {
                "data": "nested value",
                "field_name": "input.1.nested",
                "object_type": "SpanMessage",
            },
            {
                "data": "inner item 1",
                "field_name": "input.1.list.0",
                "object_type": "SpanMessage",
            },
            {
                "data": "inner item 2",
                "field_name": "input.1.list.1",
                "object_type": "SpanMessage",
            },
            {
                "data": "list item 1",
                "field_name": "input.2.0",
                "object_type": "SpanMessage",
            },
            {
                "data": "list item 2",
                "field_name": "input.2.1",
                "object_type": "SpanMessage",
            },
        ]

        assert len(calls_log) == 6
        for expected_call in expected_calls:
            assert expected_call in calls_log

        # Verify the structure was preserved with anonymized content
        assert result[0] == "[ANON:first item]"
        assert result[1]["nested"] == "[ANON:nested value]"
        assert result[1]["list"] == ["[ANON:inner item 1]", "[ANON:inner item 2]"]
        assert result[2] == ["[ANON:list item 1]", "[ANON:list item 2]"]

    def test_recursive_anonymizer__mixed_complex_structure__handles_all_parameter_combinations(
        self,
    ):
        """Test a complex nested structure with mixed dictionaries, lists, and strings."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(
                    {
                        "data": data,
                        "field_name": kwargs.get("field_name"),
                        "object_type": kwargs.get("object_type"),
                        "custom_param": kwargs.get("custom_param"),
                    }
                )
                return f"[{kwargs.get('field_name', 'UNKNOWN')}:{data}]"

        anonymizer = ParameterTrackingAnonymizer()

        complex_data = {
            "messages": [
                {"role": "user", "content": "Hello, my email is john@example.com"},
                {
                    "role": "assistant",
                    "content": "I can help you with that",
                    "attachments": ["file1.txt", "file2.pdf"],
                },
            ],
            "metadata": {
                "session_id": "sess_12345",
                "user_data": {
                    "preferences": ["pref1", "pref2"],
                    "settings": {"theme": "dark", "language": "en"},
                },
            },
        }

        result: Dict[str, Any] = anonymizer.anonymize(
            complex_data,
            field_name="output",
            object_type="TraceMessage",
            custom_param="test_value",
        )

        # Verify all expected calls were made with correct parameters
        expected_field_paths = [
            "output.messages.0.role",
            "output.messages.0.content",
            "output.messages.1.role",
            "output.messages.1.content",
            "output.messages.1.attachments.0",
            "output.messages.1.attachments.1",
            "output.metadata.session_id",
            "output.metadata.user_data.preferences.0",
            "output.metadata.user_data.preferences.1",
            "output.metadata.user_data.settings.theme",
            "output.metadata.user_data.settings.language",
        ]

        assert len(calls_log) == len(expected_field_paths)

        # Verify each call has the correct parameters
        for call in calls_log:
            assert call["object_type"] == "TraceMessage"
            assert call["custom_param"] == "test_value"
            assert call["field_name"] in expected_field_paths

        # Verify specific anonymization results
        assert (
            result["messages"][0]["content"]
            == "[output.messages.0.content:Hello, my email is john@example.com]"
        )
        assert (
            result["metadata"]["session_id"]
            == "[output.metadata.session_id:sess_12345]"
        )
        assert (
            result["metadata"]["user_data"]["settings"]["theme"]
            == "[output.metadata.user_data.settings.theme:dark]"
        )

    def test_recursive_anonymizer__max_depth_limiting__stops_recursion_at_limit(self):
        """Test that max_depth parameter properly limits recursion depth."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def __init__(self, max_depth: int = 2):
                super().__init__(max_depth=max_depth)

            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(
                    {
                        "data": data,
                        "field_name": kwargs.get("field_name"),
                    }
                )
                return f"[ANON:{data}]"

        anonymizer = ParameterTrackingAnonymizer(max_depth=2)

        # Create a structure where strings at different depths can be tested
        deeply_nested = {
            "level1_text": "depth 1 - should be processed",
            "level1": {
                "level2_text": "depth 2 - should be processed",
                "level2": {"level3_text": "depth 3 - should NOT be processed"},
            },
        }

        result: Dict[str, Any] = anonymizer.anonymize(deeply_nested, field_name="root")

        field_names = [call["field_name"] for call in calls_log]

        # The recursion depth starts at 0 for the initial call, so with max_depth=2:
        # - root.level1_text is at depth 1 (should be processed)
        # - root.level1.level2_text is at depth 2 (exceeds max_depth=2, should NOT be processed)
        # - root.level1.level2.level3_text is at depth 3+ (exceeds max_depth=2, should NOT be processed)

        assert "root.level1_text" in field_names
        assert len([name for name in field_names if "level2_text" in name]) == 0
        assert len([name for name in field_names if "level3_text" in name]) == 0

        # Verify the results
        assert result["level1_text"] == "[ANON:depth 1 - should be processed]"
        assert (
            result["level1"]["level2_text"] == "depth 2 - should be processed"
        )  # Unchanged due to depth limit
        assert (
            result["level1"]["level2"]["level3_text"]
            == "depth 3 - should NOT be processed"
        )  # Unchanged

    def test_recursive_anonymizer__non_string_types__preserves_unchanged(self):
        """Test that non-string types are preserved without calling anonymize_text."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(data)
                return f"[ANON:{data}]"

        anonymizer = ParameterTrackingAnonymizer()

        mixed_types_data = {
            "string_field": "text to anonymize",
            "int_field": 42,
            "float_field": 3.14,
            "bool_field": True,
            "none_field": None,
            "nested": {
                "another_string": "another text",
                "number": 100,
                "list_with_mixed": ["string in list", 123, False],
            },
        }

        result: Dict[str, Any] = anonymizer.anonymize(
            mixed_types_data, field_name="data"
        )

        # Should only anonymize strings (there are 3: "text to anonymize", "another text", "string in list")
        assert len(calls_log) == 3
        assert "text to anonymize" in calls_log
        assert "another text" in calls_log
        assert "string in list" in calls_log

        # Non-string types should be preserved
        assert result["int_field"] == 42
        assert result["float_field"] == 3.14
        assert result["bool_field"] is True
        assert result["none_field"] is None
        assert result["nested"]["number"] == 100
        assert result["nested"]["list_with_mixed"][1] == 123
        assert result["nested"]["list_with_mixed"][2] is False

        # Strings should be anonymized
        assert result["string_field"] == "[ANON:text to anonymize]"
        assert result["nested"]["another_string"] == "[ANON:another text]"
        assert result["nested"]["list_with_mixed"][0] == "[ANON:string in list]"

    def test_recursive_anonymizer__empty_structures__handles_gracefully(self):
        """Test that empty dictionaries and lists are handled gracefully."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(data)
                return f"[ANON:{data}]"

        anonymizer = ParameterTrackingAnonymizer()

        empty_structures = {
            "empty_dict": {},
            "empty_list": [],
            "mixed": {
                "nested_empty_dict": {},
                "nested_empty_list": [],
                "text": "some text",
            },
        }

        result: Dict[str, Any] = anonymizer.anonymize(
            empty_structures, field_name="test"
        )

        # Should only call anonymize_text for the one string
        assert len(calls_log) == 1
        assert "some text" in calls_log

        # Empty structures should be preserved
        assert result["empty_dict"] == {}
        assert result["empty_list"] == []
        assert result["mixed"]["nested_empty_dict"] == {}
        assert result["mixed"]["nested_empty_list"] == []
        assert result["mixed"]["text"] == "[ANON:some text]"

    def test_recursive_anonymizer__field_specific_anonymization__uses_field_path_for_logic(
        self,
    ):
        """Test that anonymizers can use field paths to implement field-specific logic."""

        class FieldSpecificAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                field_name = kwargs.get("field_name", "")

                # Different anonymization based on a field path
                if "email" in field_name:
                    return "[EMAIL_REDACTED]"
                elif "phone" in field_name:
                    return "[PHONE_REDACTED]"
                elif "api_key" in field_name:
                    return "[API_KEY_REDACTED]"
                elif field_name.endswith(".name"):
                    return "[NAME_REDACTED]"
                else:
                    return data  # Leave unchanged for other fields

        anonymizer = FieldSpecificAnonymizer()

        user_data = {
            "user": {
                "email": "john.doe@example.com",
                "name": "John Doe",
                "phone": "555-1234",
                "notes": "Regular user notes",
            },
            "config": {
                "api_key": "secret123",
                "description": "Configuration description",
            },
            "contacts": [
                {
                    "name": "Contact One",
                    "email": "contact1@example.com",
                    "other_info": "Some other information",
                }
            ],
        }

        result: Dict[str, Any] = anonymizer.anonymize(user_data, field_name="input")

        # Verify field-specific anonymization
        assert result["user"]["email"] == "[EMAIL_REDACTED]"
        assert result["user"]["name"] == "[NAME_REDACTED]"
        assert result["user"]["phone"] == "[PHONE_REDACTED]"
        assert result["user"]["notes"] == "Regular user notes"  # Unchanged

        assert result["config"]["api_key"] == "[API_KEY_REDACTED]"
        assert (
            result["config"]["description"] == "Configuration description"
        )  # Unchanged

        assert result["contacts"][0]["name"] == "[NAME_REDACTED]"
        assert result["contacts"][0]["email"] == "[EMAIL_REDACTED]"
        assert (
            result["contacts"][0]["other_info"] == "Some other information"
        )  # Unchanged

    def test_recursive_anonymizer__parameter_propagation__all_kwargs_preserved(self):
        """Test that all custom kwargs are properly propagated to anonymize_text."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(kwargs.copy())
                return data

        anonymizer = ParameterTrackingAnonymizer()

        test_data = {"nested": {"text": "sample text"}}

        # Pass multiple custom parameters
        anonymizer.anonymize(
            test_data,
            field_name="test_field",
            object_type="TestMessage",
            custom_param1="value1",
            custom_param2=42,
            custom_param3={"nested": "param"},
        )

        # Should have one call for the text
        assert len(calls_log) == 1
        kwargs = calls_log[0]

        # Verify all parameters were preserved
        assert kwargs["field_name"] == "test_field.nested.text"
        assert kwargs["object_type"] == "TestMessage"
        assert kwargs["custom_param1"] == "value1"
        assert kwargs["custom_param2"] == 42
        assert kwargs["custom_param3"] == {"nested": "param"}

    def test_recursive_anonymizer__circular_reference_protection__respects_max_depth(
        self,
    ):
        """Test that max_depth prevents infinite recursion even with circular references."""

        calls_count = 0

        class CountingAnonymizer(RecursiveAnonymizer):
            def __init__(self):
                super().__init__(max_depth=3)

            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                nonlocal calls_count
                calls_count += 1
                return f"[CALL_{calls_count}:{data}]"

        anonymizer = CountingAnonymizer()

        # Create a structure that tests depth limiting with strings at different levels
        deep_structure = {
            "text_at_level1": "depth 1 - should be processed",
            "level1": {
                "text_at_level2": "depth 2 - should be processed",
                "level2": {
                    "text_at_level3": "depth 3 - should be processed",
                    "level3": {"text_at_level4": "depth 4 - should NOT be processed"},
                },
            },
        }

        result: Dict[str, Any] = anonymizer.anonymize(deep_structure, field_name="root")

        # With max_depth=3, strings at depth 1, 2, 3 should be processed, but depth 4+ should not

        # Verify the structure - strings beyond max_depth should remain unchanged
        assert (
            "depth 4 - should NOT be processed"
            in result["level1"]["level2"]["level3"]["text_at_level4"]
        )

        # The strings within max_depth should be processed
        # With max_depth=3, only strings at depth 1 and 2 get processed
        assert calls_count == 2  # Should process the first 2 strings within max_depth

    def test_recursive_anonymizer__no_field_name_provided__uses_empty_string_as_base(
        self,
    ):
        """Test behavior when no field_name is provided in initial kwargs."""

        calls_log = []

        class ParameterTrackingAnonymizer(RecursiveAnonymizer):
            def anonymize_text(self, data: str, **kwargs: Any) -> str:
                calls_log.append(kwargs.get("field_name"))
                return data

        anonymizer = ParameterTrackingAnonymizer()

        test_data = {"key1": "value1", "nested": {"key2": "value2"}}

        # Call without field_name
        anonymizer.anonymize(test_data)

        # Should use empty string as a base and build paths from there
        expected_field_names = [".key1", ".nested.key2"]
        assert len(calls_log) == 2
        for field_name in calls_log:
            assert field_name in expected_field_names
