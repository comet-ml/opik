import json
import uuid
import pytest
import opik
from opik.api_objects.prompt import PromptType
from . import verifiers


def _generate_random_suffix():
    return str(uuid.uuid4())[-6:]


def _generate_random_prompt_name():
    return f"some-prompt-name-{_generate_random_suffix()}"


def _generate_random_tag():
    return f"tag-{_generate_random_suffix()}"


def _generate_random_tags(n=2):
    return [_generate_random_tag() for _ in range(n)]


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


def test_prompt__create_with_additional_parameters__happyflow(opik_client: opik.Opik):
    """Test that create_prompt() accepts tags and description parameters."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"prompt-with-params-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"
    tags = ["tag1", "tag2", "production"]
    description = "This is a test prompt description"

    # Create prompt with tags and description
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        tags=tags,
        description=description,
    )

    # Verify prompt was created
    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
    )

    # Verify tags were set by searching for the prompt
    filtered_prompts = opik_client.search_prompts(
        filter_string=f'name = "{prompt_name}" AND tags contains "tag1"',
    )
    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].name == prompt_name

    # Retrieve the prompt to verify description was set
    retrieved_prompt = opik_client.get_prompt(name=prompt_name)
    assert retrieved_prompt is not None
    assert retrieved_prompt.name == prompt_name


def test_prompt__create_with_tags__happyflow(opik_client: opik.Opik):
    """Test that create_prompt() accepts tags parameter and tags can be accessed from search results."""
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"prompt-with-tags-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"
    tags = ["text-tag1", "text-tag2", "production"]

    # Create text prompt with tags
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        tags=tags,
    )

    # Verify prompt was created
    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
    )

    # Verify tags were set by searching for the prompt and accessing tags property
    filtered_prompts = opik_client.search_prompts(
        filter_string=f'name = "{prompt_name}" AND tags contains "text-tag1"',
    )
    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].name == prompt_name
    assert set(filtered_prompts[0].tags) == set(tags)


def test_prompt__filter_versions(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    shared_tag = _generate_random_tag()
    v1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v1.version_id],
        tags=[shared_tag, _generate_random_tag()],
    )
    v2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v2.version_id],
        tags=_generate_random_tags(),
    )
    v3 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v3-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v3.version_id],
        tags=[_generate_random_tag(), shared_tag],
    )

    filtered_versions = opik_client.get_prompt_history(
        name=prompt_name,
        filter_string=f'tags contains "{shared_tag}"',
    )

    assert len(filtered_versions) == 2
    version_ids = {v.version_id for v in filtered_versions}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_prompt__search_versions(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    search_term = f"unique-search-term-{_generate_random_suffix()}"
    v1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"This template contains {search_term} for testing",
    )
    v2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"This template has different content {_generate_random_suffix()} for testing",
    )
    v3 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Another template with {search_term} included",
    )

    search_results = opik_client.get_prompt_history(
        name=prompt_name, search=search_term
    )

    assert len(search_results) == 2
    version_ids = {v.version_id for v in search_results}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_chat_prompt__filter_versions(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    shared_tag = _generate_random_tag()
    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Message v1-{_generate_random_suffix()}"}
        ],
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v1.version_id],
        tags=[shared_tag, _generate_random_tag()],
    )
    v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Message v2-{_generate_random_suffix()}"}
        ],
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v2.version_id],
        tags=_generate_random_tags(),
    )
    v3 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Message v3-{_generate_random_suffix()}"}
        ],
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v3.version_id],
        tags=[_generate_random_tag(), shared_tag],
    )

    filtered_versions = opik_client.get_chat_prompt_history(
        name=prompt_name,
        filter_string=f'tags contains "{shared_tag}"',
    )

    assert len(filtered_versions) == 2
    version_ids = {v.version_id for v in filtered_versions}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_chat_prompt__search_versions(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    search_term = f"unique-search-term-{_generate_random_suffix()}"
    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {
                "role": "user",
                "content": f"This message contains {search_term} for testing",
            }
        ],
    )
    v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {
                "role": "user",
                "content": f"This message has different content {_generate_random_suffix()} for testing",
            }
        ],
    )
    v3 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Another message with {search_term} included"}
        ],
    )

    search_results = opik_client.get_chat_prompt_history(
        name=prompt_name, search=search_term
    )

    assert len(search_results) == 2
    version_ids = {v.version_id for v in search_results}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_prompt__update_version_tags__replace_mode(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id],
        tags=_generate_random_tags(),
        merge=False,
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version2.version_id],
        tags=_generate_random_tags(),
        merge=False,
    )

    new_tags = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id, version2.version_id],
        tags=new_tags,
        merge=False,
    )

    history = opik_client.get_prompt_history(name=prompt_name)
    assert len(history) == 2
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert set(v1_in_history.tags) == set(new_tags)
    assert set(v2_in_history.tags) == set(new_tags)


def test_prompt__update_version_tags__default_replace_mode(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id],
        tags=_generate_random_tags(),
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version2.version_id],
        tags=_generate_random_tags(),
    )

    new_tags = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id, version2.version_id],
        tags=new_tags,
    )

    history = opik_client.get_prompt_history(name=prompt_name)
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert set(v1_in_history.tags) == set(new_tags)
    assert set(v2_in_history.tags) == set(new_tags)


def test_prompt__update_version_tags__clear_with_empty_array(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id, version2.version_id],
        tags=_generate_random_tags(),
    )

    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id, version2.version_id],
        tags=[],
    )

    history = opik_client.get_prompt_history(name=prompt_name)
    assert len(history) == 2
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert v1_in_history.tags == []
    assert v2_in_history.tags == []


@pytest.mark.parametrize("merge_param", [False, True, None])
def test_prompt__update_version_tags__preserve_with_none(
    opik_client: opik.Opik, merge_param
):
    prompt_name = _generate_random_prompt_name()
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
    )
    initial_tags_v1 = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id],
        tags=initial_tags_v1,
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
    )
    initial_tags_v2 = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version2.version_id],
        tags=initial_tags_v2,
    )

    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id, version2.version_id],
        tags=None,
        merge=merge_param,
    )

    history = opik_client.get_prompt_history(name=prompt_name)
    assert len(history) == 2
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert set(v1_in_history.tags) == set(initial_tags_v1)
    assert set(v2_in_history.tags) == set(initial_tags_v2)


def test_prompt__update_version_tags__merge_mode(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
    )
    initial_tags_v1 = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id],
        tags=initial_tags_v1,
        merge=False,
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
    )
    initial_tags_v2 = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version2.version_id],
        tags=initial_tags_v2,
        merge=False,
    )

    additional_tags = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id, version2.version_id],
        tags=additional_tags,
        merge=True,
    )

    history = opik_client.get_prompt_history(name=prompt_name)
    assert len(history) == 2
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert set(v1_in_history.tags) == set(initial_tags_v1 + additional_tags)
    assert set(v2_in_history.tags) == set(initial_tags_v2 + additional_tags)


def test_chat_prompt__update_version_tags(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Message v1 {_generate_random_suffix()}"}
        ],
    )
    v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Message v2 {_generate_random_suffix()}"}
        ],
    )

    new_tags = _generate_random_tags()
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v1.version_id, v2.version_id],
        tags=new_tags,
        merge=False,
    )

    history = opik_client.get_chat_prompt_history(name=prompt_name)
    assert len(history) == 2
    v1_in_history = next((v for v in history if v.version_id == v1.version_id), None)
    v2_in_history = next((v for v in history if v.version_id == v2.version_id), None)
    assert set(v1_in_history.tags) == set(new_tags)
    assert set(v2_in_history.tags) == set(new_tags)
