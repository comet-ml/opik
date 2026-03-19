import uuid
from typing import Annotated

import pytest
import opik
from opik import opik_context
from opik.api_objects.agent_config.cache import get_global_registry
from opik.api_objects.agent_config.config import AgentConfigManager
from opik.api_objects.agent_config.context import agent_config_context

from opik.api_objects.prompt.text.prompt import Prompt
from opik.rest_api import core as rest_api_core
from . import verifiers
from ..testlib import ANY_DICT, ANY_BUT_NONE


def _unique_project_name() -> str:
    return f"e2e-agent-config-{str(uuid.uuid4())[:8]}"


@pytest.fixture(autouse=True)
def clear_caches_after_test():
    yield
    get_global_registry().clear()


@pytest.fixture
def project_name(opik_client: opik.Opik):
    name = _unique_project_name()
    yield name
    try:
        project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
        opik_client.rest_client.projects.delete_project_by_id(project_id)
    except rest_api_core.ApiError:
        pass


def test_multi_class_and_field_removal_dedup__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Publishing a second config class or removing a field must not create duplicate versions."""

    class ConfigA(opik.AgentConfig):
        temperature: float
        model: str

    class ConfigB(opik.AgentConfig):
        retries: int

    # Publish both classes into the same project — each creates one new version.
    opik_client.create_agent_config_version(
        ConfigA(temperature=0.5, model="gpt-4"), project_name=project_name
    )
    v_after_b = opik_client.create_agent_config_version(
        ConfigB(retries=3), project_name=project_name
    )

    # Re-publishing ConfigA with the same values must be a no-op. The latest blueprint
    # now contains both ConfigA and ConfigB keys, but ConfigA values are unchanged so
    # it should return the current latest version rather than creating a new one.
    get_global_registry().clear()
    v_a_again = opik_client.create_agent_config_version(
        ConfigA(temperature=0.5, model="gpt-4"), project_name=project_name
    )
    assert v_a_again == v_after_b, (
        "same values after another class was added should be a no-op"
    )

    # Publish ConfigA with the model field removed locally — remaining value unchanged.
    class ConfigA(opik.AgentConfig):  # type: ignore[no-redef]
        temperature: float

    get_global_registry().clear()
    v_a_reduced = opik_client.create_agent_config_version(
        ConfigA(temperature=0.5), project_name=project_name
    )
    assert v_a_reduced == v_after_b, (
        "removing a field whose value is unchanged should be a no-op"
    )

    # Confirm history has exactly 2 entries (one per class, no duplicates).
    project_id = opik_client.rest_client.projects.retrieve_project(name=project_name).id
    history = opik_client.rest_client.agent_configs.get_blueprint_history(
        project_id=project_id
    ).content
    assert len(history) == 2


def test_publish_version_and_retrieve__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Core lifecycle: publish, dedup, new version, get by latest / version name / env."""

    class MyConfig(opik.AgentConfig):
        temperature: Annotated[float, "Sampling temperature"]
        model: str

    # Publish v1 and verify the version name comes back.
    v1_name = opik_client.create_agent_config_version(
        MyConfig(temperature=0.5, model="gpt-3.5"), project_name=project_name
    )
    assert isinstance(v1_name, str) and v1_name != ""

    # Publishing the same values again must be a no-op (1 entry in history).
    get_global_registry().clear()
    opik_client.create_agent_config_version(
        MyConfig(temperature=0.5, model="gpt-3.5"), project_name=project_name
    )
    project_id = opik_client.rest_client.projects.retrieve_project(name=project_name).id
    assert (
        len(
            opik_client.rest_client.agent_configs.get_blueprint_history(
                project_id=project_id
            ).content
        )
        == 1
    )

    # Publishing different values creates a new version.
    get_global_registry().clear()
    v2_name = opik_client.create_agent_config_version(
        MyConfig(temperature=0.8, model="gpt-4"), project_name=project_name
    )
    assert v2_name != v1_name

    # latest=True returns v2.
    get_global_registry().clear()
    latest = opik_client.get_agent_config(
        fallback=MyConfig(temperature=0.0, model="fallback"),
        project_name=project_name,
        latest=True,
    )
    assert latest.temperature == pytest.approx(0.8)
    assert latest.model == "gpt-4"

    # version= by name returns v1.
    get_global_registry().clear()
    by_name = opik_client.get_agent_config(
        fallback=MyConfig(temperature=0.0, model="fallback"),
        project_name=project_name,
        version=v1_name,
    )
    assert by_name.temperature == pytest.approx(0.5)

    # Deploy v1 to prod; env= fetch returns v1 despite v2 being latest.
    get_global_registry().clear()
    v1_cfg = opik_client.get_agent_config(
        fallback=MyConfig(temperature=0.0, model="fallback"),
        project_name=project_name,
        version=v1_name,
    )
    v1_cfg.deploy_to("prod")

    get_global_registry().clear()
    by_env = opik_client.get_agent_config(
        fallback=MyConfig(temperature=0.0, model="fallback"),
        project_name=project_name,
        env="prod",
    )
    assert by_env.temperature == pytest.approx(0.5)


