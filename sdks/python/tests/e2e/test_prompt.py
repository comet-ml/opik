import json
import uuid
import pytest
import opik
from opik.api_objects.prompt import PromptType
from . import verifiers
import opik.exceptions


def test_prompt__create__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        metadata={"outer-key": {"inner-key": "inner-value"}},
    )
    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
        type=PromptType.MUSTACHE,
        metadata={"outer-key": {"inner-key": "inner-value"}},
        version_id=prompt.__internal_api__version_id__,
        prompt_id=prompt.__internal_api__prompt_id__,
        commit=prompt.commit,
    )


def test_prompt__create_new_version__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    # create initial version
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    unique_identifier_new = str(uuid.uuid4())[-6:]
    prompt_template_new = f"some-prompt-text-{unique_identifier_new}"

    # must create new version
    new_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template_new,
    )

    verifiers.verify_prompt_version(
        new_prompt,
        name=prompt.name,
        template=prompt_template_new,
    )
    assert new_prompt.__internal_api__prompt_id__ == prompt.__internal_api__prompt_id__
    assert (
        new_prompt.__internal_api__version_id__ != prompt.__internal_api__version_id__
    )
    assert new_prompt.commit != prompt.commit
    assert new_prompt.prompt != prompt.prompt


def test_prompt__do_not_create_new_version_with_the_same_template(
    opik_client: opik.Opik,
):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    # create initial version
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    # must NOT create new version
    new_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    verifiers.verify_prompt_version(
        new_prompt,
        name=prompt.name,
        template=prompt.prompt,
    )
    assert new_prompt.__internal_api__prompt_id__ == prompt.__internal_api__prompt_id__
    assert (
        new_prompt.__internal_api__version_id__ == prompt.__internal_api__version_id__
    )
    assert new_prompt.commit == prompt.commit
    assert new_prompt.prompt == prompt.prompt


def test_prompt__get_by_name__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    original_prompt_version = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    unique_identifier_new = str(uuid.uuid4())[-6:]
    prompt_template_new = f"some-prompt-text-{unique_identifier_new}"

    new_prompt_version = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template_new,
    )

    new_prompt_version_from_api = opik_client.get_prompt(
        name=original_prompt_version.name
    )

    assert (
        new_prompt_version_from_api.__internal_api__prompt_id__
        == new_prompt_version.__internal_api__prompt_id__
    )
    assert (
        new_prompt_version_from_api.__internal_api__version_id__
        == new_prompt_version.__internal_api__version_id__
    )
    assert new_prompt_version_from_api.commit == new_prompt_version.commit
    assert new_prompt_version_from_api.prompt == new_prompt_version.prompt

    previous_prompt_from_api = opik_client.get_prompt(
        name=original_prompt_version.name, commit=original_prompt_version.commit
    )

    assert (
        previous_prompt_from_api.__internal_api__prompt_id__
        == original_prompt_version.__internal_api__prompt_id__
    )
    assert (
        previous_prompt_from_api.__internal_api__version_id__
        == original_prompt_version.__internal_api__version_id__
    )
    assert previous_prompt_from_api.commit == original_prompt_version.commit
    assert previous_prompt_from_api.prompt == original_prompt_version.prompt


def test_prompt__get__not_exists(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"

    prompt = opik_client.get_prompt(prompt_name)

    assert prompt is None


def test_prompt__initialize_class_instance(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]
    template = "Hello, {name} from {place}! Nice to meet you, {name}."

    prompt = opik.Prompt(name=f"test-{unique_identifier}", prompt=template)
    prompt_from_api = opik_client.get_prompt(name=prompt.name)

    verifiers.verify_prompt_version(
        prompt_from_api,
        name=prompt.name,
        template=prompt.prompt,
        version_id=prompt.__internal_api__version_id__,
        prompt_id=prompt.__internal_api__prompt_id__,
        commit=prompt.commit,
    )


def test_prompt__format():
    unique_identifier = str(uuid.uuid4())[-6:]
    template = "Hello, {{name}} from {{place}}! Nice to meet you, {{name}}."

    prompt = opik.Prompt(name=f"test-{unique_identifier}", prompt=template)

    result = prompt.format(name="John", place="The Earth")
    assert result == "Hello, John from The Earth! Nice to meet you, John."

    assert prompt.prompt == template


def test_prompt__create_with_custom_type(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name, prompt=prompt_template, type="jinja2"
    )

    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
        type=PromptType.JINJA2,
    )


