import json
import types

import httpx
import pytest

import opik.config as config
import opik.guardrails.rest_api_client as rest_api_client
from opik.rest_api import core as rest_api_core


def _config():
    return config.OpikConfig(api_key="test-api-key", workspace="test-workspace")


def test_build_httpx_client__auth_headers_and_timeout_configured():
    client = rest_api_client.build_httpx_client(config=_config(), timeout_seconds=7)

    assert client.headers["Authorization"] == "test-api-key"
    assert client.headers["Comet-Workspace"] == "test-workspace"
    assert client.timeout == httpx.Timeout(7)


def test_guardrails_api_client_validate__request_carries_auth_headers():
    sent_requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent_requests.append(request)
        return httpx.Response(200, json={"validation_passed": True, "validations": []})

    httpx_client = rest_api_client.build_httpx_client(
        config=_config(), timeout_seconds=7
    )
    httpx_client._transport = httpx.MockTransport(handler)

    api_client = rest_api_client.GuardrailsApiClient(
        httpx_client=httpx_client, host_url="http://guardrails/"
    )
    api_client.validate("some text", [{"type": "PII", "config": {}}])

    assert sent_requests[0].headers["Authorization"] == "test-api-key"
    assert sent_requests[0].headers["Comet-Workspace"] == "test-workspace"
    # The guardrails backend cannot read a gzipped body.
    assert "Content-Encoding" not in sent_requests[0].headers


def _opik_client(handler, request_options_sent=None):
    httpx_client = httpx.Client(
        base_url="http://opik/api/", transport=httpx.MockTransport(handler)
    )

    def request(path, method, json, request_options):
        if request_options_sent is not None:
            request_options_sent.append(request_options)
        return httpx_client.request(method, path, json=json)

    return types.SimpleNamespace(
        rest_client=types.SimpleNamespace(
            _client_wrapper=types.SimpleNamespace(
                httpx_client=types.SimpleNamespace(request=request)
            )
        )
    )


def test_validate_on_opik__posts_text_and_rules():
    sent_requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent_requests.append(request)
        return httpx.Response(200, json={"validation_passed": True, "validations": []})

    request_options_sent = []

    result = rest_api_client.validate_on_opik(
        client=_opik_client(handler, request_options_sent),
        text="some text",
        validations=[{"type": "PII", "config": {"entities": ["EMAIL_ADDRESS"]}}],
        timeout_seconds=7,
    )

    assert result.validation_passed is True
    assert sent_requests[0].url.path == "/api/v1/private/guardrails/v2/validations"
    assert json.loads(sent_requests[0].content) == {
        "text": "some text",
        "validations": [{"type": "PII", "config": {"entities": ["EMAIL_ADDRESS"]}}],
    }
    assert request_options_sent == [{"timeout_in_seconds": 7}]


@pytest.mark.parametrize(
    "response,expected_body",
    [
        (
            httpx.Response(
                403, json={"message": "Guardrails is available on the Enterprise plan"}
            ),
            {"message": "Guardrails is available on the Enterprise plan"},
        ),
        (httpx.Response(502, text="Bad Gateway"), "Bad Gateway"),
    ],
)
def test_validate_on_opik__error_status__raises_api_error(response, expected_body):
    with pytest.raises(rest_api_core.ApiError) as error:
        rest_api_client.validate_on_opik(
            client=_opik_client(lambda request: response),
            text="some text",
            validations=[{"type": "PROMPT_INJECTION", "config": {}}],
            timeout_seconds=7,
        )

    assert error.value.status_code == response.status_code
    assert error.value.body == expected_body
