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


def _patch_client(monkeypatch, *, response=None, error=None):
    """Wire ``httpx_client.get`` to a fake client, capturing the probe URL and the
    client kwargs (so tests can assert the TLS flag is forwarded)."""
    captured = {}

    class _Client:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def get(self, url, timeout=None):
            captured["url"] = url
            captured["timeout"] = timeout
            if error is not None:
                raise error
            return response

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
    assert captured["url"] == WELL_KNOWN_URL


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