def test_prompt__type_persists_in_get(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    opik_client.create_prompt(name=prompt_name, prompt=prompt_template, type="jinja2")

    retrieved_prompt = opik_client.get_prompt(name=prompt_name)
    verifiers.verify_prompt_version(
        retrieved_prompt,
        name=prompt_name,
        template=prompt_template,
        type=PromptType.JINJA2,
    )


def test_prompt__type_in_new_version(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )
    assert prompt.type == PromptType.MUSTACHE

    new_prompt = opik_client.create_prompt(
        name=prompt_name, prompt=prompt_template + "-v2", type="jinja2"
    )

    verifiers.verify_prompt_version(
        new_prompt,
        type=PromptType.JINJA2,
        prompt_id=prompt.__internal_api__prompt_id__,
    )
    assert (
        new_prompt.__internal_api__version_id__ != prompt.__internal_api__version_id__
    )
    assert new_prompt.commit != prompt.commit
    assert new_prompt.prompt != prompt.prompt


def test_prompt__search_prompts__returns_all_versions(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name_1 = f"some-prompt-name-{unique_identifier}"
    prompt_name_2 = f"some-prompt-name-{unique_identifier}-v2"

    opik_client.create_prompt(name=prompt_name_1, prompt="old-template-1")
    opik_client.create_prompt(name=prompt_name_1, prompt="new-template-1")
    opik_client.create_prompt(name=prompt_name_2, prompt="some-template-2")

    prompts = opik_client.search_prompts()

    templates = {p.prompt for p in prompts if isinstance(p, opik.Prompt)}
    assert "new-template-1" in templates
    assert "some-template-2" in templates


def test_prompt__get_prompts__with_filters__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template_1 = f"some-prompt-text-{unique_identifier}"
    prompt_template_2 = f"some-prompt-text-{unique_identifier}-v2"

    # Create two versions for the same prompt
    _ = opik_client.create_prompt(name=prompt_name, prompt=prompt_template_1)
    prompt_version2 = opik_client.create_prompt(
        name=prompt_name, prompt=prompt_template_2
    )

    # Add tags to prompt version 2
    opik_client.rest_client.prompts.update_prompt(
        id=prompt_version2.__internal_api__prompt_id__,
        name=prompt_version2.name,
        tags=["alpha", "beta"],
    )

    filtered_prompts = opik_client.search_prompts(
        filter_string=f'name contains "{prompt_name}" AND tags contains "alpha" AND tags contains "beta"',
    )

    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].prompt == prompt_template_2


def test_prompt__search_prompts__by_name__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name_1 = "common-prefix-one"
    prompt_name_2 = "common-prefix-two"
    prompt_name_3 = f"other-group-{unique_identifier}-three"

    # Create three prompts with different names
    opik_client.create_prompt(name=prompt_name_1, prompt="template-1")
    opik_client.create_prompt(name=prompt_name_2, prompt="template-2")
    opik_client.create_prompt(name=prompt_name_3, prompt="template-3")

    # Search by name substring via OQL (no additional filters) to retrieve only two matching prompts
    results = opik_client.search_prompts(filter_string='name contains "common-prefix"')

    names = set(p.name for p in results if isinstance(p, opik.Prompt))
    assert len(results) == 2
    assert names == set([prompt_name_1, prompt_name_2])


def test_prompt__template_structure_immutable__error(opik_client: opik.Opik):
    """Test that template_structure is immutable after prompt creation."""
    unique_identifier = str(uuid.uuid4())[-6:]
    prompt_name = f"test-immutable-structure-{unique_identifier}"

    # Create initial text prompt
    text_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="This is a text prompt: {{variable}}",
    )

    # Verify text prompt was created
    verifiers.verify_prompt_version(
        text_prompt,
        name=prompt_name,
        template="This is a text prompt: {{variable}}",
    )

    # Attempt to create a chat prompt version with the same name should fail
    with pytest.raises(opik.exceptions.PromptTemplateStructureMismatch):
        opik_client.create_chat_prompt(
            name=prompt_name,
            messages=[
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": "Hello!"},
            ],
        )

    # Verify the original text prompt remains unchanged
    retrieved_prompt = opik_client.get_prompt(name=prompt_name)
    assert retrieved_prompt is not None
    verifiers.verify_prompt_version(
        retrieved_prompt,
        name=prompt_name,
        template=text_prompt.prompt,
        commit=text_prompt.commit,
        prompt_id=text_prompt.__internal_api__prompt_id__,
        version_id=text_prompt.__internal_api__version_id__,
    )


