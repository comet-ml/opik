"""A training request's base model is a Hub repo id, and stored model names are plain names."""

import json
import sys
import types

import pydantic
import pytest

from opik_guardrails import schemas

INVALID_BASE_MODELS = [
    "http://example.com/model",
    "https://example.com/model/config.json",
    "/opt/models/base",
    "../adapters/other",
    "./local-model",
    "Qwen/../other",
    "a/b/c",
    "org/model--x",
    "org/model.",
    "org/model-",
    "",
]
VALID_BASE_MODELS = ["Qwen/Qwen2.5-1.5B-Instruct", "gpt2", "meta-llama/Llama-3.2-1B"]
INVALID_NAMES = ["..", ".", "../x", "/tmp/x", "a/b", ".hidden", ""]


def _examples():
    return [{"text": f"example {i}", "label": i % 2} for i in range(10)]


@pytest.fixture
def client(monkeypatch, tmp_path):
    """The Flask app with its validation engine stubbed: importing the real one loads the models."""
    monkeypatch.setitem(
        sys.modules,
        "opik_guardrails.services.validation_engine",
        types.ModuleType("opik_guardrails.services.validation_engine"),
    )
    monkeypatch.delitem(sys.modules, "opik_guardrails.api.routes", raising=False)
    monkeypatch.setenv("OPIK_GUARDRAILS_ADAPTERS_DIR", str(tmp_path))
    from opik_guardrails import create_app
    from opik_guardrails.api import routes

    launched = []
    monkeypatch.setattr(routes.subprocess, "Popen", lambda args: launched.append(args))
    return create_app().test_client(), launched, tmp_path


@pytest.mark.parametrize("base_model", INVALID_BASE_MODELS)
def test_training_refuses_a_base_model_that_is_not_a_hub_repo_id(client, base_model):
    test_client, launched, adapters_dir = client

    response = test_client.post(
        "/api/v1/guardrails/custom/train",
        json={
            "name": "toxic",
            "description": "contains toxic language",
            "examples": _examples(),
            "base_model": base_model,
        },
    )

    assert response.status_code == 400
    assert launched == []
    assert list(adapters_dir.iterdir()) == []


@pytest.mark.parametrize("base_model", VALID_BASE_MODELS)
def test_training_launches_with_a_hub_repo_id(client, base_model):
    test_client, launched, adapters_dir = client

    response = test_client.post(
        "/api/v1/guardrails/custom/train",
        json={
            "name": "toxic",
            "description": "contains toxic language",
            "examples": _examples(),
            "base_model": base_model,
        },
    )

    assert (response.status_code, response.get_json()) == (
        202,
        {"name": "toxic", "status": "training"},
    )
    assert len(launched) == 1
    job = json.loads((adapters_dir / "toxic" / "_job.json").read_text())
    assert job["base_model"] == base_model


@pytest.mark.parametrize("name", INVALID_NAMES)
def test_training_refuses_a_name_outside_the_adapters_directory(client, name):
    test_client, launched, adapters_dir = client

    response = test_client.post(
        "/api/v1/guardrails/custom/train",
        json={
            "name": name,
            "description": "contains toxic language",
            "examples": _examples(),
        },
    )

    assert response.status_code == 400
    assert launched == []
    assert list(adapters_dir.iterdir()) == []


@pytest.mark.parametrize("name", INVALID_NAMES)
def test_custom_classifier_refuses_a_model_name_outside_the_adapters_directory(name):
    with pytest.raises(pydantic.ValidationError):
        schemas.CustomClassifierValidationConfig(model_name=name)


def test_custom_classifier_accepts_a_stored_model_name():
    assert (
        schemas.CustomClassifierValidationConfig(model_name="toxic-v2.1").model_name
        == "toxic-v2.1"
    )
