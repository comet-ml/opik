import builtins
import importlib.metadata

import pytest
from packaging.requirements import Requirement

from opik.evaluation.engine import exception_analyzer
from opik.evaluation.models import LiteLLMChatModel


def _block_litellm_import(monkeypatch, missing_name="litellm"):
    original_import = builtins.__import__

    def without_litellm(name, *args, **kwargs):
        if name == "litellm" or name.startswith("litellm."):
            raise ModuleNotFoundError(
                f"No module named '{missing_name}'", name=missing_name
            )
        return original_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", without_litellm)


def test_litellm_is_an_optional_sdk_extra():
    requirements = [
        Requirement(value) for value in importlib.metadata.requires("opik") or []
    ]
    litellm_requirements = [req for req in requirements if req.name == "litellm"]

    for python_version in ("3.10", "3.11"):
        marker_environment = {"python_version": python_version}
        assert not any(
            req.marker is None
            or req.marker.evaluate({**marker_environment, "extra": ""})
            for req in litellm_requirements
        )
        assert any(
            req.marker is not None
            and req.marker.evaluate({**marker_environment, "extra": "litellm"})
            for req in litellm_requirements
        )


def test_litellm_model_without_extra_shows_install_command(monkeypatch):
    _block_litellm_import(monkeypatch)

    with pytest.raises(ImportError, match=r"pip install 'opik\[litellm\]'"):
        LiteLLMChatModel(track=False)


def test_litellm_model_broken_install_keeps_original_error(monkeypatch):
    _block_litellm_import(monkeypatch, missing_name="broken_dependency")

    with pytest.raises(ModuleNotFoundError, match="broken_dependency"):
        LiteLLMChatModel(track=False)


def test_provider_independent_rate_limit_without_litellm(monkeypatch):
    _block_litellm_import(monkeypatch)
    rate_limit = Exception("rate limited")
    rate_limit.status_code = 429

    assert exception_analyzer.is_llm_provider_rate_limit_error(rate_limit)
    assert not exception_analyzer.is_llm_provider_rate_limit_error(ValueError("other"))


def test_rate_limit_broken_litellm_install_keeps_original_error(monkeypatch):
    _block_litellm_import(monkeypatch, missing_name="broken_dependency")

    with pytest.raises(ModuleNotFoundError, match="broken_dependency"):
        exception_analyzer.is_llm_provider_rate_limit_error(ValueError("other"))
