import json
import uuid
import opik
from opik.api_objects.prompt import PromptType
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
