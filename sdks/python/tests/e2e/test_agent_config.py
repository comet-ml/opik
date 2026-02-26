import uuid
from dataclasses import dataclass, field
from typing import Dict, List

import pytest
import opik
from opik.api_objects.agent_config.config import AgentConfig
from opik.api_objects.agent_config.cache import clear_shared_caches
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
    @dataclass
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

    # Backend is authoritative — values come back typed from the blueprint
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
    @dataclass
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
    @dataclass
    class MyConfig:  # type: ignore[no-redef]
        temperature: float = 0.99
        model: str = "gpt-4"

    second = MyConfig()

    assert second.temperature == pytest.approx(0.3)
    assert second.model == "gpt-3.5"


# ---------------------------------------------------------------------------
# AgentConfig programmatic tests
# ---------------------------------------------------------------------------


def test_agent_config__programmatic_create_and_update__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    config = AgentConfig(
        parameters={"temperature": 0.5, "max_tokens": 100},
        project_name=project_name,
        description="v1",
    )

    blueprint_id_v1 = config.blueprint_id
    assert blueprint_id_v1 is not None
    # get_blueprint returns raw strings — no field_types in the programmatic API
    assert config["temperature"] == "0.5"
    assert config["max_tokens"] == "100"

    # Update creates a new blueprint revision
    config.update(
        values={"temperature": 0.8, "max_tokens": 200},
        description="v2",
    )

    assert config.blueprint_id != blueprint_id_v1
    assert config["temperature"] == "0.8"
    assert config["max_tokens"] == "200"

    # Fetching without a specific blueprint returns the latest
    latest = AgentConfig.get_blueprint(project_name=project_name)
    assert latest["temperature"] == "0.8"
    assert latest["max_tokens"] == "200"


def test_agent_config__get_blueprint_by_env_tag__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    # Create the initial blueprint and note its ID
    config = AgentConfig(
        parameters={"temperature": 0.4},
        project_name=project_name,
    )
    blueprint_id = config.blueprint_id
    assert blueprint_id is not None

    # Assign that blueprint to the "prod" env label
    project_id = opik_client.rest_client.projects.retrieve_project(name=project_name).id
    opik_client.rest_client.agent_configs.create_or_update_envs(
        project_id=project_id,
        envs=[AgentConfigEnv(env_name="prod", blueprint_id=blueprint_id)],
    )

    # Fetch via env label — must return the same blueprint
    prod_config = AgentConfig.get_blueprint(project_name=project_name, env="prod")
    assert prod_config.blueprint_id == blueprint_id
    assert prod_config["temperature"] == "0.4"


def test_agent_config__mask_creation_and_application__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    base_config = AgentConfig(
        parameters={"temperature": 0.7, "max_tokens": 256, "model": "gpt-4"},
        project_name=project_name,
    )

    # Create a mask that overrides only temperature
    mask = base_config.create_mask(
        values={"temperature": 0.2},
        description="low-temperature variant",
    )

    # The mask is a separate AgentConfig with its own blueprint_id
    assert mask.blueprint_id is not None
    assert mask.blueprint_id != base_config.blueprint_id

    # The mask reflects the overridden value
    assert mask["temperature"] == "0.2"

    # Original config is unchanged
    assert base_config["temperature"] == "0.7"
    assert base_config["max_tokens"] == "256"

    # Fetch using the mask_id — should return the mask's values
    masked = AgentConfig.get_blueprint(
        project_name=project_name, mask_id=mask.blueprint_id
    )
    assert masked["temperature"] == "0.2"


def test_agent_config__blueprint_id_matches_posted_id__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """The blueprint_id returned after creation must equal the client-generated ID."""
    config = AgentConfig(
        parameters={"temperature": 0.5},
        project_name=project_name,
    )
    blueprint_id = config.blueprint_id
    assert blueprint_id is not None

    # Fetch by that specific ID — must round-trip
    fetched = AgentConfig.get_blueprint(project_name=project_name)
    assert fetched.blueprint_id == blueprint_id
    assert fetched["temperature"] == "0.5"


