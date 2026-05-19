"""End-to-end tests for the prompt ``environment`` feature.

Covers the user-facing surface added on top of the backend's per-version
environment ownership: ``create_prompt(environment=...)``,
``Opik.set_prompt_environment(name, ...)``, and ``get_prompt(name=..., environment=...)``.
"""

import uuid

import pytest

import opik
from opik.rest_api import core as rest_api_core


def _generate_random_suffix() -> str:
    return str(uuid.uuid4())[-6:]


def _generate_random_prompt_name() -> str:
    return f"prompt-env-{_generate_random_suffix()}"


def _register_env(opik_client: opik.Opik, name: str) -> str:
    """Create the environment in the workspace registry and return its name."""
    opik_client.rest_client.environments.create_environment(name=name)
    return name


@pytest.fixture
def second_environment_name(opik_client: opik.Opik):
    """A unique second environment for tests that need two; deleted on teardown.

    The shared ``environment_name`` fixture in ``conftest.py`` covers the
    single-env case; this complements it for tests that exercise ownership
    moves between two environments. Cleanup is best-effort — ``delete_environment``
    is a no-op if the test bailed before creating it."""
    name = f"e2e-tests-environment-{_generate_random_suffix()}"
    yield name
    try:
        opik_client.delete_environment(name)
    except rest_api_core.ApiError:
        pass


def test_create_prompt__with_environment__sets_ownership(
    opik_client: opik.Opik, environment_name: str
):
    _register_env(opik_client, environment_name)
    prompt_name = _generate_random_prompt_name()
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        environment=environment_name,
    )

    assert prompt.environment == environment_name

    refreshed = opik_client.get_prompt(name=prompt_name, no_cache=True)
    assert refreshed is not None
    assert refreshed.environment == environment_name


def test_set_environment__moves_ownership__visible_after_refetch(
    opik_client: opik.Opik, environment_name: str, second_environment_name: str
):
    _register_env(opik_client, environment_name)
    _register_env(opik_client, second_environment_name)
    prompt_name = _generate_random_prompt_name()
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        environment=environment_name,
    )
    assert prompt.environment == environment_name

    opik_client.set_prompt_environment(prompt.name, second_environment_name)

    refreshed = opik_client.get_prompt(name=prompt_name, no_cache=True)
    assert refreshed is not None
    assert refreshed.environment == second_environment_name


def test_set_environment__none__clears_ownership(
    opik_client: opik.Opik, environment_name: str
):
    _register_env(opik_client, environment_name)
    prompt_name = _generate_random_prompt_name()
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt=prompt_template,
        environment=environment_name,
    )

    opik_client.set_prompt_environment(prompt.name, None)

    refreshed = opik_client.get_prompt(name=prompt_name, no_cache=True)
    assert refreshed is not None
    assert refreshed.environment is None


def test_get_prompt__by_environment__resolves_to_owning_version(
    opik_client: opik.Opik, environment_name: str
):
    _register_env(opik_client, environment_name)
    prompt_name = _generate_random_prompt_name()
    first_template = f"first-text-{_generate_random_suffix()}"
    second_template = f"second-text-{_generate_random_suffix()}"

    first_version = opik_client.create_prompt(
        name=prompt_name,
        prompt=first_template,
        environment=environment_name,
    )

    second_version = opik_client.create_prompt(
        name=prompt_name,
        prompt=second_template,
    )
    assert second_version.commit != first_version.commit

    resolved = opik_client.get_prompt(
        name=prompt_name, environment=environment_name, no_cache=True
    )
    assert resolved is not None
    assert resolved.commit == first_version.commit
    assert resolved.prompt == first_template
    assert resolved.environment == environment_name


def test_set_prompt_environment__targets_specific_version(
    opik_client: opik.Opik, environment_name: str
):
    _register_env(opik_client, environment_name)
    prompt_name = _generate_random_prompt_name()
    first_template = f"first-text-{_generate_random_suffix()}"
    second_template = f"second-text-{_generate_random_suffix()}"

    v1 = opik_client.create_prompt(name=prompt_name, prompt=first_template)
    v1_commit = v1.commit

    v2 = opik_client.create_prompt(name=prompt_name, prompt=second_template)
    assert v2.commit != v1_commit

    opik_client.set_prompt_environment(prompt_name, environment_name, commit=v1_commit)

    resolved = opik_client.get_prompt(
        name=prompt_name, environment=environment_name, no_cache=True
    )
    assert resolved is not None
    assert resolved.commit == v1_commit


def test_set_environment__unknown_environment__raises_404(opik_client: opik.Opik):
    prompt_name = _generate_random_prompt_name()
    prompt_template = f"some-prompt-text-{_generate_random_suffix()}"

    prompt = opik_client.create_prompt(name=prompt_name, prompt=prompt_template)

    with pytest.raises(rest_api_core.ApiError) as exc_info:
        opik_client.set_prompt_environment(
            prompt.name, f"missing-env-{_generate_random_suffix()}"
        )

    assert exc_info.value.status_code == 404


def test_create_chat_prompt__with_environment__sets_ownership(
    opik_client: opik.Opik, environment_name: str
):
    _register_env(opik_client, environment_name)
    prompt_name = _generate_random_prompt_name()
    messages = [{"role": "user", "content": f"hi {_generate_random_suffix()}"}]

    chat_prompt = opik_client.create_chat_prompt(
        name=prompt_name,
        messages=messages,
        environment=environment_name,
    )

    assert chat_prompt.environment == environment_name

    refreshed = opik_client.get_chat_prompt(name=prompt_name, no_cache=True)
    assert refreshed is not None
    assert refreshed.environment == environment_name
