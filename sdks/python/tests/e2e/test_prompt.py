import json
import uuid
import pytest
import opik
from opik import opik_context
from opik.api_objects.prompt import PromptType
from opik.api_objects.prompt import mask_context as prompt_mask_context_module
from opik.api_objects.prompt.prompt_cache import get_global_cache
from opik.rest_api.types.prompt_version_detail import PromptVersionDetail
from . import verifiers


def _generate_random_suffix():
    return str(uuid.uuid4())[-6:]


def _generate_random_tag():
    return f"tag-{_generate_random_suffix()}"


def _generate_random_tags(n=2):
    return [_generate_random_tag() for _ in range(n)]


def test_prompt__create__happyflow(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        metadata={"outer-key": {"inner-key": "inner-value"}},
        project_name=temporary_project_name,
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
        project_name=temporary_project_name,
    )


def test_prompt__create_new_version__happyflow(
    opik_client: opik.Opik, prompt_name: str
):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    # create initial version
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    prompt_template_new = f"some-prompt-text-{_generate_random_suffix()}"

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
    opik_client: opik.Opik, prompt_name: str
):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

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


def test_prompt__get_by_name__happyflow(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    original_prompt_version = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        project_name=temporary_project_name,
    )

    prompt_template_new = f"some-prompt-text-{_generate_random_suffix()}"

    new_prompt_version = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template_new,
        project_name=temporary_project_name,
    )

    new_prompt_version_from_api = opik_client.get_prompt(
        name=original_prompt_version.name,
        project_name=temporary_project_name,
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
        name=original_prompt_version.name,
        commit=original_prompt_version.commit,
        project_name=temporary_project_name,
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


def test_prompt__get__not_exists(opik_client: opik.Opik, prompt_name: str):
    prompt = opik_client.get_prompt(prompt_name)

    assert prompt is None


def test_prompt__initialize_class_instance(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    template = "Hello, {name} from {place}! Nice to meet you, {name}."

    prompt = opik.Prompt(
        name=prompt_name, prompt=template, project_name=temporary_project_name
    )
    prompt_from_api = opik_client.get_prompt(
        name=prompt.name, project_name=temporary_project_name
    )

    verifiers.verify_prompt_version(
        prompt_from_api,
        name=prompt.name,
        template=prompt.prompt,
        version_id=prompt.__internal_api__version_id__,
        prompt_id=prompt.__internal_api__prompt_id__,
        commit=prompt.commit,
        project_name=prompt.project_name,
    )


def test_prompt__format(prompt_name: str):
    template = "Hello, {{name}} from {{place}}! Nice to meet you, {{name}}."

    prompt = opik.Prompt(name=prompt_name, prompt=template)

    result = prompt.format(name="John", place="The Earth")
    assert result == "Hello, John from The Earth! Nice to meet you, John."

    assert prompt.prompt == template


def test_prompt__create_with_custom_type(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        type="jinja2",
        project_name=temporary_project_name,
    )

    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
        type=PromptType.JINJA2,
        project_name=temporary_project_name,
    )


def test_prompt__type_persists_in_get(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        type="jinja2",
        project_name=temporary_project_name,
    )

    retrieved_prompt = opik_client.get_prompt(
        name=prompt_name, project_name=temporary_project_name
    )
    verifiers.verify_prompt_version(
        retrieved_prompt,
        name=prompt_name,
        template=prompt_template,
        type=PromptType.JINJA2,
        project_name=temporary_project_name,
    )


def test_prompt__type_in_new_version(opik_client: opik.Opik, prompt_name: str):
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

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


def test_prompt__search_prompts__returns_all_versions(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    prompt_name_2 = f"{prompt_name}-v2"

    opik_client.create_prompt(
        name=prompt_name, prompt="old-template-1", project_name=temporary_project_name
    )
    opik_client.create_prompt(
        name=prompt_name, prompt="new-template-1", project_name=temporary_project_name
    )
    opik_client.create_prompt(
        name=prompt_name_2,
        prompt="some-template-2",
        project_name=temporary_project_name,
    )

    prompts = opik_client.search_prompts(project_name=temporary_project_name)

    templates = {p.prompt for p in prompts if isinstance(p, opik.Prompt)}
    assert "new-template-1" in templates
    assert "some-template-2" in templates


def test_prompt__get_prompts__with_filters__happyflow(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    prompt_template_1 = f"some-prompt-text-{_generate_random_suffix()}"
    prompt_template_2 = f"{prompt_template_1}-v2"

    # Create two versions for the same prompt
    _ = opik_client.create_prompt(
        name=prompt_name, prompt=prompt_template_1, project_name=temporary_project_name
    )
    prompt_version2 = opik_client.create_prompt(
        name=prompt_name, prompt=prompt_template_2, project_name=temporary_project_name
    )

    # Add tags to prompt version 2
    opik_client.rest_client.prompts.update_prompt(
        id=prompt_version2.__internal_api__prompt_id__,
        name=prompt_version2.name,
        tags=["alpha", "beta"],
    )

    filtered_prompts = opik_client.search_prompts(
        filter_string=f'name contains "{prompt_name}" AND tags contains "alpha" AND tags contains "beta"',
        project_name=temporary_project_name,
    )

    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].prompt == prompt_template_2


def test_prompt__search_prompts__by_name__happyflow(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    # Two prompts share a common prefix unique to this test; a third uses an
    # `-other-` infix so it doesn't contain that shared prefix. Searching for
    # the shared prefix must return only the first two.
    shared_prefix = f"{prompt_name}-common"
    prompt_name_1 = f"{shared_prefix}-one"
    prompt_name_2 = f"{shared_prefix}-two"
    prompt_name_3 = f"{prompt_name}-other-three"

    # Create three prompts with different names
    opik_client.create_prompt(
        name=prompt_name_1, prompt="template-1", project_name=temporary_project_name
    )
    opik_client.create_prompt(
        name=prompt_name_2, prompt="template-2", project_name=temporary_project_name
    )
    opik_client.create_prompt(
        name=prompt_name_3, prompt="template-3", project_name=temporary_project_name
    )

    results = opik_client.search_prompts(
        filter_string=f'name contains "{shared_prefix}"',
        project_name=temporary_project_name,
    )

    names = set(p.name for p in results if isinstance(p, opik.Prompt))
    assert len(results) == 2
    assert names == set([prompt_name_1, prompt_name_2])


def test_prompt__template_structure_immutable__error(
    opik_client: opik.Opik, prompt_name: str
):
    """Test that template_structure is immutable after prompt creation."""
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


def test_get_prompt__string_prompt__returns_prompt(
    opik_client: opik.Opik, prompt_name: str
):
    """Test that get_prompt() returns a Prompt object for text prompts."""
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


def test_get_prompt__chat_prompt__returns_none(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that get_prompt() raises an error for chat prompts (type mismatch)."""
    # Create a chat prompt
    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "system", "content": "You are helpful"},
            {"role": "user", "content": "Hello"},
        ],
        project_name=temporary_project_name,
    )

    # Try to retrieve it with get_prompt() - should raise an error due to type mismatch
    with pytest.raises(opik.exceptions.PromptTemplateStructureMismatch):
        opik_client.get_prompt(name=prompt_name)

    # Verify the chat prompt remains unchanged
    retrieved_chat_prompt = opik_client.get_chat_prompt(
        name=prompt_name, project_name=temporary_project_name
    )
    assert retrieved_chat_prompt is not None
    verifiers.verify_chat_prompt_version(
        retrieved_chat_prompt,
        name=prompt_name,
        messages=chat_prompt.template,
        commit=chat_prompt.commit,
        prompt_id=chat_prompt.__internal_api__prompt_id__,
        version_id=chat_prompt.__internal_api__version_id__,
        project_name=temporary_project_name,
    )


def test_get_prompt_history__string_prompt__returns_prompts(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that get_prompt_history() returns Prompt objects for text prompts."""
    # Create multiple versions of a text prompt
    v1 = opik_client.create_prompt(
        name=prompt_name, prompt="Version 1", project_name=temporary_project_name
    )
    v2 = opik_client.create_prompt(
        name=prompt_name, prompt="Version 2", project_name=temporary_project_name
    )
    v3 = opik_client.create_prompt(
        name=prompt_name, prompt="Version 3", project_name=temporary_project_name
    )

    # Retrieve history
    history = opik_client.get_prompt_history(
        name=prompt_name, project_name=temporary_project_name
    )

    assert len(history) == 3
    assert all(isinstance(p, opik.Prompt) for p in history)
    assert history[0].name == prompt_name
    assert history[0].project_name == temporary_project_name

    # Verify commits are in the history
    commits = {p.commit for p in history}
    assert v1.commit in commits
    assert v2.commit in commits
    assert v3.commit in commits


def test_get_prompt_history__chat_prompt__returns_empty_list(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that get_prompt_history() raises an error for chat prompts (type mismatch)."""
    # Create a chat prompt
    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Hello"}],
        project_name=temporary_project_name,
    )

    # Try to get history with get_prompt_history() - should raise an error due to type mismatch
    with pytest.raises(opik.exceptions.PromptTemplateStructureMismatch):
        opik_client.get_prompt_history(name=prompt_name)

    # Verify the chat prompt remains unchanged
    retrieved_chat_prompt = opik_client.get_chat_prompt(
        name=prompt_name, project_name=temporary_project_name
    )
    assert retrieved_chat_prompt is not None
    assert retrieved_chat_prompt.name == prompt_name
    assert retrieved_chat_prompt.project_name == temporary_project_name
    verifiers.verify_chat_prompt_version(
        retrieved_chat_prompt,
        name=prompt_name,
        messages=chat_prompt.template,
        commit=chat_prompt.commit,
        prompt_id=chat_prompt.__internal_api__prompt_id__,
        version_id=chat_prompt.__internal_api__version_id__,
        project_name=temporary_project_name,
    )


def test_search_prompts__returns_both_types(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that search_prompts() returns both text and chat prompts."""
    # Four prompts (2 text + 2 chat) all derive from the unique prompt_name fixture
    # so the search prefix below isolates this test's prompts from any others.
    text_name_1 = f"{prompt_name}-text-1"
    text_name_2 = f"{prompt_name}-text-2"
    chat_name_1 = f"{prompt_name}-chat-1"
    chat_name_2 = f"{prompt_name}-chat-2"

    # Create text prompts
    text_prompt_1 = opik_client.create_prompt(
        name=text_name_1,
        prompt="Text prompt 1",
        project_name=temporary_project_name,
    )
    text_prompt_2 = opik_client.create_prompt(
        name=text_name_2,
        prompt="Text prompt 2",
        project_name=temporary_project_name,
    )

    # Create chat prompts with similar names
    chat_prompt_1 = opik_client.create_chat_prompt(
        name=chat_name_1,
        messages=[{"role": "user", "content": "Chat 1"}],
        project_name=temporary_project_name,
    )
    chat_prompt_2 = opik_client.create_chat_prompt(
        name=chat_name_2,
        messages=[{"role": "user", "content": "Chat 2"}],
        project_name=temporary_project_name,
    )

    # Search for all prompts sharing the unique fixture prefix
    results = opik_client.search_prompts(
        filter_string=f'name contains "{prompt_name}"',
        project_name=temporary_project_name,
    )

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
    assert all(prompt.project_name == temporary_project_name for prompt in results)


def test_search_prompts__filter_by_template_structure_text(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that search_prompts() can filter by template_structure='text'."""
    text_name = f"{prompt_name}-text"
    chat_name = f"{prompt_name}-chat"

    # Create text and chat prompts
    text_prompt = opik_client.create_prompt(
        name=text_name,
        prompt="Text prompt",
        project_name=temporary_project_name,
    )
    _ = opik_client.create_chat_prompt(
        name=chat_name,
        messages=[{"role": "user", "content": "Chat"}],
        project_name=temporary_project_name,
    )

    # Search for only text prompts
    results = opik_client.search_prompts(
        filter_string=f'name contains "{prompt_name}" AND template_structure = "text"',
        project_name=temporary_project_name,
    )

    # Should only return text prompts
    assert len(results) == 1
    assert isinstance(results[0], opik.Prompt)
    assert results[0].name == text_prompt.name


def test_search_prompts__filter_by_template_structure_chat(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that search_prompts() can filter by template_structure='chat'."""
    text_name = f"{prompt_name}-text"
    chat_name = f"{prompt_name}-chat"

    # Create text and chat prompts
    _ = opik_client.create_prompt(
        name=text_name,
        prompt="Text prompt",
        project_name=temporary_project_name,
    )
    chat_prompt = opik_client.create_chat_prompt(
        name=chat_name,
        messages=[{"role": "user", "content": "Chat"}],
        project_name=temporary_project_name,
    )

    # Search for only chat prompts
    results = opik_client.search_prompts(
        filter_string=f'name contains "{prompt_name}" AND template_structure = "chat"',
        project_name=temporary_project_name,
    )

    # Should only return chat prompts
    assert len(results) == 1
    assert isinstance(results[0], opik.ChatPrompt)
    assert results[0].name == chat_prompt.name


def test_get_prompt__with_commit__string_prompt(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that get_prompt() with commit works for text prompts."""
    # Create multiple versions
    v1 = opik_client.create_prompt(
        name=prompt_name, prompt="Version 1", project_name=temporary_project_name
    )
    _ = opik_client.create_prompt(
        name=prompt_name, prompt="Version 2", project_name=temporary_project_name
    )

    # Retrieve specific version by commit
    retrieved_v1 = opik_client.get_prompt(
        name=prompt_name, commit=v1.commit, project_name=temporary_project_name
    )

    assert retrieved_v1 is not None
    assert isinstance(retrieved_v1, opik.Prompt)
    assert retrieved_v1.commit == v1.commit
    assert retrieved_v1.prompt == "Version 1"
    assert retrieved_v1.name == prompt_name
    assert retrieved_v1.project_name == temporary_project_name


def test_get_prompt__nonexistent__returns_none(opik_client: opik.Opik):
    """Test that get_prompt() returns None for non-existent prompts."""
    result = opik_client.get_prompt(
        name=f"nonexistent-prompt-{_generate_random_suffix()}"
    )
    assert result is None


def test_get_prompt__with_version__string_prompt(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that get_prompt() with the sequential ``version`` selector
    (e.g. ``"v1"``, ``"v2"``, ...) returns the correct prompt content."""
    # Create three versions of the same prompt.
    opik_client.create_prompt(
        name=prompt_name, prompt="Version 1", project_name=temporary_project_name
    )
    opik_client.create_prompt(
        name=prompt_name, prompt="Version 2", project_name=temporary_project_name
    )
    latest = opik_client.create_prompt(
        name=prompt_name, prompt="Version 3", project_name=temporary_project_name
    )

    expected_by_version = {
        "v1": "Version 1",
        "v2": "Version 2",
        "v3": "Version 3",
    }

    for selector, expected_template in expected_by_version.items():
        retrieved = opik_client.get_prompt(
            name=prompt_name,
            version=selector,
            project_name=temporary_project_name,
            no_cache=True,
        )
        assert retrieved is not None, f"expected prompt for version {selector}"
        assert isinstance(retrieved, opik.Prompt)
        assert retrieved.prompt == expected_template
        assert retrieved.version == selector
        assert retrieved.name == prompt_name
        assert retrieved.project_name == temporary_project_name

    # The latest prompt (no version selector) should expose version="v3".
    latest_fetched = opik_client.get_prompt(
        name=prompt_name, project_name=temporary_project_name, no_cache=True
    )
    assert latest_fetched is not None
    assert latest_fetched.version == "v3"
    # The create_prompt return value should also surface the version_number now.
    assert latest.version == "v3"


def test_prompt__format_playground_chat_prompt__returns_json(
    opik_client: opik.Opik, prompt_name: str
):
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
    opik_client: opik.Opik, prompt_name: str
):
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


def test_prompt__create_with_additional_parameters__happyflow(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that create_prompt() accepts tags and description parameters."""
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"
    tags = ["tag1", "tag2", "production"]
    description = "This is a test prompt description"

    # Create prompt with tags and description
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        tags=tags,
        description=description,
        project_name=temporary_project_name,
    )

    # Verify prompt was created
    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
        project_name=temporary_project_name,
    )

    # Verify tags were set by searching for the prompt
    filtered_prompts = opik_client.search_prompts(
        filter_string=f'name = "{prompt_name}" AND tags contains "tag1"',
        project_name=temporary_project_name,
    )
    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].name == prompt_name
    assert filtered_prompts[0].project_name == temporary_project_name

    # Retrieve the prompt to verify description was set
    retrieved_prompt = opik_client.get_prompt(
        name=prompt_name, project_name=temporary_project_name
    )
    assert retrieved_prompt is not None
    assert retrieved_prompt.name == prompt_name
    assert retrieved_prompt.project_name == temporary_project_name


def test_prompt__create_with_tags__happyflow(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    """Test that create_prompt() accepts tags parameter and tags can be accessed from search results."""
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"
    tags = ["text-tag1", "text-tag2", "production"]

    # Create text prompt with tags
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        tags=tags,
        project_name=temporary_project_name,
    )

    # Verify prompt was created
    verifiers.verify_prompt_version(
        prompt,
        name=prompt_name,
        template=prompt_template,
        project_name=temporary_project_name,
    )

    # Verify tags were set by searching for the prompt and accessing tags property
    filtered_prompts = opik_client.search_prompts(
        filter_string=f'name = "{prompt_name}" AND tags contains "text-tag1"',
        project_name=temporary_project_name,
    )
    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].name == prompt_name
    assert filtered_prompts[0].project_name == temporary_project_name
    assert set(filtered_prompts[0].tags) == set(tags)


def test_prompt__filter_versions(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    shared_tag = _generate_random_tag()

    v1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
        project_name=temporary_project_name,
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v1.version_id],
        tags=[shared_tag, _generate_random_tag()],
    )
    v2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
        project_name=temporary_project_name,
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v2.version_id],
        tags=_generate_random_tags(),
    )
    v3 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v3-{_generate_random_suffix()}",
        project_name=temporary_project_name,
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v3.version_id],
        tags=[_generate_random_tag(), shared_tag],
    )

    filtered_versions = opik_client.get_prompt_history(
        name=prompt_name,
        filter_string=f'tags contains "{shared_tag}"',
        project_name=temporary_project_name,
    )

    assert len(filtered_versions) == 2
    version_ids = {v.version_id for v in filtered_versions}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_prompt__search_versions(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    search_term = f"unique-search-term-{_generate_random_suffix()}"

    v1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"This template contains {search_term} for testing",
        project_name=temporary_project_name,
    )
    v2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"This template has different content {_generate_random_suffix()} for testing",
        project_name=temporary_project_name,
    )
    v3 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Another template with {search_term} included",
        project_name=temporary_project_name,
    )

    search_results = opik_client.get_prompt_history(
        name=prompt_name, search=search_term, project_name=temporary_project_name
    )

    assert len(search_results) == 2
    version_ids = {v.version_id for v in search_results}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_chat_prompt__filter_versions(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    shared_tag = _generate_random_tag()

    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Message v1-{_generate_random_suffix()}"}
        ],
        project_name=temporary_project_name,
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
        project_name=temporary_project_name,
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
        project_name=temporary_project_name,
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[v3.version_id],
        tags=[_generate_random_tag(), shared_tag],
    )

    filtered_versions = opik_client.get_chat_prompt_history(
        name=prompt_name,
        filter_string=f'tags contains "{shared_tag}"',
        project_name=temporary_project_name,
    )

    assert len(filtered_versions) == 2
    version_ids = {v.version_id for v in filtered_versions}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_chat_prompt__search_versions(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    search_term = f"unique-search-term-{_generate_random_suffix()}"

    v1 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {
                "role": "user",
                "content": f"This message contains {search_term} for testing",
            }
        ],
        project_name=temporary_project_name,
    )
    v2 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {
                "role": "user",
                "content": f"This message has different content {_generate_random_suffix()} for testing",
            }
        ],
        project_name=temporary_project_name,
    )
    v3 = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "user", "content": f"Another message with {search_term} included"}
        ],
        project_name=temporary_project_name,
    )

    search_results = opik_client.get_chat_prompt_history(
        name=prompt_name, search=search_term, project_name=temporary_project_name
    )

    assert len(search_results) == 2
    version_ids = {v.version_id for v in search_results}
    assert v1.version_id in version_ids
    assert v3.version_id in version_ids
    assert v2.version_id not in version_ids


def test_prompt__update_version_tags__replace_mode(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
        project_name=temporary_project_name,
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id],
        tags=_generate_random_tags(),
        merge=False,
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
        project_name=temporary_project_name,
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

    history = opik_client.get_prompt_history(
        name=prompt_name, project_name=temporary_project_name
    )
    assert len(history) == 2
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert set(v1_in_history.tags) == set(new_tags)
    assert set(v2_in_history.tags) == set(new_tags)


def test_prompt__update_version_tags__default_replace_mode(
    opik_client: opik.Opik, prompt_name: str, temporary_project_name: str
):
    version1 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v1-{_generate_random_suffix()}",
        project_name=temporary_project_name,
    )
    opik_client.get_prompts_client().batch_update_prompt_version_tags(
        version_ids=[version1.version_id],
        tags=_generate_random_tags(),
    )
    version2 = opik_client.create_prompt(
        name=prompt_name,
        prompt=f"Template v2-{_generate_random_suffix()}",
        project_name=temporary_project_name,
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

    history = opik_client.get_prompt_history(
        name=prompt_name, project_name=temporary_project_name
    )
    assert len(history) == 2
    v1_in_history = next(
        (v for v in history if v.version_id == version1.version_id), None
    )
    v2_in_history = next(
        (v for v in history if v.version_id == version2.version_id), None
    )
    assert set(v1_in_history.tags) == set(new_tags)
    assert set(v2_in_history.tags) == set(new_tags)


def test_prompt__update_version_tags__clear_with_empty_array(
    opik_client: opik.Opik, prompt_name: str
):
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
    opik_client: opik.Opik, prompt_name: str, merge_param
):
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


def test_prompt__update_version_tags__merge_mode(
    opik_client: opik.Opik, prompt_name: str
):
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


def test_chat_prompt__update_version_tags(opik_client: opik.Opik, prompt_name: str):
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


def test_prompt__auto_inject_into_trace__happyflow(
    opik_client: opik.Opik, prompt_name: str
):
    """Fetching text and chat prompts inside @track auto-injects opik_prompts into trace metadata."""
    # Two prompts needed (text + chat) — derive from the unique fixture name.
    text_prompt_name = f"{prompt_name}-text"
    chat_prompt_name = f"{prompt_name}-chat"

    opik_client.create_prompt(name=text_prompt_name, prompt="Summarize: {{text}}")
    opik_client.create_chat_prompt(
        name=chat_prompt_name,
        messages=[
            {"role": "system", "content": "You are helpful"},
            {"role": "user", "content": "Help with {{task}}"},
        ],
    )

    get_global_cache().clear()

    trace_id_storage = {}

    @opik.track()
    def my_tracked_fn():
        trace_id_storage["id"] = opik_context.get_current_trace_data().id
        opik_client.get_prompt(name=text_prompt_name)
        opik_client.get_chat_prompt(name=chat_prompt_name)
        return "done"

    my_tracked_fn()
    opik.flush_tracker()

    trace = opik_client.get_trace_content(id=trace_id_storage["id"])
    metadata = trace.metadata or {}
    opik_prompts = metadata.get("opik_prompts")

    assert opik_prompts is not None, "Expected opik_prompts in trace metadata"
    assert len(opik_prompts) == 2, f"Expected 2 prompt entries, got {len(opik_prompts)}"

    text_entry = next(e for e in opik_prompts if e["name"] == text_prompt_name)
    verifiers.verify_opik_prompt_entry(
        text_entry, name=text_prompt_name, template_structure="text"
    )

    chat_entry = next(e for e in opik_prompts if e["name"] == chat_prompt_name)
    verifiers.verify_opik_prompt_entry(
        chat_entry, name=chat_prompt_name, template_structure="chat"
    )


def _create_mask_version(
    opik_client: opik.Opik, prompt_name: str, prompt_id: str, template: str
) -> PromptVersionDetail:
    """Create a mask version for an existing prompt via the REST API."""
    return opik_client.rest_client.prompts.create_prompt_version(
        name=prompt_name,
        version=PromptVersionDetail(
            template=template,
            prompt_id=prompt_id,
            version_type="mask",
        ),
    )


def test_prompt__mask_get_prompt__returns_masked_template(
    opik_client: opik.Opik, prompt_name: str
):
    """When a mask context is active, get_prompt returns the mask overlay instead of the original.
    Mask versions must not appear in get_prompt_history."""
    original_template = f"original-{_generate_random_suffix()}"
    mask_template = f"masked-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(name=prompt_name, prompt=original_template)
    prompt_id = prompt.__internal_api__prompt_id__

    mask_version = _create_mask_version(
        opik_client, prompt_name, prompt_id, mask_template
    )

    versions = opik_client.get_prompt_history(name=prompt_name)
    version_ids = [v.__internal_api__version_id__ for v in versions]
    assert mask_version.id not in version_ids

    get_global_cache().clear()

    masks = {prompt_id: mask_version.id}
    with prompt_mask_context_module.prompt_mask_context(masks):
        masked_prompt = opik_client.get_prompt(name=prompt_name)

    assert masked_prompt is not None
    assert masked_prompt.prompt == mask_template
    assert masked_prompt.__internal_api__version_id__ == mask_version.id

    get_global_cache().clear()
    unmasked = opik_client.get_prompt(name=prompt_name)
    assert unmasked is not None
    assert unmasked.prompt == original_template


def test_prompt__mask_chat_prompt__returns_masked_messages(
    opik_client: opik.Opik, prompt_name: str
):
    """Mask context also works for chat prompts."""
    original_messages = [
        {"role": "user", "content": f"original-{_generate_random_suffix()}"}
    ]
    mask_messages = [
        {"role": "system", "content": f"masked-{_generate_random_suffix()}"}
    ]

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name, messages=original_messages
    )
    prompt_id = chat_prompt.__internal_api__prompt_id__

    mask_version = _create_mask_version(
        opik_client, prompt_name, prompt_id, json.dumps(mask_messages)
    )

    get_global_cache().clear()

    masks = {prompt_id: mask_version.id}
    with prompt_mask_context_module.prompt_mask_context(masks):
        masked_chat = opik_client.get_chat_prompt(name=prompt_name)

    assert masked_chat is not None
    assert masked_chat.template == mask_messages
    assert masked_chat.__internal_api__version_id__ == mask_version.id

    get_global_cache().clear()
    unmasked = opik_client.get_chat_prompt(name=prompt_name)
    assert unmasked is not None
    assert unmasked.template == original_messages
