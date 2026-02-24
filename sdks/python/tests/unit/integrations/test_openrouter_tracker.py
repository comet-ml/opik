import pytest

from opik.integrations.openrouter import opik_tracker


def test_parse_openrouter_model_metadata_with_variants():
    metadata = opik_tracker._parse_openrouter_model_metadata(
        "openai/gpt-4o-mini:extended:online"
    )

    assert metadata["openrouter_model_base"] == "openai/gpt-4o-mini"
    assert metadata["openrouter_model_variants"] == ["extended", "online"]


def test_parse_openrouter_model_metadata_without_variants():
    metadata = opik_tracker._parse_openrouter_model_metadata("openai/gpt-4o-mini")

    assert metadata["openrouter_model_base"] == "openai/gpt-4o-mini"
    assert "openrouter_model_variants" not in metadata


def test_extract_openrouter_request_metadata():
    kwargs = {
        "model": "openai/gpt-4o-mini:thinking",
        "messages": [{"role": "user", "content": "hello"}],
        "models": ["openrouter/openai/gpt-4o-mini", "openrouter/anthropic/claude-3"],
    }

    metadata = opik_tracker._extract_openrouter_request_metadata(kwargs)

    assert metadata["openrouter_model_base"] == "openai/gpt-4o-mini"
    assert metadata["openrouter_model_variants"] == ["thinking"]
    assert metadata["openrouter_fallback_models"] == kwargs["models"]


def test_extract_openrouter_response_metadata():
    response = {
        "model": "openai/gpt-4o-mini:extended",
        "provider": "openai/gpt-4o-mini",
        "provider_name": "openrouter-openai",
        "provider_id": "openrouter/openai/gpt-4o-mini",
        "model_provider": "openrouter",
        "routing": {"order": ["anthropic/claude-3", "openai/gpt-4o-mini"]},
        "web_search": True,
        "models": ["openai/gpt-4o-mini", "openrouter/openai/gpt-4o-mini"],
    }

    metadata = opik_tracker._extract_openrouter_response_metadata(response)

    assert metadata["openrouter_provider"] == "openai/gpt-4o-mini"
    assert metadata["openrouter_provider_name"] == "openrouter-openai"
    assert metadata["openrouter_provider_id"] == "openrouter/openai/gpt-4o-mini"
    assert metadata["openrouter_model_provider"] == "openrouter"
    assert metadata["openrouter_model_base"] == "openai/gpt-4o-mini"
    assert metadata["openrouter_model_variants"] == ["extended"]
    assert metadata["openrouter_web_search"] is True
    assert metadata["openrouter_fallback_models"] == response["models"]


def test_convert_response_to_dict_reraises_dict_errors():
    class BrokenResponse:
        def dict(self):
            raise ValueError("boom")

    with pytest.raises(ValueError, match="boom"):
        opik_tracker._convert_response_to_dict(BrokenResponse())
