import uuid
import pytest
import opik
from opik.api_objects.prompt import PromptType
from opik.rest_api import core as rest_api_core
from . import verifiers


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

    templates = {p.prompt for p in prompts}
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

    names = set(p.name for p in results)
    assert len(results) == 2
    assert names == set([prompt_name_1, prompt_name_2])


def test_prompt__template_structure_immutable__error(opik_client: opik.Opik):
    """Test that template_structure is immutable after prompt creation."""
    unique_identifier = str(uuid.uuid4())[-6:]
    prompt_name = f"test-immutable-structure-{unique_identifier}"

    # Create initial string prompt
    string_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="This is a string prompt: {{variable}}",
    )

    # Verify string prompt was created
    verifiers.verify_prompt_version(
        string_prompt,
        name=prompt_name,
        template="This is a string prompt: {{variable}}",
    )

    # Attempt to create a chat prompt version with the same name should fail
    with pytest.raises(rest_api_core.ApiError) as exc_info:
        opik_client.create_chat_prompt(
            name=prompt_name,
            messages=[
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": "Hello!"},
            ],
        )

    # Verify the error message contains relevant information
    assert exc_info.value.status_code == 400
    assert (
        "template structure mismatch" in str(exc_info.value).lower()
        or "template_structure" in str(exc_info.value).lower()
    )


def test_chat_prompt__template_structure_immutable__error(opik_client: opik.Opik):
    """Test that template_structure is immutable for chat prompts."""
    unique_identifier = str(uuid.uuid4())[-6:]
    prompt_name = f"test-immutable-chat-structure-{unique_identifier}"

    # Create initial chat prompt
    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Hello!"},
        ],
    )

    # Verify chat prompt was created
    verifiers.verify_chat_prompt_version(
        chat_prompt,
        name=prompt_name,
        messages=[
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Hello!"},
        ],
    )

    # Attempt to create a string prompt version with the same name should fail
    with pytest.raises(rest_api_core.ApiError) as exc_info:
        opik_client.create_prompt(
            name=prompt_name,
            prompt="This is a string prompt: {{variable}}",
        )

    # Verify the error message contains relevant information
    assert exc_info.value.status_code == 400
    assert (
        "template structure mismatch" in str(exc_info.value).lower()
        or "template_structure" in str(exc_info.value).lower()
        or "chat prompt" in str(exc_info.value).lower()
    )


def test_get_prompt__string_prompt__returns_prompt(opik_client: opik.Opik):
    """Test that get_prompt() returns a Prompt object for string prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"string-prompt-{unique_id}"

    # Create a string prompt
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
    """Test that get_prompt() returns None for chat prompts (type mismatch)."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-{unique_id}"

    # Create a chat prompt
    opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[
            {"role": "system", "content": "You are helpful"},
            {"role": "user", "content": "Hello"},
        ],
    )

    # Try to retrieve it with get_prompt() - should return None due to type mismatch
    retrieved_prompt = opik_client.get_prompt(name=prompt_name)
    assert retrieved_prompt is None


def test_get_prompt_history__string_prompt__returns_prompts(opik_client: opik.Opik):
    """Test that get_prompt_history() returns Prompt objects for string prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"string-prompt-history-{unique_id}"

    # Create multiple versions of a string prompt
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
    """Test that get_prompt_history() returns empty list for chat prompts (type mismatch)."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"chat-prompt-history-{unique_id}"

    # Create a chat prompt
    opik_client.create_chat_prompt(
        name=prompt_name,
        messages=[{"role": "user", "content": "Hello"}],
    )

    # Try to get history with get_prompt_history() - should return empty list
    history = opik_client.get_prompt_history(name=prompt_name)
    assert len(history) == 0


def test_search_prompts__only_returns_string_prompts(opik_client: opik.Opik):
    """Test that search_prompts() only returns string prompts, not chat prompts."""
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

    # Should only return string prompts
    assert len(results) == 2
    assert all(isinstance(p, opik.Prompt) for p in results)

    result_names = {p.name for p in results}
    assert string_prompt_1.name in result_names
    assert string_prompt_2.name in result_names
    assert chat_prompt_1.name not in result_names
    assert chat_prompt_2.name not in result_names


def test_get_prompt__with_commit__string_prompt(opik_client: opik.Opik):
    """Test that get_prompt() with commit works for string prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"string-prompt-commit-{unique_id}"

    # Create multiple versions
    v1 = opik_client.create_prompt(name=prompt_name, prompt="Version 1")
    v2 = opik_client.create_prompt(name=prompt_name, prompt="Version 2")

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