def test_prompt_field_and_trace_metadata__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Prompt-typed field survives the roundtrip; field access inside a tracked
    function injects agent_configuration into trace and span metadata."""

    prompt_name = f"e2e-prompt-{uuid.uuid4().hex[:8]}"
    prompt_v1 = opik_client.create_prompt(name=prompt_name, prompt="Hello v1")

    class PromptConfig(opik.AgentConfig):
        system_prompt: Prompt
        temperature: float

    opik_client.create_agent_config_version(
        PromptConfig(system_prompt=prompt_v1, temperature=0.3),
        project_name=project_name,
    )

    get_global_registry().clear()

    result = opik_client.get_agent_config(
        fallback=PromptConfig(system_prompt=prompt_v1, temperature=0.0),
        project_name=project_name,
        latest=True,
    )

    # Prompt field roundtrip.
    assert isinstance(result.system_prompt, Prompt)
    assert result.system_prompt.version_id == prompt_v1.version_id

    # Field access inside @opik.track must inject agent_configuration metadata.
    id_storage = {}

    @opik.track(project_name=project_name)
    def run():
        id_storage["trace_id"] = opik_context.get_current_trace_data().id
        id_storage["span_id"] = opik_context.get_current_span_data().id
        _ = result.temperature

    run()
    opik.flush_tracker()

    expected_meta = {
        "_blueprint_id": ANY_BUT_NONE,
        "blueprint_version": ANY_BUT_NONE,
        "values": ANY_DICT,
    }
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=id_storage["trace_id"],
        metadata={"agent_configuration": ANY_DICT.containing(expected_meta)},
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=id_storage["span_id"],
        trace_id=id_storage["trace_id"],
        parent_span_id=None,
        metadata={"agent_configuration": ANY_DICT.containing(expected_meta)},
    )


def test_mask_overrides_config__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """A mask overrides selected fields while leaving untouched fields intact."""

    class MyConfig(opik.AgentConfig):
        temperature: float
        model: str

    opik_client.create_agent_config_version(
        MyConfig(temperature=0.5, model="gpt-4"), project_name=project_name
    )

    get_global_registry().clear()

    manager = AgentConfigManager(
        project_name=project_name,
        rest_client_=opik_client.rest_client,
    )
    mask_id = manager.create_mask(parameters={"MyConfig.temperature": 0.9})

    get_global_registry().clear()

    with agent_config_context(mask_id):
        result = opik_client.get_agent_config(
            fallback=MyConfig(temperature=0.0, model="fallback"),
            project_name=project_name,
            latest=True,
        )
        assert result.temperature == pytest.approx(0.9)
        assert result.model == "gpt-4"
