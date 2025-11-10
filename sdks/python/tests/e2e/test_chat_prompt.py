import uuid
import opik
from opik.api_objects.prompt import PromptType, ChatPrompt
from . import verifiers


def test_chat_prompt__create__happyflow(opik_client: opik.Opik):
    """Test creating a chat prompt with multiple messages."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-{unique_identifier}"
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Hello, {{name}}!"},
    ]

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
        metadata={"version": "1.0", "type": "customer_support"},
    )

    # Verify the prompt was created correctly
    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=messages,
        metadata={"version": "1.0", "type": "customer_support"},
        type=PromptType.MUSTACHE,
    )


def test_chat_prompt__format__happyflow(opik_client: opik.Opik):
    """Test formatting a chat prompt with variables."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-format-{unique_identifier}"
    messages = [
        {"role": "system", "content": "You are a {{role}}."},
        {"role": "user", "content": "Hello, {{name}}! Tell me about {{topic}}."},
    ]

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
    )

    # Verify the prompt was created correctly
    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=messages,
    )

    # Format with variables
    formatted = chat_prompt.format(
        variables={"role": "coding assistant", "name": "Alice", "topic": "Python"}
    )

    assert len(formatted) == 2
    assert formatted[0]["role"] == "system"
    assert formatted[0]["content"] == "You are a coding assistant."
    assert formatted[1]["role"] == "user"
    assert formatted[1]["content"] == "Hello, Alice! Tell me about Python."


def test_chat_prompt__create_new_version__happyflow(opik_client: opik.Opik):
    """Test creating a new version of a chat prompt."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-versioning-{unique_identifier}"
    messages_v1 = [
        {"role": "system", "content": "You are helpful."},
        {"role": "user", "content": "Hi!"},
    ]

    # Create initial version
    chat_prompt_v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages_v1,
    )

    # Create new version with different messages
    messages_v2 = [
        {"role": "system", "content": "You are very helpful."},
        {"role": "user", "content": "Hello there!"},
        {"role": "assistant", "content": "How can I assist you?"},
    ]

    chat_prompt_v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages_v2,
    )

    # Verify both versions
    verifiers.verify_chat_prompt_version(
        chat_prompt_v1,
        name=prompt_name,
        messages=messages_v1,
    )

    verifiers.verify_chat_prompt_version(
        chat_prompt_v2,
        name=prompt_name,
        messages=messages_v2,
    )

    # Verify they share the same prompt ID but have different version IDs
    assert (
        chat_prompt_v2.__internal_api__prompt_id__
        == chat_prompt_v1.__internal_api__prompt_id__
    )
    assert (
        chat_prompt_v2.__internal_api__version_id__
        != chat_prompt_v1.__internal_api__version_id__
    )
    assert chat_prompt_v2.commit != chat_prompt_v1.commit


def test_chat_prompt__do_not_create_new_version_with_same_messages(
    opik_client: opik.Opik,
):
    """Test that creating a chat prompt with identical messages doesn't create a new version."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-no-dup-{unique_identifier}"
    messages = [
        {"role": "system", "content": "You are helpful."},
        {"role": "user", "content": "Hello!"},
    ]

    # Create initial version
    chat_prompt_v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
    )

    # Try to create with same messages
    chat_prompt_v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
    )

    # Verify both prompts
    verifiers.verify_chat_prompt_version(
        chat_prompt_v1,
        name=prompt_name,
        messages=messages,
        commit=chat_prompt_v1.commit,
        prompt_id=chat_prompt_v1.__internal_api__prompt_id__,
    )

    verifiers.verify_chat_prompt_version(
        chat_prompt_v2,
        name=prompt_name,
        messages=messages,
        commit=chat_prompt_v1.commit,
        prompt_id=chat_prompt_v1.__internal_api__prompt_id__,
    )


