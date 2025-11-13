import base64
import dataclasses
from datetime import date, datetime, timezone
from threading import Lock
from typing import Any, Optional, Dict

import numpy as np

import pytest

import opik.jsonable_encoder as jsonable_encoder
from opik.anonymizer import anonymizer


@dataclasses.dataclass
class Node:
    value: int
    child: Optional["Node"] = None


def test_jsonable_encoder__cyclic_reference():
    """
    Test that the encoder detects cyclic references and does not infinitely recurse.
    """
    # Create a simple two-node cycle: A -> B -> A
    node_a = Node(value=1)
    node_b = Node(value=2)
    node_a.child = node_b
    node_b.child = node_a

    encoded = jsonable_encoder.encode(node_a)
    # The exact format of the cycle marker can vary; we check that:
    # 1. We get some structure for node_a (like a dict).
    # 2. Inside node_a, there's a reference to node_b (a dict).
    # 3. Inside node_b, there's a "cyclic reference" marker instead of a full node_a object.
    print("=" * 150)
    print(encoded)
    assert isinstance(encoded, dict)
    assert "value" in encoded
    assert "child" in encoded

    # node_a.child (which is node_b) should be a dict
    assert isinstance(encoded["child"], dict)
    assert "value" in encoded["child"]
    assert "child" in encoded["child"]

    # node_b.child should be the cycle marker
    cycle_marker = encoded["child"]["child"]
    print("=" * 150)
    print(cycle_marker)
    assert isinstance(
        cycle_marker, str
    ), "Expected a string marker for cyclic reference"
    assert (
        "<Cyclic reference to " in cycle_marker
    ), "Should contain 'Cyclic reference' text"


def test_jsonable_encoder__repeated_objects_in_list():
    """
    Test that the encoder handles a list of the same object repeated multiple times
    without marking it as a cycle (because it isn't a cycle—just repeated references).
    """
    node = Node(value=42)

    # Put the same node object in a list multiple times
    repeated_list = [node, node, node]

    encoded = jsonable_encoder.encode(repeated_list)
    # We expect a list of three items, each being a dict with `value` = 42, `child` = None
    assert isinstance(encoded, list)
    assert len(encoded) == 3

    for item in encoded:
        assert isinstance(item, dict)
        assert item.get("value") == 42
        assert item.get("child") is None

    # They are distinct dictionary objects, but there is no cycle reference marker
    # because there's no actual cycle. It's just repeated references of the same object.
    assert all("Cyclic reference" not in str(item) for item in encoded)


@pytest.mark.parametrize(
    "obj",
    [
        42,
        42.000,
        "42",
        {
            "p1": 42,
            "p2": 42.000,
            "p3": "42",
        },
        [
            42,
            42.000,
            "42",
        ],
    ],
)
def test_jsonable_encoder__common_types(obj):
    assert obj == jsonable_encoder.encode(obj)


@pytest.mark.parametrize(
    "obj",
    [
        {42, 42.000, "42"},
        (42, 42.000, "42"),
        np.array([42, 42.000, "42"]),
    ],
)
def test_jsonable_encoder__converted_to_list(obj):
    assert list(obj) == jsonable_encoder.encode(obj)


@pytest.mark.parametrize(
    "obj,expected",
    [
        (
            date(2020, 1, 1),
            "2020-01-01",
        ),
        (datetime(2020, 1, 1, 10, 20, 30, tzinfo=timezone.utc), "2020-01-01T10:20:30Z"),
    ],
)
def test_jsonable_encoder__datetime_to_text(obj, expected):
    assert expected == jsonable_encoder.encode(obj)


def test_jsonable_encoder__non_serializable_to_text__class():
    class SomeClass:
        a = 1
        b = 42.0

    data = SomeClass()

    assert "SomeClass object at 0x" in jsonable_encoder.encode(data)


def test_jsonable_encoder__non_serializable_to_text__lock():
    data = Lock()

    assert jsonable_encoder.encode(data).startswith(
        "<unlocked _thread.lock object at 0x"
    )


def test_jsonable_encoder__non_serializable_lock_inside_dataclass__lock_converted_to_text():
    @dataclasses.dataclass
    class SomeClass:
        a: Any
        b: Any

    data = SomeClass(a=1, b=Lock())

    encoded = jsonable_encoder.encode(data)
    assert isinstance(encoded, dict)
    assert encoded["a"] == 1
    assert encoded["b"].startswith("<unlocked _thread.lock object at 0x")


def test_jsonable_encoder__non_serializable_to_text__bytes():
    data = b"deadbeef"

    assert base64.b64encode(data).decode("utf-8") == jsonable_encoder.encode(data)


class MockAnonymizer(anonymizer.Anonymizer):
    """Mock anonymizer for testing purposes."""

    def anonymize(self, data, **kwargs):
        """Mock anonymization that replaces strings with '[ANONYMIZED]'."""
        if isinstance(data, str):
            return "[ANONYMIZED]"
        return data