def test_get_prompt__string_prompt__returns_prompt(opik_client: opik.Opik):
    """Test that get_prompt() returns a Prompt object for text prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"text-prompt-{unique_id}"

    # Create a text prompt
    created_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Hello {{name}}",
    )

    # Retrieve it with get_prompt()
    retrieved_prompt = opik_client.get_prompt(name=prompt_name)

    assert retrieved_prompt is not None
    assert isinstance(retrieved_prompt, opik.Prompt)
    assert retrieved_prompt.name == prompt_name
    assert retrieved_prompt.commit == created_prompt.commit


def test_get_prompt__chat_prompt__returns_none(opik_client: opik.Opik):
    """Test that get_prompt() raises an error for chat prompts (type mismatch)."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-{unique_id}"

    # Create a chat prompt
    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "system", "content": "You are helpful"},
            {"role": "user", "content": "Hello"},
        ],
    )

    # Try to retrieve it with get_prompt() - should raise an error due to type mismatch
    with pytest.raises(opik.exceptions.PromptTemplateStructureMismatch):
        opik_client.get_prompt(name=prompt_name)

    # Verify the chat prompt remains unchanged
    retrieved_chat_prompt = opik_client.get_chat_prompt(name=prompt_name)
    assert retrieved_chat_prompt is not None
    verifiers.verify_chat_prompt_version(
        retrieved_chat_prompt,
        name=prompt_name,
        messages=chat_prompt.template,
        commit=chat_prompt.commit,
        prompt_id=chat_prompt.__internal_api__prompt_id__,
        version_id=chat_prompt.__internal_api__version_id__,
    )


def test_get_prompt_history__string_prompt__returns_prompts(opik_client: opik.Opik):
    """Test that get_prompt_history() returns Prompt objects for text prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"text-prompt-history-{unique_id}"

    # Create multiple versions of a text prompt
    v1 = opik_client.create_prompt(name=prompt_name, prompt="Version 1")
    v2 = opik_client.create_prompt(name=prompt_name, prompt="Version 2")
    v3 = opik_client.create_prompt(name=prompt_name, prompt="Version 3")

    # Retrieve history
    history = opik_client.get_prompt_history(name=prompt_name)

    assert len(history) == 3
    assert all(isinstance(p, opik.Prompt) for p in history)
    assert history[0].name == prompt_name

    # Verify commits are in the history
    commits = {p.commit for p in history}
    assert v1.commit in commits
    assert v2.commit in commits
    assert v3.commit in commits


def test_get_prompt_history__chat_prompt__returns_empty_list(opik_client: opik.Opik):
    """Test that get_prompt_history() raises an error for chat prompts (type mismatch)."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-history-{unique_id}"

    # Create a chat prompt
    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Hello"}],
    )

    # Try to get history with get_prompt_history() - should raise an error due to type mismatch
    with pytest.raises(opik.exceptions.PromptTemplateStructureMismatch):
        opik_client.get_prompt_history(name=prompt_name)

    # Verify the chat prompt remains unchanged
    retrieved_chat_prompt = opik_client.get_chat_prompt(name=prompt_name)
    assert retrieved_chat_prompt is not None
    verifiers.verify_chat_prompt_version(
        retrieved_chat_prompt,
        name=prompt_name,
        messages=chat_prompt.template,
        commit=chat_prompt.commit,
        prompt_id=chat_prompt.__internal_api__prompt_id__,
        version_id=chat_prompt.__internal_api__version_id__,
    )


def test_search_prompts__returns_both_types(opik_client: opik.Opik):
    """Test that search_prompts() returns both text and chat prompts."""
    unique_id = str(uuid.uuid4())[-6:]

    # Create text prompts
    text_prompt_1 = opik_client.create_prompt(
        name=f"text-search-{unique_id}-1",
        prompt="Text prompt 1",
    )
    text_prompt_2 = opik_client.create_prompt(
        name=f"text-search-{unique_id}-2",
        prompt="Text prompt 2",
    )

    # Create chat prompts with similar names
    chat_prompt_1 = opik_client.create_chat_prompt(
        name=f"chat-search-{unique_id}-1",
        messages=[{"role": "user", "content": "Chat 1"}],
    )
    chat_prompt_2 = opik_client.create_chat_prompt(
        name=f"chat-search-{unique_id}-2",
        messages=[{"role": "user", "content": "Chat 2"}],
    )

    # Search for all prompts with the unique_id
    results = opik_client.search_prompts(filter_string=f'name contains "{unique_id}"')

    # Should return both text and chat prompts
    assert len(results) == 4
    text_prompts = [p for p in results if isinstance(p, opik.Prompt)]
    chat_prompts = [p for p in results if isinstance(p, opik.ChatPrompt)]
    assert len(text_prompts) == 2
    assert len(chat_prompts) == 2
    assert {p.name for p in text_prompts} == {
        text_prompt_1.name,
        text_prompt_2.name,
    }
    assert {p.name for p in chat_prompts} == {chat_prompt_1.name, chat_prompt_2.name}


