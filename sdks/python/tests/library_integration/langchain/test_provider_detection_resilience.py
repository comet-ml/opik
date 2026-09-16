"""Provider detection must survive whichever shape the base URL arrives in.

A run dict is a serialised LangChain run, so `extra.invocation_params.base_url`
reaches the extractor either as a URL object or as the plain string that was
configured. Reading `.host` off a string raises AttributeError, which escapes
`get_llm_usage_info` and makes `try_extract_provider_usage_data` return None, so
the span is logged with no usage, no model and no cost even though the tokens
were extractable.
"""

from typing import Any, Dict

import httpx
import pytest

from opik import LLMProvider
from opik.integrations.langchain.provider_usage_extractors import usage_extractor


def _openai_run(base_url: Any) -> Dict[str, Any]:
    """OpenAI-shaped run carrying fully extractable usage plus a base URL."""
    return {
        "serialized": {"kwargs": {"openai_api_key": "fixture-value"}},
        "extra": {"invocation_params": {"base_url": base_url}},
        "outputs": {
            "llm_output": {
                "token_usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
                "model_name": "gpt-4o",
            }
        },
    }


def _assert_usage_survived(info: Any) -> None:
    assert info is not None, "the base URL must not discard the extracted usage"
    assert info.model == "gpt-4o"
    assert info.usage is not None
    assert info.usage.prompt_tokens == 10
    assert info.usage.completion_tokens == 20
    assert info.usage.total_tokens == 30


def test_try_extract_provider_usage_data__string_base_url__keeps_usage_and_reports_host() -> (
    None
):
    info = usage_extractor.try_extract_provider_usage_data(
        _openai_run("https://my-proxy.example.com/v1")
    )

    _assert_usage_survived(info)
    assert info.provider == "my-proxy.example.com"


def test_try_extract_provider_usage_data__string_openai_base_url__keeps_default_provider() -> (
    None
):
    info = usage_extractor.try_extract_provider_usage_data(
        _openai_run("https://api.openai.com/v1")
    )

    _assert_usage_survived(info)
    assert info.provider == LLMProvider.OPENAI


def test_try_extract_provider_usage_data__url_object_base_url__behaves_as_before() -> (
    None
):
    info = usage_extractor.try_extract_provider_usage_data(
        _openai_run(httpx.URL("https://my-proxy.example.com/v1"))
    )

    _assert_usage_survived(info)
    assert info.provider == "my-proxy.example.com"


@pytest.mark.parametrize(
    "base_url",
    [
        # urlsplit raises ValueError on this one, measured rather than assumed
        pytest.param("http://[::1", id="unparseable_ipv6"),
        pytest.param("not a url", id="no_scheme"),
        pytest.param(42, id="neither_string_nor_url"),
    ],
)
def test_try_extract_provider_usage_data__unusable_base_url__falls_back_to_default(
    base_url: Any,
) -> None:
    info = usage_extractor.try_extract_provider_usage_data(_openai_run(base_url))

    _assert_usage_survived(info)
    assert info.provider == LLMProvider.OPENAI
