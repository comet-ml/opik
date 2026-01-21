from opik_optimizer.algorithms.hierarchical_reflective_optimizer.reporting import (
    MessageDiffItem,
    compute_message_diff_order,
)
from tests.unit.fixtures import (
    Message,
    assistant_message,
    system_message,
    tool_message,
    user_message,
)


class TestComputeMessageDiffOrder:
    def test_system_changed_user_unchanged__displays_in_optimized_order(self) -> None:
        """Test that when system message changes and user is unchanged, they display in optimized order (system first, then user)."""
        # Arrange
        initial_messages = [
            system_message("Provide a concise answer to the question."),
            user_message("What is the capital of France?"),
        ]
        optimized_messages = [
            system_message("Provide a concise and accurate answer to the question."),
            user_message("What is the capital of France?"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2, f"Expected 2 diff items, got {len(result)}"

        # First item should be system (changed)
        assert result[0].role == "system"
        assert result[0].change_type == "changed"
        assert result[0].initial_content == "Provide a concise answer to the question."
        assert (
            result[0].optimized_content
            == "Provide a concise and accurate answer to the question."
        )

        # Second item should be user (unchanged)
        assert result[1].role == "user"
        assert result[1].change_type == "unchanged"
        assert result[1].initial_content == "What is the capital of France?"
        assert result[1].optimized_content == "What is the capital of France?"

    def test_message_added(self) -> None:
        """Test that a newly added message role is correctly identified."""
        # Arrange
        initial_messages = [user_message("What is the capital of France?")]
        optimized_messages = [
            system_message("You are a helpful assistant."),
            user_message("What is the capital of France?"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2

        # First item should be system (added) - follows optimized order
        assert result[0].role == "system"
        assert result[0].change_type == "added"
        assert result[0].initial_content is None
        assert result[0].optimized_content == "You are a helpful assistant."

        # Second item should be user (unchanged)
        assert result[1].role == "user"
        assert result[1].change_type == "unchanged"

    def test_message_removed(self) -> None:
        """Test that a removed message role is correctly identified."""
        # Arrange
        initial_messages = [
            system_message("You are a helpful assistant."),
            user_message("What is the capital of France?"),
        ]
        optimized_messages = [user_message("What is the capital of France?")]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2

        # First item should be user (unchanged) - follows optimized order
        assert result[0].role == "user"
        assert result[0].change_type == "unchanged"

        # Second item should be system (removed) - appears at end
        assert result[1].role == "system"
        assert result[1].change_type == "removed"
        assert result[1].initial_content == "You are a helpful assistant."
        assert result[1].optimized_content is None

    def test_all_messages_unchanged(self) -> None:
        """Test when all messages remain unchanged."""
        # Arrange
        initial_messages = [
            system_message("You are a helpful assistant."),
            user_message("Hello!"),
        ]
        optimized_messages = [
            system_message("You are a helpful assistant."),
            user_message("Hello!"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2
        assert all(item.change_type == "unchanged" for item in result)
        assert result[0].role == "system"
        assert result[1].role == "user"

    def test_multiple_roles_with_mixed_changes(self) -> None:
        """Test complex scenario with multiple roles having different change types."""
        # Arrange
        initial_messages = [
            system_message("Old system message"),
            user_message("User message"),
            assistant_message("Assistant response"),
        ]
        optimized_messages = [
            system_message("New system message"),
            user_message("User message"),
            tool_message("Tool output"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 4

        # Order should follow optimized, then removed
        assert result[0].role == "system"
        assert result[0].change_type == "changed"
        assert result[0].initial_content == "Old system message"
        assert result[0].optimized_content == "New system message"

        assert result[1].role == "user"
        assert result[1].change_type == "unchanged"

        assert result[2].role == "tool"
        assert result[2].change_type == "added"
        assert result[2].initial_content is None
        assert result[2].optimized_content == "Tool output"

        assert result[3].role == "assistant"
        assert result[3].change_type == "removed"
        assert result[3].initial_content == "Assistant response"
        assert result[3].optimized_content is None

    def test_empty_initial_messages(self) -> None:
        """Test when starting with no messages."""
        # Arrange
        initial_messages: list[Message] = []
        optimized_messages = [
            system_message("New system message"),
            user_message("New user message"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2
        assert all(item.change_type == "added" for item in result)
        assert result[0].role == "system"
        assert result[1].role == "user"

    def test_empty_optimized_messages(self) -> None:
        """Test when all messages are removed."""
        # Arrange
        initial_messages = [
            system_message("System message"),
            user_message("User message"),
        ]
        optimized_messages: list[Message] = []

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2
        assert all(item.change_type == "removed" for item in result)

    def test_role_reordering(self) -> None:
        """Test that when roles are reordered, the result follows optimized order."""
        # Arrange
        initial_messages = [
            user_message("Question"),
            system_message("Instructions"),
        ]
        optimized_messages = [
            system_message("Instructions"),
            user_message("Question"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 2
        assert all(item.change_type == "unchanged" for item in result)
        # Order should match optimized (system first)
        assert result[0].role == "system"
        assert result[1].role == "user"

    def test_content_with_newlines(self) -> None:
        """Test that multiline content is handled correctly."""
        # Arrange
        initial_messages = [system_message("Line 1\nLine 2\nLine 3")]
        optimized_messages = [system_message("Line 1\nModified Line 2\nLine 3")]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 1
        assert result[0].change_type == "changed"
        assert result[0].initial_content == "Line 1\nLine 2\nLine 3"
        assert result[0].optimized_content == "Line 1\nModified Line 2\nLine 3"

    def test_missing_role_defaults_to_message(self) -> None:
        """Test that messages without 'role' key default to 'message'."""
        # Arrange
        initial_messages = [{"content": "Some content"}]
        optimized_messages = [{"content": "Some content"}]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 1
        assert result[0].role == "message"
        assert result[0].change_type == "unchanged"

    def test_missing_content_defaults_to_empty_string(self) -> None:
        """Test that messages without 'content' key default to empty string."""
        # Arrange
        initial_messages = [{"role": "system"}]
        optimized_messages = [{"role": "system"}]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 1
        assert result[0].change_type == "unchanged"
        assert result[0].initial_content == ""
        assert result[0].optimized_content == ""

    def test_whitespace_changes_detected(self) -> None:
        """Test that whitespace-only changes are detected as changed."""
        # Arrange
        initial_messages = [system_message("Hello World")]
        optimized_messages = [system_message("Hello  World")]  # 2 spaces

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        assert len(result) == 1
        assert result[0].change_type == "changed"

    def test_single_role_multiple_times__uses_first_occurrence(self) -> None:
        """Test that when a role appears multiple times, only the first occurrence is used."""
        # Arrange
        initial_messages = [
            user_message("First message"),
            user_message("Second message"),
        ]
        optimized_messages = [
            user_message("Updated first message"),
            user_message("Updated second message"),
        ]

        # Act
        result = compute_message_diff_order(initial_messages, optimized_messages)

        # Assert
        # Should only compare first occurrences
        assert len(result) == 1
        assert result[0].role == "user"
        assert result[0].change_type == "changed"
        assert result[0].initial_content == "First message"
        assert result[0].optimized_content == "Updated first message"

    def test_dataclass_properties(self) -> None:
        """Test that MessageDiffItem can be created and accessed correctly."""
        # Arrange & Act
        item = MessageDiffItem(
            role="system",
            change_type="changed",
            initial_content="old",
            optimized_content="new",
        )

        # Assert
        assert item.role == "system"
        assert item.change_type == "changed"
        assert item.initial_content == "old"
        assert item.optimized_content == "new"
