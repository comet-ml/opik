import uuid
from dataclasses import field
from typing import Dict, List

import pytest
import opik
from opik.api_objects.agent_config.cache import clear_shared_caches
from opik.api_objects.agent_config.decorator import _get_cached_config
from opik.rest_api import core as rest_api_core
from opik.rest_api.types.agent_config_env import AgentConfigEnv


def _unique_project_name() -> str:
    return f"e2e-agent-config-{str(uuid.uuid4())[:8]}"


@pytest.fixture(autouse=True)
def clear_caches_after_test():
    yield
    clear_shared_caches()


@pytest.fixture
def project_name(opik_client: opik.Opik):
    name = _unique_project_name()
    yield name
    try:
        project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
        opik_client.rest_client.projects.delete_project_by_id(project_id)
    except rest_api_core.ApiError:
        pass


# ---------------------------------------------------------------------------
# Decorator tests
# ---------------------------------------------------------------------------


def test_agent_config_decorator__all_primitive_and_collection_types__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    @opik.agent_config(project=project_name)
    class AllTypesConfig:
        temperature: float = 0.7
        max_tokens: int = 512
        model_name: str = "gpt-4"
        use_tools: bool = True
        stop_sequences: List[str] = field(default_factory=lambda: ["<|end|>", "\n\n"])
        sampling_params: Dict[str, float] = field(
            default_factory=lambda: {"top_p": 0.9, "top_k": 0.0}
        )

    instance = AllTypesConfig()

    assert isinstance(instance.temperature, float)
    assert instance.temperature == pytest.approx(0.7)
    assert instance.max_tokens == 512
    assert instance.model_name == "gpt-4"
    assert instance.use_tools is True
    assert instance.stop_sequences == ["<|end|>", "\n\n"]
    assert instance.sampling_params == {"top_p": 0.9, "top_k": 0.0}


def test_agent_config_decorator__backend_overrides_local_defaults__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    # First instantiation creates the blueprint with temperature=0.3
    @opik.agent_config(project=project_name)
    class MyConfig:
        temperature: float = 0.3
        model: str = "gpt-3.5"

    first = MyConfig()
    assert first.temperature == pytest.approx(0.3)
    assert first.model == "gpt-3.5"

    # Clear cache so the second instantiation re-fetches from backend
    clear_shared_caches()

    # Second instantiation with different local defaults — backend values must win
    @opik.agent_config(project=project_name)
    class MyConfig:  # type: ignore[no-redef]
        temperature: float = 0.99
        model: str = "gpt-4"

    second = MyConfig()

    assert second.temperature == pytest.approx(0.3)
    assert second.model == "gpt-3.5"


# ---------------------------------------------------------------------------
# AgentConfig + Blueprint programmatic tests
# ---------------------------------------------------------------------------


def test_agent_config__programmatic_create_and_get__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    agent_config = opik_client.get_agent_config(project_name=project_name)

    bp_v1 = agent_config.create_blueprint(
        parameters={"temperature": 0.5, "max_tokens": 100},
        description="v1",
    )
    assert bp_v1.id is not None
    assert bp_v1["temperature"] == 0.5
    assert bp_v1["max_tokens"] == 100

    bp_v2 = agent_config.create_blueprint(
        parameters={"temperature": 0.8, "max_tokens": 200},
        description="v2",
    )
    assert bp_v2.id is not None
    assert bp_v2.id != bp_v1.id
    assert bp_v2["temperature"] == 0.8
    assert bp_v2["max_tokens"] == 200

    latest = agent_config.get_blueprint()
    assert latest["temperature"] == 0.8
    assert latest["max_tokens"] == 200


def test_agent_config__get_blueprint_by_env_tag__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    agent_config = opik_client.get_agent_config(project_name=project_name)

    bp = agent_config.create_blueprint(parameters={"temperature": 0.4})
    blueprint_id = bp.id
    assert blueprint_id is not None

    # Create another blueprint to ensure we're not pointing at the latest
    bp = agent_config.create_blueprint(parameters={"temperature": 0.4})

    project_id = opik_client.rest_client.projects.retrieve_project(name=project_name).id
    opik_client.rest_client.agent_configs.create_or_update_envs(
        project_id=project_id,
        envs=[AgentConfigEnv(env_name="prod", blueprint_id=blueprint_id)],
    )

    # We fetch the correct blueprint by env
    prod_bp = agent_config.get_blueprint(env="prod")
    assert prod_bp.id == blueprint_id
    assert prod_bp["temperature"] == 0.4


def test_agent_config__mask_creation_and_application__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    agent_config = opik_client.get_agent_config(project_name=project_name)

    base_bp = agent_config.create_blueprint(
        parameters={"temperature": 0.7, "max_tokens": 256, "model": "gpt-4"},
    )

    mask_id = agent_config.create_mask(
        parameters={"temperature": 0.2},
        description="low-temperature variant",
    )

    assert isinstance(mask_id, str)
    assert mask_id != base_bp.id

    assert base_bp["temperature"] == 0.7
    assert base_bp["max_tokens"] == 256

    masked = agent_config.get_blueprint(mask_id=mask_id)
    assert masked["temperature"] == 0.2


