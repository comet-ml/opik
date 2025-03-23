import uuid
import opik


def test_prompt__create__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        metadata={"outer-key": {"inner-key": "inner-value"}},
    )

    assert prompt.name == prompt_name
    assert prompt.prompt == prompt_template
    assert prompt.__internal_api__version_id__ is not None
    assert prompt.__internal_api__prompt_id__ is not None
    assert prompt.commit is not None
    assert prompt.metadata == {"outer-key": {"inner-key": "inner-value"}}


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

    assert new_prompt.name == prompt.name
    assert new_prompt.prompt == prompt_template_new
    assert (
        new_prompt.__internal_api__version_id__ != prompt.__internal_api__version_id__
    )
    assert new_prompt.__internal_api__prompt_id__ == prompt.__internal_api__prompt_id__
    assert new_prompt.commit != prompt.commit


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

    assert new_prompt.name == prompt.name
    assert new_prompt.prompt == prompt.prompt
    assert (
        new_prompt.__internal_api__version_id__ == prompt.__internal_api__version_id__
    )
    assert new_prompt.__internal_api__prompt_id__ == prompt.__internal_api__prompt_id__
    assert new_prompt.commit == prompt.commit


def test_prompt__get_by_name__happyflow(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

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

    # ASSERTIONS
    p1 = opik_client.get_prompt(name=prompt.name)

    assert p1.name == new_prompt.name
    assert p1.prompt == new_prompt.prompt
    assert p1.__internal_api__version_id__ == new_prompt.__internal_api__version_id__
    assert p1.__internal_api__prompt_id__ == new_prompt.__internal_api__prompt_id__
    assert p1.commit == new_prompt.commit

    p2 = opik_client.get_prompt(name=prompt.name, commit=prompt.commit)

    assert p2.name == prompt.name
    assert p2.prompt == prompt.prompt
    assert p2.__internal_api__version_id__ == prompt.__internal_api__version_id__
    assert p2.__internal_api__prompt_id__ == prompt.__internal_api__prompt_id__
    assert p2.commit == prompt.commit


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

    assert prompt.name == prompt_from_api.name
    assert prompt.prompt == prompt_from_api.prompt
    assert (
        prompt.__internal_api__version_id__
        == prompt_from_api.__internal_api__version_id__
    )
    assert (
        prompt.__internal_api__prompt_id__
        == prompt_from_api.__internal_api__prompt_id__
    )
    assert prompt.commit == prompt_from_api.commit


def test_prompt__format():
    unique_identifier = str(uuid.uuid4())[-6:]
    template = "Hello, {{name}} from {{place}}! Nice to meet you, {{name}}."

    prompt = opik.Prompt(name=f"test-{unique_identifier}", prompt=template)

    result = prompt.format(name="John", place="The Earth")
    assert result == "Hello, John from The Earth! Nice to meet you, John."

    assert prompt.prompt == template


def test_prompt__create_with_default_type(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    assert prompt.type == 'mustache'  # Verify default type
    assert prompt.name == prompt_name
    assert prompt.prompt == prompt_template


def test_prompt__create_with_custom_type(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        type = "jinja2"
    )

    assert prompt.type == 'jinja2'
    assert prompt.name == prompt_name
    assert prompt.prompt == prompt_template


def test_prompt__type_persists_in_get(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    # Create prompt with custom type
    created_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        type='jinja2'
    )

    # Get the prompt and verify type persists
    retrieved_prompt = opik_client.get_prompt(name=prompt_name)
    assert retrieved_prompt is not None
    assert retrieved_prompt.type == 'jinja2'
    assert retrieved_prompt.name == prompt_name
    assert retrieved_prompt.prompt == prompt_template


def test_prompt__type_in_new_version(opik_client: opik.Opik):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    # Create initial version with default type
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )
    assert prompt.type == 'mustache'

    # Create new version with different type
    new_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template + "-v2",
        type='jinja2'
    )

    assert new_prompt.type == 'jinja2'
    assert new_prompt.__internal_api__prompt_id__ == prompt.__internal_api__prompt_id__
    assert new_prompt.__internal_api__version_id__ != prompt.__internal_api__version_id__
