import httpx

from opik.configurator.mcp import detection

BASE_URL = "https://dev.comet.com/"
API_URL = "https://dev.comet.com/opik/api/"
WELL_KNOWN_URL = "https://dev.comet.com/.well-known/oauth-authorization-server/opik"
MCP_URL = "https://dev.comet.com/opik/api/v1/mcp"

_VALID_METADATA = {
    "issuer": "https://dev.comet.com/opik",
    "authorization_endpoint": "https://dev.comet.com/opik/oauth/authorize",
    "token_endpoint": "https://dev.comet.com/opik/oauth/token",
}


class _Response:
    def __init__(self, status_code, json_data=None, json_error=False):
        self.status_code = status_code
        self._json_data = json_data
        self._json_error = json_error

    def json(self):
        if self._json_error:
            raise ValueError("Expecting value")
        return self._json_data


def _patch_client(monkeypatch, *, response=None, error=None, mcp_response=None):
    """Wire ``httpx_client.get`` to a fake client that answers the discovery URL
    with ``response`` (or raises ``error``) and the MCP endpoint with
    ``mcp_response``, by default the 401 sign-in challenge of a running server;
    an answer that is an exception is raised. Captures the probed URLs and the
    client kwargs (so tests can assert the TLS flag is forwarded)."""
    captured = {"urls": []}
    answers = {
        WELL_KNOWN_URL: error or response,
        MCP_URL: mcp_response or _Response(401),
    }

    class _Client:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def get(self, url, timeout=None):
            captured["urls"].append(url)
            captured["timeout"] = timeout
            answer = answers[url]
            if isinstance(answer, Exception):
                raise answer
            return answer

    def fake_get(**kwargs):
        captured["client_kwargs"] = kwargs
        return _Client()

    monkeypatch.setattr(detection.httpx_client, "get", fake_get)
    return captured


def test_detect__valid_oauth_metadata__returns_mcp_url(monkeypatch):
    captured = _patch_client(
        monkeypatch, response=_Response(200, json_data=_VALID_METADATA)
    )

    result = detection.detect_hosted_mcp_server(
        base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
    )

    assert result == MCP_URL
    assert captured["urls"] == [WELL_KNOWN_URL, MCP_URL]


def test_detect__forwards_tls_flag_without_rereading_config(monkeypatch):
    captured = _patch_client(
        monkeypatch, response=_Response(200, json_data=_VALID_METADATA)
    )

    detection.detect_hosted_mcp_server(
        base_url=BASE_URL, api_url=API_URL, check_tls_certificate=False
    )

    assert captured["client_kwargs"]["check_tls_certificate"] is False


def test_detect__html_catch_all_200__returns_none(monkeypatch):
    # The frontend SPA answers any unknown path with HTML and a 200 status.
    _patch_client(monkeypatch, response=_Response(200, json_error=True))

    result = detection.detect_hosted_mcp_server(
        base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
    )

    assert result is None


def test_detect__not_found__returns_none(monkeypatch):
    _patch_client(
        monkeypatch, response=_Response(404, json_data={"error": "not_found"})
    )

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        is None
    )


def test_detect__json_without_authorization_endpoint__returns_none(monkeypatch):
    _patch_client(
        monkeypatch, response=_Response(200, json_data={"unrelated": "value"})
    )

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        is None
    )


def test_detect__non_dict_json__returns_none(monkeypatch):
    _patch_client(monkeypatch, response=_Response(200, json_data=["not", "a", "dict"]))

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        is None
    )


def test_detect__network_error__returns_none(monkeypatch):
    _patch_client(monkeypatch, error=httpx.ConnectError("unreachable"))

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        is None
    )


def test_detect__oauth_metadata_but_no_mcp_server__returns_none(monkeypatch):
    # MCP OAuth is on but no MCP server is deployed: the Opik backend answers
    # 404 at the MCP path, so registering that URL would leave a dead server.
    _patch_client(
        monkeypatch,
        response=_Response(200, json_data=_VALID_METADATA),
        mcp_response=_Response(404, json_data={"code": 404}),
    )

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        is None
    )


def test_detect__mcp_server_error__returns_mcp_url(monkeypatch):
    # An outage is not a missing server: keep the hosted one rather than move the
    # user to the local server.
    _patch_client(
        monkeypatch,
        response=_Response(200, json_data=_VALID_METADATA),
        mcp_response=_Response(503),
    )

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        == MCP_URL
    )


def test_detect__mcp_server_unreachable__returns_mcp_url(monkeypatch):
    _patch_client(
        monkeypatch,
        response=_Response(200, json_data=_VALID_METADATA),
        mcp_response=httpx.ReadTimeout("timed out"),
    )

    assert (
        detection.detect_hosted_mcp_server(
            base_url=BASE_URL, api_url=API_URL, check_tls_certificate=True
        )
        == MCP_URL
    )
