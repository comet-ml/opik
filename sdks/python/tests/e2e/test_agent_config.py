import uuid
from typing import Annotated, Optional

import pytest
import opik
from opik import opik_context
from opik.api_objects.agent_config.cache import get_global_registry
from opik.api_objects.agent_config.config import ConfigManager
from opik.api_objects.agent_config.context import agent_config_context

from opik.api_objects.prompt.text.prompt import Prompt
from opik.api_objects.prompt.chat.chat_prompt import ChatPrompt
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


def test_multi_class_publishes_store_only_sent_values__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Each published blueprint stores only the values that were sent — no carry-forward."""

    class ConfigA(opik.Config):
        temperature: float
        model: str

    class ConfigB(opik.Config):
        retries: int

    opik_client.create_config(
        ConfigA(temperature=0.5, model="gpt-4"), project_name=project_name
    )
    opik_client.create_config(ConfigB(retries=3), project_name=project_name)

    manager = ConfigManager(
        project_name=project_name,
        rest_client_=opik_client.rest_client,
    )
    latest = manager.get_blueprint()
    assert latest is not None
    assert sorted(latest.keys()) == ["retries"]

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

    class MyConfig(opik.Config):
        temperature: Annotated[float, "Sampling temperature"]
        model: str
        hint: Optional[str]

    # Publish v1 with hint=None and verify the version name comes back.
    v1_name = opik_client.create_config(
        MyConfig(temperature=0.5, model="gpt-3.5", hint=None), project_name=project_name
    )
    assert isinstance(v1_name, str) and v1_name != ""

    # Backend auto-tags the first blueprint as "prod" — verify without any manual deploy_to.
    get_global_registry().clear()

    @opik.track(project_name=project_name)
    def fetch_auto_prod():
        return opik_client.get_or_create_config(
            fallback=MyConfig(temperature=0.0, model="fallback", hint=None),
            project_name=project_name,
            env="prod",
        )

    auto_prod = fetch_auto_prod()
    assert auto_prod.temperature == pytest.approx(0.5)
    assert auto_prod.model == "gpt-3.5"

    # Publishing different values (hint filled in) creates a new version.
    get_global_registry().clear()
    v2_name = opik_client.create_config(
        MyConfig(temperature=0.8, model="gpt-4", hint="use chain-of-thought"),
        project_name=project_name,
    )
    assert v2_name != v1_name

    # version="latest" returns v2; hint is now a real value.
    get_global_registry().clear()

    @opik.track(project_name=project_name)
    def fetch_latest():
        return opik_client.get_or_create_config(
            fallback=MyConfig(temperature=0.0, model="fallback", hint=None),
            project_name=project_name,
            version="latest",
        )

    latest = fetch_latest()
    assert latest.temperature == pytest.approx(0.8)
    assert latest.model == "gpt-4"
    assert latest.hint == "use chain-of-thought"

    # version= by name returns v1; hint must be None as originally published.
    get_global_registry().clear()

    @opik.track(project_name=project_name)
    def fetch_by_name():
        return opik_client.get_or_create_config(
            fallback=MyConfig(temperature=0.0, model="fallback", hint=None),
            project_name=project_name,
            version=v1_name,
        )

    by_name = fetch_by_name()
    assert by_name.temperature == pytest.approx(0.5)
    assert by_name.hint is None

    # Deploy v1 to prod; env= fetch returns v1 despite v2 being latest.
    opik_client.set_config_env(project_name=project_name, version=v1_name, env="prod")
    get_global_registry().clear()

    @opik.track(project_name=project_name)
    def fetch_by_env():
        return opik_client.get_or_create_config(
            fallback=MyConfig(temperature=0.0, model="fallback", hint=None),
            project_name=project_name,
            env="prod",
        )

    by_env = fetch_by_env()
    assert by_env.temperature == pytest.approx(0.5)
    assert by_env.hint is None


def test_prompt_field_and_trace_metadata__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Prompt-typed and ChatPrompt-typed fields survive the roundtrip with the correct
    class; field access inside a tracked function injects agent_configuration into
    trace and span metadata."""

    prompt_name = f"e2e-prompt-{uuid.uuid4().hex[:8]}"
    chat_prompt_name = f"e2e-chat-prompt-{uuid.uuid4().hex[:8]}"

    prompt_v1 = opik_client.create_prompt(name=prompt_name, prompt="Hello v1")
    chat_prompt_v1 = opik_client.create_chat_prompt(
        name=chat_prompt_name,
        messages=[{"role": "user", "content": "Hi v1"}],
    )

    class PromptConfig(opik.Config):
        system_prompt: Prompt
        chat_template: ChatPrompt
        temperature: float

    opik_client.create_config(
        PromptConfig(
            system_prompt=prompt_v1,
            chat_template=chat_prompt_v1,
            temperature=0.3,
        ),
        project_name=project_name,
    )

    get_global_registry().clear()

    id_storage = {}

    @opik.track(project_name=project_name)
    def run():
        cfg = opik_client.get_or_create_config(
            fallback=PromptConfig(
                system_prompt=prompt_v1,
                chat_template=chat_prompt_v1,
                temperature=0.0,
            ),
            project_name=project_name,
            version="latest",
        )
        id_storage["trace_id"] = opik_context.get_current_trace_data().id
        id_storage["span_id"] = opik_context.get_current_span_data().id
        id_storage["system_prompt"] = cfg.system_prompt
        id_storage["system_prompt_version_id"] = cfg.system_prompt.version_id
        id_storage["chat_template"] = cfg.chat_template
        id_storage["chat_template_version_id"] = cfg.chat_template.version_id
        _ = cfg.temperature
        return cfg

    run()
    opik.flush_tracker()

    # Prompt field roundtrip — must come back as Prompt, not ChatPrompt.
    assert isinstance(id_storage["system_prompt"], Prompt)
    assert not isinstance(id_storage["system_prompt"], ChatPrompt)
    assert id_storage["system_prompt_version_id"] == prompt_v1.version_id

    # ChatPrompt field roundtrip — must come back as ChatPrompt, not plain Prompt.
    assert isinstance(id_storage["chat_template"], ChatPrompt)
    assert id_storage["chat_template_version_id"] == chat_prompt_v1.version_id

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

    class MyConfig(opik.Config):
        temperature: float
        model: str

    opik_client.create_config(
        MyConfig(temperature=0.5, model="gpt-4"), project_name=project_name
    )

    get_global_registry().clear()

    manager = ConfigManager(
        project_name=project_name,
        rest_client_=opik_client.rest_client,
    )
    mask_id = manager.create_mask(parameters={"temperature": 0.9})

    get_global_registry().clear()

    with agent_config_context(mask_id):

        @opik.track(project_name=project_name)
        def fetch_with_mask():
            return opik_client.get_or_create_config(
                fallback=MyConfig(temperature=0.0, model="fallback"),
                project_name=project_name,
                version="latest",
            )

        result = fetch_with_mask()
        assert result.temperature == pytest.approx(0.9)
        assert result.model == "gpt-4"
