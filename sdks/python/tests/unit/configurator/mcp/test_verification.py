import json
from unittest import mock

import httpx
import pytest

from opik.configurator.mcp import verification


class _FakeClient:
    """Minimal stand-in for the httpx client used as a context manager."""

    def __init__(self, response=None, error=None):
        self._response = response
        self._error = error
        self.requests = []

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def get(self, url, **kwargs):
        self.requests.append((url, kwargs))
        if self._error is not None:
            raise self._error
        return self._response


def _response(status_code, payload=None, text=""):
    content = json.dumps(payload).encode() if payload is not None else text.encode()
    return httpx.Response(
        status_code=status_code,
        content=content,
        request=httpx.Request("GET", "https://www.comet.com/opik/api/"),
    )


@pytest.fixture
def patch_client(monkeypatch):
    def _patch(client):
        monkeypatch.setattr(verification, "_client", lambda *a, **k: client)
        return client

    return _patch


class TestVerifyLocalCredentials:
    def test_verify_local_credentials__ok__reports_workspace_and_project_count(
        self, patch_client
    ):
        patch_client(_FakeClient(_response(200, {"total": 4, "content": [{}]})))

        result = verification.verify_local_credentials(
            api_key="key",
            workspace="acme-ai",
            api_url="https://www.comet.com/opik/api/",
            check_tls_certificate=True,
        )

        assert result.succeeded is True
        assert "acme-ai" in result.detail
        assert "4 project(s)" in result.detail

    def test_verify_local_credentials__no_total__falls_back_to_content_length(
        self, patch_client
    ):
        patch_client(_FakeClient(_response(200, {"content": [{}, {}]})))

        result = verification.verify_local_credentials(
            api_key="key",
            workspace="acme-ai",
            api_url="https://www.comet.com/opik/api/",
            check_tls_certificate=True,
        )

        assert result.succeeded is True
        assert "2 project(s)" in result.detail

    def test_verify_local_credentials__unparseable_body__still_succeeds(
        self, patch_client
    ):
        """The call already proved the credentials work; we just report less."""
        patch_client(_FakeClient(_response(200, text="<html/>")))

        result = verification.verify_local_credentials(
            api_key="key",
            workspace="acme-ai",
            api_url="https://www.comet.com/opik/api/",
            check_tls_certificate=True,
        )

        assert result.succeeded is True
        assert "acme-ai" in result.detail
        assert "project(s)" not in result.detail

    @pytest.mark.parametrize("status_code", [401, 403])
    def test_verify_local_credentials__rejected__fails_with_actionable_message(
        self, patch_client, status_code
    ):
        patch_client(_FakeClient(_response(status_code)))

        result = verification.verify_local_credentials(
            api_key="stale-key",
            workspace="acme-ai",
            api_url="https://www.comet.com/opik/api/",
            check_tls_certificate=True,
        )

        assert result.succeeded is False
        assert str(status_code) in result.detail
        assert "opik configure" in result.detail

    def test_verify_local_credentials__server_error__fails(self, patch_client):
        patch_client(_FakeClient(_response(503)))

        result = verification.verify_local_credentials(
            api_key="key",
            workspace="acme-ai",
            api_url="https://www.comet.com/opik/api/",
            check_tls_certificate=True,
        )

        assert result.succeeded is False
        assert "503" in result.detail

    def test_verify_local_credentials__network_error__fails(self, patch_client):
        patch_client(_FakeClient(error=httpx.ConnectError("no route to host")))

        result = verification.verify_local_credentials(
            api_key="key",
            workspace="acme-ai",
            api_url="https://opik.acme.com/api/",
            check_tls_certificate=True,
        )

        assert result.succeeded is False
        assert "could not reach" in result.detail

    def test_verify_local_credentials__does_not_echo_the_api_key(self, patch_client):
        patch_client(_FakeClient(_response(401)))

        result = verification.verify_local_credentials(
            api_key="super-secret",
            workspace="acme-ai",
            api_url="https://www.comet.com/opik/api/",
            check_tls_certificate=True,
        )

        assert "super-secret" not in result.detail

    def test_verify_local_credentials__url_missing_slash__still_builds_projects_url(
        self, patch_client
    ):
        client = patch_client(_FakeClient(_response(200, {"total": 0})))

        verification.verify_local_credentials(
            api_key="key",
            workspace="ws",
            api_url="https://www.comet.com/opik/api",
            check_tls_certificate=True,
        )

        assert client.requests[0][0] == (
            "https://www.comet.com/opik/api/v1/private/projects"
        )


