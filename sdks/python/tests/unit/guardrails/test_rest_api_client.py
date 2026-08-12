import httpx

import opik.config as config
import opik.guardrails.rest_api_client as rest_api_client


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
