import uuid

from opik import Prompt


def test_prompt__create__happyflow(opik_client):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"
    prompt_template = f"some-prompt-text-{unique_identifier}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
    )

    assert prompt.name == prompt_name
    assert prompt.prompt == prompt_template
    assert prompt.__internal_api__version_id__ is not None
    assert prompt.__internal_api__prompt_id__ is not None
    assert prompt.commit is not None


def test_prompt__create_new_version__happyflow(opik_client):
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


def test_prompt__do_not_create_new_version_with_the_same_template(opik_client):
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


def test_prompt__get_by_name__happyflow(opik_client):
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


def test_prompt__get__not_exists(opik_client):
    unique_identifier = str(uuid.uuid4())[-6:]

    prompt_name = f"some-prompt-name-{unique_identifier}"

    prompt = opik_client.get_prompt(prompt_name)

    assert prompt is None


def test_prompt__initialize_class_instance(opik_client):
    unique_identifier = str(uuid.uuid4())[-6:]
    template = "Hello, {name} from {place}! Nice to meet you, {name}."

    prompt = Prompt(name=f"test-{unique_identifier}", prompt=template)
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


def test_prompt__format(opik_client):
    unique_identifier = str(uuid.uuid4())[-6:]
    template = "Hello, {{name}} from {{place}}! Nice to meet you, {{name}}."

    prompt = Prompt(name=f"test-{unique_identifier}", prompt=template)

    result = prompt.format()
    assert result == template

    result = prompt.format(name="John")
    assert result == "Hello, John from {{place}}! Nice to meet you, John."

    result = prompt.format(name="John", place="The Earth")
    assert result == "Hello, John from The Earth! Nice to meet you, John."

    result = prompt.format(name="John", place="The Earth", unexisting_key="value")
    assert result == "Hello, John from The Earth! Nice to meet you, John."

    assert prompt.prompt == template
