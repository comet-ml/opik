from typing import Dict, Optional, Any

from opik.anonymizer import anonymizer, recursive_anonymizer

from opik.message_processing import encoder_helpers


class MockAnonymizer(anonymizer.Anonymizer):
    """Mock anonymizer for testing purposes."""

    def anonymize(self, data, **kwargs):
        """Mock anonymization that replaces strings with '[ANONYMIZED]'."""
        if isinstance(data, str):
            return "[ANONYMIZED]"
        return data


class TestEncodeAndAnonymize:
    """Test suite for anonymize_encoded_obj functionality."""

    def test_anonymize_encoded_obj__no_anonymizers__returns_encoded_only(self):
        """Test that with an empty anonymizers list, only encoding is performed."""
        obj = {"name": "John Doe", "email": "john@example.com"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj, anonymizers=[], fields_to_anonymize=set(), object_type="span"
        )

        expected = {"name": "John Doe", "email": "john@example.com"}
        assert result == expected

    def test_anonymize_encoded_obj__with_anonymizers_no_fields__no_error(self):
        """Test that providing anonymizers with empty fields works."""
        obj = {"name": "John Doe"}
        mock_anonymizer = MockAnonymizer()

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=set(),
            object_type="span",
        )

        expected = {"name": "John Doe"}
        assert result == expected

    def test_anonymize_encoded_obj__dict_with_matching_fields(self):
        """Test anonymization of a dictionary with matching field names."""
        obj = {
            "name": "John Doe",
            "email": "john@example.com",
            "phone": "123-456-7890",
            "age": 30,
        }
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email", "phone"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        expected = {
            "name": "John Doe",
            "email": "[ANONYMIZED]",
            "phone": "[ANONYMIZED]",
            "age": 30,
        }
        assert result == expected

    def test_anonymize_encoded_obj__dict_with_no_matching_fields(self):
        """Test that fields not in dict are ignored."""
        obj = {"name": "John Doe", "age": 30}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email", "phone"}  # These fields don't exist in obj

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        expected = {"name": "John Doe", "age": 30}
        assert result == expected

    def test_anonymize_encoded_obj__dict_partial_field_match(self):
        """Test anonymization when only some specified fields exist."""
        obj = {"name": "John Doe", "email": "john@example.com", "age": 30}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email", "phone", "ssn"}  # Only email exists

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        expected = {"name": "John Doe", "email": "[ANONYMIZED]", "age": 30}
        assert result == expected

    def test_anonymize_encoded_obj__non_dict_object__no_anonymization(self):
        """Test that non-dict objects are not anonymized."""
        obj = ["item1", "item2", "item3"]
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"item1"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="trace",
        )

        # Should return an encoded list without anonymization
        assert result == ["item1", "item2", "item3"]

    def test_anonymize_encoded_obj__string_object__no_anonymization(self):
        """Test that string objects are not anonymized."""
        obj = "This is a sensitive string"
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"field1"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="trace",
        )

        assert result == "This is a sensitive string"

    def test_anonymize_encoded_obj__complex_nested_object(self):
        """Test encoding complex nested objects before anonymization."""
        import dataclasses
        from opik import jsonable_encoder

        @dataclasses.dataclass
        class Person:
            name: str
            email: str
            age: int
            address: Dict[str, str] = dataclasses.field(default_factory=dict)

        person = Person(name="John Doe", email="john@example.com", age=30)
        person.address["street"] = "123 Main Street"
        person.address["city"] = "New York"
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}

        # Encode the object first, as expected by anonymize_encoded_obj
        encoded_person = jsonable_encoder.encode(person)

        result = encoder_helpers.anonymize_encoded_obj(
            obj=encoded_person,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="trace",
        )

        expected = {
            "name": "John Doe",
            "email": "[ANONYMIZED]",
            "age": 30,
            "address": {
                "city": "New York",
                "street": "123 Main Street",
            },
        }
        assert result == expected

    def test_anonymize_encoded_obj__nested_dict_in_encoded_result(self):
        """Test that only top-level fields are anonymized in nested structures."""
        obj = {
            "user_info": {"email": "nested@example.com", "name": "Nested User"},
            "email": "top@example.com",
            "id": "12345",
        }
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}  # Only top-level email should be anonymized

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        expected = {
            "user_info": {
                "email": "nested@example.com",  # Not anonymized (nested)
                "name": "Nested User",
            },
            "email": "[ANONYMIZED]",  # Anonymized (top-level)
            "id": "12345",
        }
        assert result == expected

    def test_anonymize_encoded_obj__empty_dict(self):
        """Test handling of empty dictionary."""
        obj = {}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        assert result == {}

    def test_anonymize_encoded_obj__empty_fields_set(self):
        """Test with an empty fields_to_anonymize set."""
        obj = {"name": "John", "email": "john@example.com"}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = set()  # Empty set

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        # No fields should be anonymized
        expected = {"name": "John", "email": "john@example.com"}
        assert result == expected

    def test_anonymize_encoded_obj__various_field_types(self):
        """Test anonymization of fields with various data types."""
        obj = {
            "string_field": "test string",
            "int_field": 42,
            "float_field": 3.14,
            "bool_field": True,
            "none_field": None,
            "list_field": [1, 2, 3],
        }

        # Create an anonymizer that just adds a prefix
        class PrefixAnonymizer(anonymizer.Anonymizer):
            def anonymize(self, data, **kwargs):
                return f"ANON_{data}"

        prefix_anonymizer = PrefixAnonymizer()
        fields_to_anonymize = {"string_field", "int_field", "bool_field", "none_field"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[prefix_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        expected = {
            "string_field": "ANON_test string",
            "int_field": "ANON_42",
            "float_field": 3.14,  # Not anonymized
            "bool_field": "ANON_True",
            "none_field": "ANON_None",
            "list_field": [1, 2, 3],  # Not anonymized
        }
        assert result == expected

    def test_anonymize_encoded_obj__integration_with_actual_encoder_features(self):
        """Test integration with actual encoder features like datetime serialization."""
        from datetime import datetime, timezone
        from opik import jsonable_encoder

        obj = {
            "timestamp": datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc),
            "email": "test@example.com",
            "data": {"nested": "value"},
        }
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}

        # Encode the object first, as expected by anonymize_encoded_obj
        encoded_obj = jsonable_encoder.encode(obj)

        result = encoder_helpers.anonymize_encoded_obj(
            obj=encoded_obj,
            anonymizers=[mock_anonymizer],
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        # Should have encoded datetime and anonymized email
        assert "timestamp" in result
        assert result["timestamp"] == "2023-01-01T12:00:00Z"  # Encoded datetime
        assert result["email"] == "[ANONYMIZED]"  # Anonymized field
        assert result["data"] == {"nested": "value"}  # Unchanged nested data

    def test_anonymize_encoded_obj__remove_sensitive_dictionary_key(self):
        """Test that sensitive keys can be removed from the result."""

        class ApiKeyAnonymizer(anonymizer.Anonymizer):
            def anonymize(self, data, **kwargs):
                if "api_key" in data:
                    del data["api_key"]
                return data

        obj = {
            "metadata": {
                "api_key": "12345",
                "email": "test@example.com",
                "data": {"nested": "value"},
            },
            "input": {"role": "user", "question": "What is LLM?"},
        }

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[ApiKeyAnonymizer()],
            fields_to_anonymize={"metadata"},
            object_type="span",
        )

        # should remove api_key
        assert "api_key" not in result["metadata"]

    def test_anonymize_encoded_obj__field_name_passed_to_anonymizer(self):
        """Test that sensitive field names and auxiliary information are passed to the anonymizer."""

        class ApiKeyAnonymizer(anonymizer.Anonymizer):
            def anonymize(self, data, **kwargs):
                field_name = kwargs.get("field_name")
                object_type = kwargs.get("object_type")
                if (
                    field_name == "metadata"
                    and object_type == "span"
                    and "api_key" in data
                ):
                    del data["api_key"]
                return data

        class SSNAnonymizer(recursive_anonymizer.RecursiveAnonymizer):
            def anonymize_text(
                self, data: str, field_name: Optional[str] = None, **kwargs: Any
            ) -> str:
                object_type = kwargs.get("object_type")
                if field_name == "input.ssn" and object_type == "span":
                    return "[SSN_REMOVED]"

                return data

        obj = {
            "metadata": {
                "api_key": "12345",
                "email": "test@example.com",
                "data": {"nested": "value"},
            },
            "input": {
                "api_key": "12345",
                "ssn": "123-4567-789",
                "role": "user",
                "question": "What is LLM?",
            },
        }

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=[ApiKeyAnonymizer(), SSNAnonymizer()],
            fields_to_anonymize={"metadata", "input"},
            object_type="span",
        )

        # should remove api_key from metadata
        assert "api_key" not in result["metadata"]

        # should not remove api_key from input
        assert "api_key" in result["input"]

        # should have SSN removed
        assert result["input"]["ssn"] == "[SSN_REMOVED]"

    def test_anonymize_encoded_obj__multiple_anonymizers(self):
        """Test that multiple anonymizers are applied in a sequence."""

        class PrefixAnonymizer(anonymizer.Anonymizer):
            def anonymize(self, data, **kwargs):
                if isinstance(data, str):
                    return f"PREFIX_{data}"
                return data

        class SuffixAnonymizer(anonymizer.Anonymizer):
            def anonymize(self, data, **kwargs):
                if isinstance(data, str):
                    return f"{data}_SUFFIX"
                return data

        obj = {"email": "test@example.com", "name": "John Doe"}
        anonymizers = [PrefixAnonymizer(), SuffixAnonymizer()]
        fields_to_anonymize = {"email"}

        result = encoder_helpers.anonymize_encoded_obj(
            obj=obj,
            anonymizers=anonymizers,
            fields_to_anonymize=fields_to_anonymize,
            object_type="span",
        )

        # Should apply both anonymizers in order: first prefix, then suffix
        expected = {"email": "PREFIX_test@example.com_SUFFIX", "name": "John Doe"}
        assert result == expected