def test_chat_prompt__direct_class_instantiation__happyflow(opik_client: opik.Opik):
    """Test creating a ChatPrompt directly using the class."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-direct-{unique_identifier}"
    messages = [
        {"role": "system", "content": "You are an expert."},
        {"role": "user", "content": "Explain {{concept}}."},
    ]

    # Create directly via ChatPrompt class
    chat_prompt = ChatPrompt(
        name=prompt_name,
        messages=messages,
        metadata={"category": "education"},
    )

    # Verify it was synced to backend
    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=messages,
        metadata={"category": "education"},
    )


def test_chat_prompt__multimodal_content__happyflow(opik_client: opik.Opik):
    """Test chat prompt with multimodal content (text + images)."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-multimodal-{unique_identifier}"
    messages = [
        {"role": "system", "content": "You analyze images."},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "What's in this image of {{subject}}?"},
                {
                    "type": "image_url",
                    "image_url": {"url": "https://example.com/image.jpg"},
                },
            ],
        },
    ]

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
    )

    # Verify multimodal content is preserved
    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=messages,
    )

    assert len(chat_prompt.messages[1]["content"]) == 2
    assert chat_prompt.messages[1]["content"][0]["type"] == "text"
    assert chat_prompt.messages[1]["content"][1]["type"] == "image_url"


def test_chat_prompt__different_types__mustache_and_jinja2(opik_client: opik.Opik):
    """Test chat prompts with different template types."""
    unique_identifier = str(uuid.uuid4())[-6:]

    # Test with Mustache
    prompt_name_mustache = f"chat-prompt-mustache-{unique_identifier}"
    messages_mustache = [
        {"role": "user", "content": "Hello {{name}}!"},
    ]

    chat_prompt_mustache = opik_client.create_chat_prompt(
        name=prompt_name_mustache,
        messages=messages_mustache,
        type=PromptType.MUSTACHE,
    )

    verifiers.verify_chat_prompt_version(
        chat_prompt_mustache,
        name=prompt_name_mustache,
        messages=messages_mustache,
        type=PromptType.MUSTACHE,
    )

    formatted_mustache = chat_prompt_mustache.format(variables={"name": "Bob"})
    assert formatted_mustache[0]["content"] == "Hello Bob!"

    # Test with Jinja2
    prompt_name_jinja = f"chat-prompt-jinja-{unique_identifier}"
    messages_jinja = [
        {"role": "user", "content": "Hello {{ name }}!"},
    ]

    chat_prompt_jinja = opik_client.create_chat_prompt(
        name=prompt_name_jinja,
        messages=messages_jinja,
        type=PromptType.JINJA2,
    )

    verifiers.verify_chat_prompt_version(
        chat_prompt_jinja,
        name=prompt_name_jinja,
        messages=messages_jinja,
        type=PromptType.JINJA2,
    )

    formatted_jinja = chat_prompt_jinja.format(variables={"name": "Carol"})
    assert formatted_jinja[0]["content"] == "Hello Carol!"


def test_chat_prompt__empty_messages__should_work(opik_client: opik.Opik):
    """Test chat prompt with empty messages list."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-empty-{unique_identifier}"
    messages = []

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
    )

    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=[],
    )


def test_chat_prompt__multiple_roles__happyflow(opik_client: opik.Opik):
    """Test chat prompt with system, user, and assistant messages."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-multirole-{unique_identifier}"
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "What is the capital of {{country}}?"},
        {"role": "assistant", "content": "I'd be happy to help with that!"},
        {"role": "user", "content": "Please tell me."},
    ]

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
    )

    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=messages,
    )

    formatted = chat_prompt.format(variables={"country": "France"})
    assert len(formatted) == 4
    assert formatted[0]["role"] == "system"
    assert formatted[1]["role"] == "user"
    assert formatted[1]["content"] == "What is the capital of France?"
    assert formatted[2]["role"] == "assistant"
    assert formatted[3]["role"] == "user"


def test_get_chat_prompt__chat_prompt__returns_chat_prompt(opik_client: opik.Opik):
    """Test that get_chat_prompt() returns a ChatPrompt object for chat prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-get-{unique_id}"

    # Create a chat prompt
    created_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "system", "content": "You are helpful"},
            {"role": "user", "content": "Hello {{name}}"},
        ],
    )

    # Retrieve it with get_chat_prompt()
    retrieved_prompt = opik_client.get_chat_prompt(name=prompt_name)

    assert retrieved_prompt is not None
    assert isinstance(retrieved_prompt, opik.ChatPrompt)
    assert retrieved_prompt.name == prompt_name
    assert retrieved_prompt.commit == created_prompt.commit
    assert len(retrieved_prompt.messages) == 2


def test_get_chat_prompt__string_prompt__returns_none(opik_client: opik.Opik):
    """Test that get_chat_prompt() returns None for string prompts (type mismatch)."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"string-prompt-{unique_id}"

    # Create a string prompt
    opik_client.create_prompt(
        name=prompt_name,
        prompt="Hello {{name}}",
    )

    # Try to retrieve it with get_chat_prompt() - should return None due to type mismatch
    retrieved_prompt = opik_client.get_chat_prompt(name=prompt_name)
    assert retrieved_prompt is None


