"""Provider detection must survive whichever shape the base URL arrives in.

A run dict is a serialised LangChain run, so `extra.invocation_params.base_url`
reaches the extractor either as a URL object or as the plain string that was
configured. Reading `.host` off a string raises AttributeError, which escapes
`get_llm_usage_info` and makes `try_extract_provider_usage_data` return None, so
the span is logged with no usage, no model and no cost even though the tokens
were extractable.
"""

import logging
import uuid
from types import SimpleNamespace
from typing import Any, Dict

import httpx
import pytest
from langchain_core.tracers import schemas

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


def _provider(base_url: Any) -> Any:
    info = usage_extractor.try_extract_provider_usage_data(_openai_run(base_url))
    _assert_usage_survived(info)
    return info.provider


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
    "base_url,expected_provider",
    [
        pytest.param(
            "https://my-proxy.example.com/v1",
            "my-proxy.example.com",
            id="proxy",
        ),
        pytest.param("http://localhost:8080/v1", "localhost", id="localhost_with_port"),
        pytest.param(
            "//my-proxy.example.com/v1",
            "my-proxy.example.com",
            id="scheme_relative",
        ),
        pytest.param(
            "https://user:pw@my-proxy.example.com/v1",
            "my-proxy.example.com",
            id="userinfo",
        ),
        pytest.param(
            "HTTPS://MY-PROXY.EXAMPLE.COM/v1",
            "my-proxy.example.com",
            id="upper_case",
        ),
        pytest.param("http://127.0.0.1:9000", "127.0.0.1", id="ipv4"),
        pytest.param("https://[2001:db8::1]:8443/v1", "2001:db8::1", id="ipv6"),
        pytest.param(
            "HTTP://API.OPENAI.COM/v1",
            LLMProvider.OPENAI,
            id="upper_case_openai",
        ),
        pytest.param("my-proxy.example.com/v1", "", id="no_scheme"),
        pytest.param("MY-PROXY.EXAMPLE.COM/v1", "", id="no_scheme_upper_case"),
        pytest.param("not a url", "", id="free_text"),
        pytest.param("/v1", "", id="path_only"),
        pytest.param("http://", "", id="scheme_only"),
    ],
)
def test_try_extract_provider_usage_data__string_and_url_object__report_the_same_provider(
    base_url: str, expected_provider: Any
) -> None:
    """The two shapes a serialised run can carry must not name different providers."""
    assert _provider(base_url) == expected_provider
    assert _provider(httpx.URL(base_url)) == expected_provider


def test_try_extract_provider_usage_data__unparseable_base_url__reports_no_host(
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An unreadable host must not be reported as OpenAI, which prices the run."""
    monkeypatch.setattr("opik._logging.LOG_ONCE_CACHE", set())
    caplog.set_level(
        logging.WARNING,
        logger="opik.integrations.langchain.provider_usage_extractors.openai_usage_extractor",
    )

    for base_url in ("http://[::1", "https://user:secret@[::1"):
        assert _provider(base_url) == ""

    warnings = [
        record
        for record in caplog.records
        if "Could not parse base_url" in record.message
    ]
    assert len(warnings) == 1
    assert "ValueError" in warnings[0].message
    assert "secret" not in caplog.text


@pytest.mark.parametrize("host", [None, 42], ids=["none", "integer"])
def test_try_extract_provider_usage_data__non_text_host__reports_unknown_provider(
    host: Any,
) -> None:
    info = usage_extractor.try_extract_provider_usage_data(
        _openai_run(SimpleNamespace(host=host))
    )

    _assert_usage_survived(info)
    assert info.provider == ""


def test_try_extract_provider_usage_data__raising_host_accessor__reports_unknown_provider(
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("opik._logging.LOG_ONCE_CACHE", set())

    class BaseURLWithRaisingHost:
        @property
        def host(self) -> str:
            raise ValueError("private base URL details")

    caplog.set_level(
        logging.WARNING,
        logger="opik.integrations.langchain.provider_usage_extractors.openai_usage_extractor",
    )

    info = usage_extractor.try_extract_provider_usage_data(
        _openai_run(BaseURLWithRaisingHost())
    )

    _assert_usage_survived(info)
    assert info.provider == ""
    assert "Could not read base_url.host" in caplog.text
    assert "private base URL details" not in caplog.text


@pytest.mark.parametrize(
    "base_url",
    [
        pytest.param(42, id="int"),
        pytest.param(object(), id="bare_object"),
    ],
)
def test_try_extract_provider_usage_data__value_that_is_not_url_shaped__keeps_default(
    base_url: Any,
) -> None:
    assert _provider(base_url) == LLMProvider.OPENAI


def test_try_extract_provider_usage_data__base_url_through_a_real_run_object__reports_the_host() -> (
    None
):
    """Keeps the serialisation boundary between the tracer and the extractor covered.

    `opik_tracer._process_end_span` hands the extractors `run.dict()`, so the payload
    below is built through the same LangChain model rather than a literal dict. A
    change in how `extra` is dumped, including a switch to a JSON mode that coerces
    values, has to break here rather than only in a running application.
    """
    run = schemas.Run(
        id=uuid.uuid4(),
        trace_id=uuid.uuid4(),
        name="ChatOpenAI",
        run_type="llm",
        serialized={"kwargs": {"openai_api_key": "fixture-value"}},
        extra={
            "invocation_params": {
                "base_url": "http://litellm.local:4000",
                "model": "mock-model",
            }
        },
        outputs={
            "llm_output": {
                "token_usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 20,
                    "total_tokens": 30,
                },
                "model_name": "gpt-4o",
            }
        },
    )

    run_dict = run.dict()

    carried = run_dict["extra"]["invocation_params"]["base_url"]
    assert isinstance(carried, str), "the string must survive the run dump unchanged"

    info = usage_extractor.try_extract_provider_usage_data(run_dict)

    _assert_usage_survived(info)
    assert info.provider == "litellm.local"