class TestVerifyHostedEndpoint:
    @pytest.mark.parametrize("status_code", [401, 403])
    def test_verify_hosted_endpoint__challenge__is_healthy(
        self, patch_client, status_code
    ):
        """No credentials are stored, so rejecting an anonymous probe is correct."""
        patch_client(_FakeClient(_response(status_code)))

        result = verification.verify_hosted_endpoint(
            mcp_url="https://www.comet.com/opik/api/v1/mcp",
            check_tls_certificate=True,
        )

        assert result.succeeded is True
        assert "sign in" in result.detail

    def test_verify_hosted_endpoint__not_found__fails(self, patch_client):
        patch_client(_FakeClient(_response(404)))

        result = verification.verify_hosted_endpoint(
            mcp_url="https://www.comet.com/opik/api/v1/mcp",
            check_tls_certificate=True,
        )

        assert result.succeeded is False
        assert "--local-server" in result.detail

    def test_verify_hosted_endpoint__network_error__fails(self, patch_client):
        patch_client(_FakeClient(error=httpx.ReadTimeout("timed out")))

        result = verification.verify_hosted_endpoint(
            mcp_url="https://www.comet.com/opik/api/v1/mcp",
            check_tls_certificate=True,
        )

        assert result.succeeded is False
        assert "could not reach" in result.detail


class TestListWorkspaces:
    def test_list_workspaces__ok__returns_names(self, patch_client):
        patch_client(_FakeClient(_response(200, {"workspaceNames": ["a", "b"]})))

        assert verification.list_workspaces(
            api_key="key", base_url="https://www.comet.com/", check_tls_certificate=True
        ) == ["a", "b"]

    @pytest.mark.parametrize(
        "response",
        [
            _response(500),
            _response(200, text="<html/>"),
            _response(200, {"unexpected": "shape"}),
            _response(200, ["not", "a", "dict"]),
        ],
    )
    def test_list_workspaces__unusable_response__returns_none(
        self, patch_client, response
    ):
        """`None` means "unknown" and must stay distinguishable from "exactly one"."""
        patch_client(_FakeClient(response))

        assert (
            verification.list_workspaces(
                api_key="key",
                base_url="https://www.comet.com/",
                check_tls_certificate=True,
            )
            is None
        )

    def test_list_workspaces__network_error__returns_none(self, patch_client):
        patch_client(_FakeClient(error=httpx.ConnectError("boom")))

        assert (
            verification.list_workspaces(
                api_key="key",
                base_url="https://www.comet.com/",
                check_tls_certificate=True,
            )
            is None
        )

    def test_list_workspaces__sends_the_api_key_not_a_workspace(self, monkeypatch):
        client_spy = mock.Mock(
            return_value=_FakeClient(_response(200, {"workspaceNames": []}))
        )
        monkeypatch.setattr(verification, "_client", client_spy)

        verification.list_workspaces(
            api_key="key",
            base_url="https://www.comet.com/",
            check_tls_certificate=False,
        )

        assert client_spy.call_args.args == ("key", None, False)


class TestHostedEndpointStatuses:
    """Only the auth challenge proves a working hosted endpoint.

    Every status except 404 used to report success, so a 500 or a 502 announced
    "reachable" and the user found out when sign-in failed.
    """

    @pytest.mark.parametrize("status_code", [400, 500, 502, 503])
    def test_verify_hosted_endpoint__server_error__fails(
        self, patch_client, status_code
    ):
        patch_client(_FakeClient(_response(status_code)))

        result = verification.verify_hosted_endpoint(
            mcp_url="https://www.comet.com/opik/api/v1/mcp",
            check_tls_certificate=True,
        )

        assert result.succeeded is False
        assert str(status_code) in result.detail