def test_search_prompts__filter_by_template_structure_text(opik_client: opik.Opik):
    """Test that search_prompts() can filter by template_structure='text'."""
    unique_id = str(uuid.uuid4())[-6:]

    # Create text and chat prompts
    text_prompt = opik_client.create_prompt(
        name=f"text-search-{unique_id}",
        prompt="Text prompt",
    )
    _ = opik_client.create_chat_prompt(
        name=f"chat-search-{unique_id}",
        messages=[{"role": "user", "content": "Chat"}],
    )

    # Search for only text prompts
    results = opik_client.search_prompts(
        filter_string=f'name contains "{unique_id}" AND template_structure = "text"'
    )

    # Should only return text prompts
    assert len(results) == 1
    assert isinstance(results[0], opik.Prompt)
    assert results[0].name == text_prompt.name


def test_search_prompts__filter_by_template_structure_chat(opik_client: opik.Opik):
    """Test that search_prompts() can filter by template_structure='chat'."""
    unique_id = str(uuid.uuid4())[-6:]

    # Create text and chat prompts
    _ = opik_client.create_prompt(
        name=f"text-search-{unique_id}",
        prompt="Text prompt",
    )
    chat_prompt = opik_client.create_chat_prompt(
        name=f"chat-search-{unique_id}",
        messages=[{"role": "user", "content": "Chat"}],
    )

    # Search for only chat prompts
    results = opik_client.search_prompts(
        filter_string=f'name contains "{unique_id}" AND template_structure = "chat"'
    )

    # Should only return chat prompts
    assert len(results) == 1
    assert isinstance(results[0], opik.ChatPrompt)
    assert results[0].name == chat_prompt.name


def test_get_prompt__with_commit__string_prompt(opik_client: opik.Opik):
    """Test that get_prompt() with commit works for text prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"string-prompt-commit-{unique_id}"

    # Create multiple versions
    v1 = opik_client.create_prompt(name=prompt_name, prompt="Version 1")
    _ = opik_client.create_prompt(name=prompt_name, prompt="Version 2")

    # Retrieve specific version by commit
    retrieved_v1 = opik_client.get_prompt(name=prompt_name, commit=v1.commit)

    assert retrieved_v1 is not None
    assert isinstance(retrieved_v1, opik.Prompt)
    assert retrieved_v1.commit == v1.commit
    assert retrieved_v1.prompt == "Version 1"


def test_get_prompt__nonexistent__returns_none(opik_client: opik.Opik):
    """Test that get_prompt() returns None for non-existent prompts."""
    result = opik_client.get_prompt(name="nonexistent-prompt-12345")
    assert result is None


def test_prompt__format_playground_chat_prompt__returns_json(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"chat-prompt-{unique_identifier}"
    # JSON template representing a chat messages array with multimodal content
    # Build as Python structure for readability, then convert to JSON
    prompt_structure = [
        {
            "role": "system",
            "content": "You are {{assistant_type}}",
        },
        {
            "role": "user",
            "content": "{{user_message}}",
        },
        {
            "role": "assistant",
            "content": "{{assistant_response}}",
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": "{{followup_text}}",
                },
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "{{image_url}}",
                    },
                },
                {
                    "type": "video_url",
                    "video_url": {
                        "url": "{{video_url}}",
                    },
                },
            ],
        },
    ]
    prompt_template = json.dumps(prompt_structure)

    # Create a playground chat prompt with the appropriate metadata
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        metadata={
            "created_from": "opik_ui",
            "type": "messages_json",
        },
    )

    # Format the prompt with variables
    result = prompt.format(
        assistant_type="a helpful AI",
        user_message="Hello!",
        assistant_response="Hi there! How can I help you today?",
        followup_text="Can you analyze this image and video?",
        image_url="https://example.com/image.jpg",
        video_url="https://example.com/video.mp4",
    )

    # Should return parsed JSON (list of dicts), not a string
    expected_result = [
        {
            "role": "system",
            "content": "You are a helpful AI",
        },
        {
            "role": "user",
            "content": "Hello!",
        },
        {
            "role": "assistant",
            "content": "Hi there! How can I help you today?",
        },
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": "Can you analyze this image and video?",
                },
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "https://example.com/image.jpg",
                    },
                },
                {
                    "type": "video_url",
                    "video_url": {
                        "url": "https://example.com/video.mp4",
                    },
                },
            ],
        },
    ]

    assert result == expected_result


def test_prompt__format_playground_chat_prompt__invalid_json__returns_string(
    opik_client: opik.Opik,
):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"invalid-chat-prompt-{unique_identifier}"
    # Invalid JSON template (missing closing bracket)
    prompt_template = '[{"role": "system", "content": "{{content}}"}'

    # Create a playground chat prompt with the appropriate metadata
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        metadata={
            "created_from": "opik_ui",
            "type": "messages_json",
        },
    )

    # Format the prompt - should handle invalid JSON gracefully
    result = prompt.format(content="test content")

    # Should return the formatted string (not parsed) because JSON is invalid
    assert isinstance(result, str)
    assert result == '[{"role": "system", "content": "test content"}'