def test_agent_config__multiple_updates_each_produce_new_blueprint_id__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Each update must produce a distinct blueprint_id and the correct values."""
    config = AgentConfig(
        parameters={"temperature": 0.1, "max_tokens": 10},
        project_name=project_name,
        description="v1",
    )
    id_v1 = config.blueprint_id
    assert id_v1 is not None

    config.update(values={"temperature": 0.2, "max_tokens": 20}, description="v2")
    id_v2 = config.blueprint_id
    assert id_v2 is not None
    assert id_v2 != id_v1
    assert config["temperature"] == "0.2"
    assert config["max_tokens"] == "20"

    config.update(values={"temperature": 0.3, "max_tokens": 30}, description="v3")
    id_v3 = config.blueprint_id
    assert id_v3 is not None
    assert id_v3 != id_v2
    assert config["temperature"] == "0.3"
    assert config["max_tokens"] == "30"

    # Latest blueprint reflects v3
    latest = AgentConfig.get_blueprint(project_name=project_name)
    assert latest.blueprint_id == id_v3
    assert latest["temperature"] == "0.3"


def test_agent_config__mask_id_pin__does_not_update_backend__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Decorator with mask_id must only read — not write — to the backend."""

    # Create a base blueprint via the decorator API (creates "BaseConfig.temperature" key)
    @opik.agent_config(project=project_name)
    @dataclass
    class BaseConfig:
        temperature: float = 0.5

    BaseConfig()

    # Create a mask using the programmatic API with the same prefixed key
    base = AgentConfig(
        parameters={"BaseConfig.temperature": 0.5},
        project_name=project_name,
    )
    mask = base.create_mask(values={"BaseConfig.temperature": 0.1})
    mask_id = mask.blueprint_id
    assert mask_id is not None

    # Record latest blueprint before the pinned read
    pre_read_latest = AgentConfig.get_blueprint(project_name=project_name)

    clear_shared_caches()

    @opik.agent_config(project=project_name, mask_id=mask_id)
    @dataclass
    class BaseConfig:  # type: ignore[no-redef]
        temperature: float = 0.99  # local default should be ignored

    instance = BaseConfig()
    # Must use the mask value, not the local default or the base blueprint
    assert instance.temperature == pytest.approx(0.1)

    # No new blueprint should have been created — latest is still the same
    post_read_latest = AgentConfig.get_blueprint(project_name=project_name)
    assert post_read_latest.blueprint_id == pre_read_latest.blueprint_id


def test_agent_config__env_pin__does_not_update_backend__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Decorator with env must only read — not write — to the backend."""

    # Create a base blueprint via the decorator API so keys are prefixed
    @opik.agent_config(project=project_name)
    @dataclass
    class EnvConfig:
        temperature: float = 0.4

    base_instance = EnvConfig()
    base_blueprint_id = base_instance.__opik_shared_cache__.blueprint_id
    assert base_blueprint_id is not None

    project_id = opik_client.rest_client.projects.retrieve_project(name=project_name).id
    opik_client.rest_client.agent_configs.create_or_update_envs(
        project_id=project_id,
        envs=[AgentConfigEnv(env_name="staging", blueprint_id=base_blueprint_id)],
    )

    clear_shared_caches()

    @opik.agent_config(project=project_name, env="staging")
    @dataclass
    class EnvConfig:  # type: ignore[no-redef]
        temperature: float = 0.99  # local default should be ignored
        name: str = "placeholder"

    instance = EnvConfig()
    assert instance.temperature == pytest.approx(0.4)

    # Still just the one blueprint — no extra write
    latest = AgentConfig.get_blueprint(project_name=project_name)
    assert latest.blueprint_id == base_blueprint_id


def test_agent_config__multiple_mask_updates__each_produce_distinct_mask_id__happyflow(
    opik_client: opik.Opik,
    project_name: str,
):
    """Multiple mask creations must each have a unique blueprint_id."""
    base = AgentConfig(
        parameters={"temperature": 0.7, "model": "gpt-4"},
        project_name=project_name,
    )

    mask_a = base.create_mask(values={"temperature": 0.1}, description="low-temp")
    mask_b = base.create_mask(values={"temperature": 0.9}, description="high-temp")

    assert mask_a.blueprint_id is not None
    assert mask_b.blueprint_id is not None
    assert mask_a.blueprint_id != mask_b.blueprint_id
    assert mask_a["temperature"] == "0.1"
    assert mask_b["temperature"] == "0.9"

    # Fetch each by its specific mask_id to confirm IDs round-trip
    fetched_a = AgentConfig.get_blueprint(
        project_name=project_name, mask_id=mask_a.blueprint_id
    )
    fetched_b = AgentConfig.get_blueprint(
        project_name=project_name, mask_id=mask_b.blueprint_id
    )
    assert fetched_a.blueprint_id == mask_a.blueprint_id
    assert fetched_b.blueprint_id == mask_b.blueprint_id
    assert fetched_a["temperature"] == "0.1"
    assert fetched_b["temperature"] == "0.9"