def test_get_chat_prompt_history__chat_prompt__returns_chat_prompts(
    opik_client: opik.Opik,
):
    """Test that get_chat_prompt_history() returns ChatPrompt objects for chat prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-history-{unique_id}"

    # Create multiple versions of a chat prompt
    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Version 1"}],
    )
    v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Version 2"}],
    )
    v3 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "system", "content": "System message"},
            {"role": "user", "content": "Version 3"},
        ],
    )

    # Retrieve history
    history = opik_client.get_chat_prompt_history(name=prompt_name)

    assert len(history) == 3
    assert all(isinstance(p, opik.ChatPrompt) for p in history)
    assert history[0].name == prompt_name

    # Verify commits are in the history
    commits = {p.commit for p in history}
    assert v1.commit in commits
    assert v2.commit in commits
    assert v3.commit in commits


def test_get_chat_prompt_history__string_prompt__returns_empty_list(
    opik_client: opik.Opik,
):
    """Test that get_chat_prompt_history() returns empty list for string prompts (type mismatch)."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"string-prompt-history-{unique_id}"

    # Create a string prompt
    opik_client.create_prompt(name=prompt_name, prompt="Hello")

    # Try to get history with get_chat_prompt_history() - should return empty list
    history = opik_client.get_chat_prompt_history(name=prompt_name)
    assert len(history) == 0


def test_search_prompts__filter_chat_prompts_only(opik_client: opik.Opik):
    """Test that search_prompts() with template_structure filter returns only ChatPrompt instances."""
    unique_id = str(uuid.uuid4())[-6:]

    # Create string prompts
    string_prompt_1 = opik_client.create_prompt(
        name=f"string-search-{unique_id}-1",
        prompt="String prompt 1",
    )
    string_prompt_2 = opik_client.create_prompt(
        name=f"string-search-{unique_id}-2",
        prompt="String prompt 2",
    )

    # Create chat prompts
    chat_prompt_1 = opik_client.create_chat_prompt(
        name=f"chat-search-{unique_id}-1",
        messages=[{"role": "user", "content": "Chat 1"}],
    )
    chat_prompt_2 = opik_client.create_chat_prompt(
        name=f"chat-search-{unique_id}-2",
        messages=[{"role": "user", "content": "Chat 2"}],
    )

    # Search for only chat prompts using template_structure filter
    results = opik_client.search_prompts(
        filter_string=f'name contains "{unique_id}" AND template_structure = "chat"'
    )

    # Should only return chat prompts, not string prompts
    assert len(results) == 2
    assert all(isinstance(p, opik.ChatPrompt) for p in results)

    result_names = {p.name for p in results}
    assert chat_prompt_1.name in result_names
    assert chat_prompt_2.name in result_names
    assert string_prompt_1.name not in result_names
    assert string_prompt_2.name not in result_names


def test_get_chat_prompt__with_commit__chat_prompt(opik_client: opik.Opik):
    """Test that get_chat_prompt() with commit works for chat prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-commit-{unique_id}"

    # Create multiple versions
    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Version 1"}],
    )
    _ = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Version 2"}],
    )

    # Retrieve specific version by commit
    retrieved_v1 = opik_client.get_chat_prompt(name=prompt_name, commit=v1.commit)

    assert retrieved_v1 is not None
    assert isinstance(retrieved_v1, opik.ChatPrompt)
    assert retrieved_v1.commit == v1.commit
    assert retrieved_v1.messages[0]["content"] == "Version 1"


def test_get_chat_prompt__nonexistent__returns_none(opik_client: opik.Opik):
    """Test that get_chat_prompt() returns None for non-existent prompts."""
    result = opik_client.get_chat_prompt(name="nonexistent-chat-prompt-12345")
    assert result is None