class TestEncodeAndAnonymize:
    """Test suite for encode_and_anonymize functionality."""

    def test_encode_and_anonymize__no_anonymizer__returns_encoded_only(self):
        """Test that without anonymizer, only encoding is performed."""
        obj = {"name": "John Doe", "email": "john@example.com"}

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj, field_anonymizer=None, fields_to_anonymize=None
        )

        expected = {"name": "John Doe", "email": "john@example.com"}
        assert result == expected

    def test_encode_and_anonymize__with_anonymizer_no_fields__raises_error(self):
        """Test that providing anonymizer without fields raises ValueError."""
        obj = {"name": "John Doe"}
        mock_anonymizer = MockAnonymizer()

        with pytest.raises(
            ValueError,
            match="fields_to_anonymize must be set if field_anonymizer is set",
        ):
            jsonable_encoder.encode_and_anonymize(
                obj=obj, field_anonymizer=mock_anonymizer, fields_to_anonymize=None
            )

    def test_encode_and_anonymize__dict_with_matching_fields(self):
        """Test anonymization of a dictionary with matching field names."""
        obj = {
            "name": "John Doe",
            "email": "john@example.com",
            "phone": "123-456-7890",
            "age": 30,
        }
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email", "phone"}

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        expected = {
            "name": "John Doe",
            "email": "[ANONYMIZED]",
            "phone": "[ANONYMIZED]",
            "age": 30,
        }
        assert result == expected

    def test_encode_and_anonymize__dict_with_no_matching_fields(self):
        """Test that fields not in dict are ignored."""
        obj = {"name": "John Doe", "age": 30}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email", "phone"}  # These fields don't exist in obj

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        expected = {"name": "John Doe", "age": 30}
        assert result == expected

    def test_encode_and_anonymize__dict_partial_field_match(self):
        """Test anonymization when only some specified fields exist."""
        obj = {"name": "John Doe", "email": "john@example.com", "age": 30}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email", "phone", "ssn"}  # Only email exists

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        expected = {"name": "John Doe", "email": "[ANONYMIZED]", "age": 30}
        assert result == expected

    def test_encode_and_anonymize__non_dict_object__no_anonymization(self):
        """Test that non-dict objects are not anonymized."""
        obj = ["item1", "item2", "item3"]
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"item1"}

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        # Should return an encoded list without anonymization
        assert result == ["item1", "item2", "item3"]

    def test_encode_and_anonymize__string_object__no_anonymization(self):
        """Test that string objects are not anonymized."""
        obj = "This is a sensitive string"
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"field1"}

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        assert result == "This is a sensitive string"

    def test_encode_and_anonymize__complex_nested_object(self):
        """Test encoding complex nested objects before anonymization."""
        import dataclasses

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

        result = jsonable_encoder.encode_and_anonymize(
            obj=person,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
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

    def test_encode_and_anonymize__nested_dict_in_encoded_result(self):
        """Test that only top-level fields are anonymized in nested structures."""
        obj = {
            "user_info": {"email": "nested@example.com", "name": "Nested User"},
            "email": "top@example.com",
            "id": "12345",
        }
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}  # Only top-level email should be anonymized

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
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

    def test_encode_and_anonymize__empty_dict(self):
        """Test handling of empty dictionary."""
        obj = {}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        assert result == {}

    def test_encode_and_anonymize__empty_fields_set(self):
        """Test with an empty fields_to_anonymize set."""
        obj = {"name": "John", "email": "john@example.com"}
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = set()  # Empty set

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        # No fields should be anonymized
        expected = {"name": "John", "email": "john@example.com"}
        assert result == expected

    def test_encode_and_anonymize__various_field_types(self):
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

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=prefix_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
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

    def test_encode_and_anonymize__integration_with_actual_encoder_features(self):
        """Test integration with actual encoder features like datetime serialization."""
        from datetime import datetime, timezone

        obj = {
            "timestamp": datetime(2023, 1, 1, 12, 0, 0, tzinfo=timezone.utc),
            "email": "test@example.com",
            "data": {"nested": "value"},
        }
        mock_anonymizer = MockAnonymizer()
        fields_to_anonymize = {"email"}

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=mock_anonymizer,
            fields_to_anonymize=fields_to_anonymize,
        )

        # Should have encoded datetime and anonymized email
        assert "timestamp" in result
        assert result["timestamp"] == "2023-01-01T12:00:00Z"  # Encoded datetime
        assert result["email"] == "[ANONYMIZED]"  # Anonymized field
        assert result["data"] == {"nested": "value"}  # Unchanged nested data

    def test_encode_and_anonymize__remove_sensitive_dictionary_key(self):
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

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=ApiKeyAnonymizer(),
            fields_to_anonymize={"metadata"},
        )

        # should remove api_key
        assert "api_key" not in result["metadata"]

    def test_encode_and_anonymize__field_name_passed_to_anonymizer(self):
        """Test that sensitive field names are passed to the anonymizer."""

        class ApiKeyAnonymizer(anonymizer.Anonymizer):
            def anonymize(self, data, **kwargs):
                field_name = kwargs.get("field_name")
                if field_name == "metadata" and "api_key" in data:
                    del data["api_key"]
                return data

        obj = {
            "metadata": {
                "api_key": "12345",
                "email": "test@example.com",
                "data": {"nested": "value"},
            },
            "input": {"api_key": "12345", "role": "user", "question": "What is LLM?"},
        }

        result = jsonable_encoder.encode_and_anonymize(
            obj=obj,
            field_anonymizer=ApiKeyAnonymizer(),
            fields_to_anonymize={"metadata"},
        )

        # should remove api_key from metadata
        assert "api_key" not in result["metadata"]

        # should not remove api_key from input
        assert "api_key" in result["input"]
