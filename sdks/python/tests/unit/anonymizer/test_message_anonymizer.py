import datetime

from opik.anonymizer import message_anonymizer
from opik.anonymizer import factory
from opik.message_processing import messages


class TestAnonymizeMessage:
    """Test suite for the anonymize_message function."""

    def test_anonymize_message__create_trace_message(self):
        """Test anonymizing CreateTraceMessage input, output, and metadata."""
        anonymizer = factory.create_anonymizer(
            [
                {"regex": r"\d{3}-\d{3}-\d{4}", "replace": "[PHONE]"},
                {
                    "regex": r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}",
                    "replace": "[EMAIL]",
                },
            ]
        )

        message = messages.CreateTraceMessage(
            trace_id="trace-123",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={"user_info": "Contact John at john@example.com or 123-456-7890"},
            output={"result": "Sent email to jane@test.org and called 987-654-3210"},
            metadata={
                "contacts": ["admin@company.com", "support: 555-123-4567"],
                "notes": "Phone: 111-222-3333",
            },
            tags=["test"],
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be a new object
        assert result is not message

        # Check that sensitive data was anonymized
        assert result.input["user_info"] == "Contact John at [EMAIL] or [PHONE]"
        assert result.output["result"] == "Sent email to [EMAIL] and called [PHONE]"
        assert result.metadata["contacts"] == ["[EMAIL]", "support: [PHONE]"]
        assert result.metadata["notes"] == "Phone: [PHONE]"

        # Check that other fields remain unchanged
        assert result.trace_id == message.trace_id
        assert result.project_name == message.project_name
        assert result.name == message.name
        assert result.tags == message.tags

    def test_anonymize_message__create_span_message(self):
        """Test anonymizing CreateSpanMessage input, output, and metadata."""
        anonymizer = factory.create_anonymizer(
            [
                {"regex": r"secret_key_\w+", "replace": "[API_KEY]"},
                {"regex": r"\b\d{4} \d{4} \d{4} \d{4}\b", "replace": "[CARD]"},
            ]
        )

        message = messages.CreateSpanMessage(
            span_id="span-123",
            trace_id="trace-456",
            project_name="test-project",
            parent_span_id=None,
            name="test-span",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={
                "api_key": "secret_key_abc123xyz",
                "payment_info": {"card": "1234 5678 9012 3456"},
            },
            output={
                "success": True,
                "transaction": {"card_used": "4567 8901 2345 6789"},
            },
            metadata={
                "debug": "Using secret_key_def456ghi",
                "backup_cards": ["9876 5432 1098 7654"],
            },
            tags=["payment"],
            type="llm",
            usage=None,
            model=None,
            provider=None,
            error_info=None,
            total_cost=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be a new object
        assert result is not message

        # Check that sensitive data was anonymized
        assert result.input["api_key"] == "[API_KEY]"
        assert result.input["payment_info"]["card"] == "[CARD]"
        assert result.output["transaction"]["card_used"] == "[CARD]"
        assert result.metadata["debug"] == "Using [API_KEY]"
        assert result.metadata["backup_cards"] == ["[CARD]"]

        # Check that other fields remain unchanged
        assert result.span_id == message.span_id
        assert result.trace_id == message.trace_id
        assert result.type == message.type

    def test_anonymize_message__create_trace_message_none_fields(self):
        """Test anonymizing CreateTraceMessage with None input/output/metadata."""
        anonymizer = factory.create_anonymizer({"regex": r"\d+", "replace": "***"})

        message = messages.CreateTraceMessage(
            trace_id="trace-123",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime.now(),
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be a new object
        assert result is not message

        # None fields should remain None
        assert result.input is None
        assert result.output is None
        assert result.metadata is None

        # Other fields should be unchanged
        assert result.trace_id == message.trace_id

    def test_anonymize_message__create_span_message_none_fields(self):
        """Test anonymizing CreateSpanMessage with None input/output/metadata."""
        anonymizer = factory.create_anonymizer({"regex": r"\d+", "replace": "***"})

        message = messages.CreateSpanMessage(
            span_id="span-123",
            trace_id="trace-456",
            project_name="test-project",
            parent_span_id=None,
            name="test-span",
            start_time=datetime.datetime.now(),
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            type="general",
            usage=None,
            model=None,
            provider=None,
            error_info=None,
            total_cost=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be a new object
        assert result is not message

        # None fields should remain None
        assert result.input is None
        assert result.output is None
        assert result.metadata is None

        # Other fields should be unchanged
        assert result.span_id == message.span_id

    def test_anonymize_message__nested_data_structures(self):
        """Test anonymizing messages with deeply nested data structures."""
        anonymizer = factory.create_anonymizer(
            [
                {"regex": r"user_\d+", "replace": "[USER_ID]"},
                {"regex": r"pass_\w+", "replace": "[PASSWORD]"},
            ]
        )

        message = messages.CreateTraceMessage(
            trace_id="trace-123",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={
                "authentication": {
                    "users": [
                        {
                            "id": "user_123",
                            "credentials": {"password": "pass_secret123"},
                        },
                        {
                            "id": "user_456",
                            "credentials": {"password": "pass_hidden789"},
                        },
                    ]
                }
            },
            output={
                "results": {
                    "authenticated_users": ["user_789", "user_101"],
                    "failed_logins": {"user_222": "Invalid pass_wrong456"},
                }
            },
            metadata={
                "audit_log": [
                    {
                        "action": "login",
                        "user": "user_333",
                        "details": "pass_correct999",
                    },
                    {"action": "logout", "user": "user_444"},
                ]
            },
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Check nested anonymization in input
        users = result.input["authentication"]["users"]
        assert users[0]["id"] == "[USER_ID]"
        assert users[0]["credentials"]["password"] == "[PASSWORD]"
        assert users[1]["id"] == "[USER_ID]"
        assert users[1]["credentials"]["password"] == "[PASSWORD]"

        # Check nested anonymization in output
        assert result.output["results"]["authenticated_users"] == [
            "[USER_ID]",
            "[USER_ID]",
        ]
        # Dictionary keys are not anonymized, only values
        failed_logins = result.output["results"]["failed_logins"]
        assert len(failed_logins) == 1
        assert "user_222" in failed_logins
        assert failed_logins["user_222"] == "Invalid [PASSWORD]"

        # Check nested anonymization in metadata
        audit_log = result.metadata["audit_log"]
        assert audit_log[0]["user"] == "[USER_ID]"
        assert audit_log[0]["details"] == "[PASSWORD]"
        assert audit_log[1]["user"] == "[USER_ID]"

    def test_anonymize_message__unsupported_message_type_unchanged(self):
        """Test that unsupported message types are returned unchanged."""
        anonymizer = factory.create_anonymizer({"regex": r"\d+", "replace": "***"})

        # Test with UpdateSpanMessage (should not be anonymized)
        message = messages.UpdateSpanMessage(
            span_id="span-123",
            parent_span_id=None,
            trace_id="trace-456",
            project_name="test-project",
            end_time=None,
            input={"sensitive": "data with 12345"},
            output={"result": "contains 67890"},
            metadata={"info": "has numbers 11111"},
            tags=None,
            usage=None,
            model=None,
            provider=None,
            error_info=None,
            total_cost=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be the exact same object (no copy made)
        assert result is message

        # Data should be unchanged
        assert result.input["sensitive"] == "data with 12345"
        assert result.output["result"] == "contains 67890"
        assert result.metadata["info"] == "has numbers 11111"

    def test_anonymize_message__feedback_score_message_unchanged(self):
        """Test that FeedbackScoreMessage is returned unchanged."""
        anonymizer = factory.create_anonymizer({"regex": r"\d+", "replace": "***"})

        message = messages.FeedbackScoreMessage(
            id="feedback-123-456",
            project_name="test-project",
            name="score-with-789",
            value=0.85,
            source="user-101112",
            reason="Good response with ID 131415",
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be the exact same object
        assert result is message

        # All fields should be unchanged
        assert result.id == "feedback-123-456"
        assert result.name == "score-with-789"
        assert result.source == "user-101112"
        assert result.reason == "Good response with ID 131415"

    def test_anonymize_message__empty_data_structures(self):
        """Test anonymizing messages with empty data structures."""
        anonymizer = factory.create_anonymizer({"regex": r"\d+", "replace": "***"})

        message = messages.CreateTraceMessage(
            trace_id="trace-123",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={},
            output={},
            metadata={},
            tags=[],
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Should be a new object
        assert result is not message

        # Empty structures should remain empty
        assert result.input == {}
        assert result.output == {}
        assert result.metadata == {}
        assert result.tags == []

    def test_anonymize_message__mixed_data_types(self):
        """Test anonymizing messages with mixed data types (strings, numbers, booleans)."""
        anonymizer = factory.create_anonymizer(
            {"regex": r"secret", "replace": "[REDACTED]"}
        )

        message = messages.CreateSpanMessage(
            span_id="span-123",
            trace_id="trace-456",
            project_name="test-project",
            parent_span_id=None,
            name="test-span",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={
                "text": "This is secret information",
                "number": 12345,
                "boolean": True,
                "null_value": None,
                "list": ["secret data", 678, False],
            },
            output={
                "success": True,
                "message": "secret operation completed",
                "count": 42,
            },
            metadata={"config": {"enabled": True, "key": "secret_key", "timeout": 30}},
            tags=None,
            type="general",
            usage=None,
            model=None,
            provider=None,
            error_info=None,
            total_cost=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Check that strings were anonymized
        assert result.input["text"] == "This is [REDACTED] information"
        assert result.input["list"][0] == "[REDACTED] data"
        assert result.output["message"] == "[REDACTED] operation completed"
        assert result.metadata["config"]["key"] == "[REDACTED]_key"

        # Check that non-strings remain unchanged
        assert result.input["number"] == 12345
        assert result.input["boolean"] is True
        assert result.input["null_value"] is None
        assert result.input["list"][1] == 678
        assert result.input["list"][2] is False
        assert result.output["success"] is True
        assert result.output["count"] == 42
        assert result.metadata["config"]["enabled"] is True
        assert result.metadata["config"]["timeout"] == 30

    def test_anonymize_message__function_rule(self):
        """Test anonymizing messages with function-based rules."""

        def uppercase_anonymizer(text: str) -> str:
            return text.upper()

        anonymizer = factory.create_anonymizer(uppercase_anonymizer)

        message = messages.CreateTraceMessage(
            trace_id="trace-123",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={"message": "hello world"},
            output={"response": "goodbye world"},
            metadata={"note": "test message"},
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Check that function rule was applied
        assert result.input["message"] == "HELLO WORLD"
        assert result.output["response"] == "GOODBYE WORLD"
        assert result.metadata["note"] == "TEST MESSAGE"

    def test_anonymize_message__multiple_rules_applied_sequentially(self):
        """Test that multiple rules are applied sequentially."""
        anonymizer = factory.create_anonymizer(
            [
                {"regex": r"step1", "replace": "STEP1"},
                {"regex": r"STEP1", "replace": "FINAL"},
                lambda text: text.replace("test", "TEST"),
            ]
        )

        message = messages.CreateTraceMessage(
            trace_id="trace-123",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime.now(),
            end_time=None,
            input={"data": "This is step1 test data"},
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        result = message_anonymizer.anonymize_message(message, anonymizer)

        # Rules should be applied sequentially: step1 -> STEP1 -> FINAL, test -> TEST
        assert result.input["data"] == "This is FINAL TEST data"
