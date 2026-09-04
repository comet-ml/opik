import sys

from opik.evaluation.engine import exception_analyzer


class _HttpStatusError(Exception):
    """Mimics a provider error carrying a status code without needing either SDK."""

    status_code = 429


def test_openai_rate_limit_error_is_detected():
    import httpx
    import openai

    response = httpx.Response(
        429, request=httpx.Request("POST", "https://api.example.com")
    )
    error = openai.RateLimitError("slow down", response=response, body=None)

    assert exception_analyzer.is_llm_provider_rate_limit_error(error) is True


def test_status_code_fallback_classifies_429_without_litellm():
    """litellm is not installed in this environment; the status_code fallback must still classify."""
    assert (
        exception_analyzer.is_llm_provider_rate_limit_error(
            _HttpStatusError("retry me")
        )
        is True
    )


def test_missing_provider_sdks_do_not_crash_the_analyzer(monkeypatch):
    """#8134 defect 4: the analyzer runs inside metric error handlers. With neither
    provider SDK importable it must answer from the status_code fallback instead of
    raising ImportError out of the handler's except block and aborting the run."""
    monkeypatch.setitem(sys.modules, "openai", None)
    monkeypatch.setitem(sys.modules, "litellm", None)
    monkeypatch.setitem(sys.modules, "litellm.exceptions", None)

    assert (
        exception_analyzer.is_llm_provider_rate_limit_error(
            ValueError("tolerated error")
        )
        is False
    )
    assert (
        exception_analyzer.is_llm_provider_rate_limit_error(
            _HttpStatusError("429 without SDKs")
        )
        is True
    )


def test_exception_without_status_code_is_not_rate_limit(monkeypatch):
    monkeypatch.setitem(sys.modules, "openai", None)
    monkeypatch.setitem(sys.modules, "litellm", None)
    monkeypatch.setitem(sys.modules, "litellm.exceptions", None)

    assert (
        exception_analyzer.is_llm_provider_rate_limit_error(
            ValueError("tolerated error")
        )
        is False
    )