def test_agent_config__multiple_blueprints_each_produce_new_id__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    agent_config = opik_client.get_agent_config(project_name=project_name)

    bp_v1 = agent_config.create_blueprint(
        parameters={"temperature": 0.1, "max_tokens": 10}, description="v1"
    )
    id_v1 = bp_v1.id
    assert id_v1 is not None

    bp_v2 = agent_config.create_blueprint(
        parameters={"temperature": 0.2, "max_tokens": 20}, description="v2"
    )
    id_v2 = bp_v2.id
    assert id_v2 is not None
    assert id_v2 != id_v1
    assert bp_v2["temperature"] == 0.2
    assert bp_v2["max_tokens"] == 20

    bp_v3 = agent_config.create_blueprint(
        parameters={"temperature": 0.3, "max_tokens": 30}, description="v3"
    )
    id_v3 = bp_v3.id
    assert id_v3 is not None
    assert id_v3 != id_v2
    assert bp_v3["temperature"] == 0.3
    assert bp_v3["max_tokens"] == 30

    latest = agent_config.get_blueprint()
    assert latest.id == id_v3
    assert latest["temperature"] == 0.3


def test_agent_config__mask_id_pin__does_not_update_backend__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    @opik.agent_config(project=project_name)
    class BaseConfig:
        temperature: float = 0.5

    BaseConfig()

    agent_config = opik_client.get_agent_config(project_name=project_name)

    agent_config.create_blueprint(
        parameters={"BaseConfig.temperature": 0.5},
    )
    mask_id = agent_config.create_mask(parameters={"BaseConfig.temperature": 0.1})
    assert mask_id is not None

    pre_read_latest = agent_config.get_blueprint()

    clear_shared_caches()

    @opik.agent_config(project=project_name, mask_id=mask_id)
    class BaseConfig:  # type: ignore[no-redef]
        temperature: float = 0.99
        name: str = "placeholder"

    instance = BaseConfig()
    assert instance.temperature == pytest.approx(0.1)

    post_read_latest = agent_config.get_blueprint()
    assert post_read_latest.id == pre_read_latest.id


def test_agent_config__env_pin__does_not_update_backend__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    @opik.agent_config(project=project_name)
    class EnvConfig:
        temperature: float = 0.4

    base_instance = EnvConfig()
    base_blueprint_id = _get_cached_config(base_instance).blueprint_id
    assert base_blueprint_id is not None

    project_id = opik_client.rest_client.projects.retrieve_project(name=project_name).id
    opik_client.rest_client.agent_configs.create_or_update_envs(
        project_id=project_id,
        envs=[AgentConfigEnv(env_name="staging", blueprint_id=base_blueprint_id)],
    )

    clear_shared_caches()

    @opik.agent_config(project=project_name, env="staging")
    class EnvConfig:  # type: ignore[no-redef]
        temperature: float = 0.99
        name: str = "placeholder"

    instance = EnvConfig()
    assert instance.temperature == pytest.approx(0.4)

    agent_config = opik_client.get_agent_config(project_name=project_name)
    latest = agent_config.get_blueprint()
    assert latest.id == base_blueprint_id


def test_agent_config__env_pin__second_instantiation_uses_backend_value__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Backend is source of truth: re-instantiating with a different constructor arg
    should return the value from the first registration, not the new arg."""

    @opik.agent_config(project=project_name, env="prod")
    class MyAgentConfig:
        my_param: int
        name: str
        temperature: float = 0.8

    # First instantiation: registers with backend
    config = MyAgentConfig(my_param=11, name="Steve")
    assert config.name == "Steve"
    assert config.temperature == pytest.approx(0.8)

    clear_shared_caches()

    # Second instantiation: backend is now source of truth, "Bob" should be ignored
    @opik.agent_config(project=project_name, env="prod")
    class MyAgentConfig:  # type: ignore[no-redef]
        my_param: int
        name: str
        temperature: float = 0.8

    config = MyAgentConfig(my_param=10, name="Bob")
    assert config.name == "Steve"
    assert config.temperature == pytest.approx(0.8)


def test_agent_config__multiple_mask_updates__each_produce_distinct_mask_id__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    agent_config = opik_client.get_agent_config(project_name=project_name)

    agent_config.create_blueprint(
        parameters={"temperature": 0.7, "model": "gpt-4"},
    )

    mask_id_a = agent_config.create_mask(
        parameters={"temperature": 0.1}, description="low-temp"
    )
    mask_id_b = agent_config.create_mask(
        parameters={"temperature": 0.9}, description="high-temp"
    )

    assert mask_id_a is not None
    assert mask_id_b is not None
    assert mask_id_a != mask_id_b

    fetched_a = agent_config.get_blueprint(mask_id=mask_id_a)
    fetched_b = agent_config.get_blueprint(mask_id=mask_id_b)
    # Blueprint ids are the same, but masks are applied on top
    assert fetched_a.id == fetched_b.id
    assert fetched_a["temperature"] == 0.1
    assert fetched_b["temperature"] == 0.9
