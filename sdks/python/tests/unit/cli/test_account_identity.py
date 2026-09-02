"""Tests for the account identity reported with the `configuration` events."""

import hashlib

import pytest
from unittest import mock

from opik.cli import account_identity


@pytest.fixture(autouse=True)
def reporting_on(monkeypatch, tmp_path):
    """Analytics is off under pytest, and off means nothing is looked up at all.

    Every test here is about what gets reported when it is on, so the switch is
    flipped for all of them; the one test that needs it off flips it back.
    """
    monkeypatch.setattr(account_identity.analytics, "reporting_allowed", lambda: True)
    # Resolution is cached for the process, which outlives a single test.
    account_identity._fetch.cache_clear()
    # Away from the developer's own home directory: whoever runs these has the MCP
    # server installed, and the tests would then assert against their machine's id.
    monkeypatch.setattr(
        account_identity, "_MCP_INSTALL_ID_PATH", tmp_path / "absent" / "install-id"
    )


def _config(api_key="key", workspace="my-ws", cloud=True):
    return mock.Mock(
        api_key=api_key,
        workspace=workspace,
        is_cloud_installation=cloud,
        url_override="https://www.comet.com/opik/api",
    )


def _responding(status=200, body=None, error=None):
    """A stand-in httpx client, so the transport is not what is under test."""
    client = mock.MagicMock()
    client.__enter__.return_value = client
    if error is not None:
        client.get.side_effect = error
    else:
        response = mock.Mock(status_code=status)
        response.json.return_value = body if body is not None else {}
        client.get.return_value = response
    return client


class TestResolvedAccount:
    def test_cloud_key__login_is_reported(self):
        with mock.patch.object(
            account_identity.httpx,
            "Client",
            return_value=_responding(
                body={"userName": "someone", "defaultWorkspaceName": "their-ws"}
            ),
        ):
            properties = account_identity._properties(_config())

        assert properties["user_id"] == "someone"
        assert properties["identity_lookup"] == "resolved"

    def test_two_events__cost_one_round_trip(self):
        """A command reports an entry and a result event, not two lookups."""
        client = _responding(body={"userName": "someone"})

        with mock.patch.object(account_identity.httpx, "Client", return_value=client):
            account_identity._properties(_config())
            account_identity._properties(_config())

        assert client.get.call_count == 1

    def test_key_is_sent_as_authorization__and_never_as_a_property(self):
        client = _responding(body={"userName": "someone"})

        with mock.patch.object(account_identity.httpx, "Client", return_value=client):
            properties = account_identity._properties(_config(api_key="secret-key"))

        assert client.get.call_args.kwargs["headers"] == {"Authorization": "secret-key"}
        assert "secret-key" not in properties.values()


class TestUnresolvedAccount:
    """An unattributed run has to say which reason it was.

    Coverage that ramps with upgrades is only readable if "nobody to ask about
    yet", "nobody to ask, ever" and "asked and got nothing" are separable.
    """

    def test_no_api_key__reports_no_credential_without_asking(self):
        with mock.patch.object(account_identity.httpx, "Client") as client:
            properties = account_identity._properties(_config(api_key=None))

        assert properties["identity_lookup"] == "no_credential"
        assert "user_id" not in properties
        client.assert_not_called()

    def test_self_hosted__reports_none_expected_without_asking(self):
        """There is no account-details endpoint to spend a timeout on."""
        with mock.patch.object(account_identity.httpx, "Client") as client:
            properties = account_identity._properties(_config(cloud=False))

        assert properties["identity_lookup"] == "none_expected"
        client.assert_not_called()

    def test_local_install_without_a_key__is_none_expected_rather_than_no_credential(
        self,
    ):
        """A local Opik has no accounts at all, so it is not a run to wait on."""
        properties = account_identity._properties(_config(api_key=None, cloud=False))

        assert properties["identity_lookup"] == "none_expected"

    @pytest.mark.parametrize(
        "answer",
        [
            {"status": 401},
            {"status": 200, "body": {"defaultWorkspaceName": "their-ws"}},
            {"error": Exception("no route to host")},
        ],
    )
    def test_lookup_produced_no_login__reports_a_miss(self, answer):
        with mock.patch.object(
            account_identity.httpx, "Client", return_value=_responding(**answer)
        ):
            properties = account_identity._properties(_config())

        assert properties["identity_lookup"] == "miss"
        assert "user_id" not in properties

    def test_answer_is_not_json__does_not_raise(self):
        with mock.patch.object(
            account_identity.httpx, "Client", return_value=_responding(body=["nope"])
        ):
            properties = account_identity._properties(_config())

        assert properties["identity_lookup"] == "miss"


class TestCredentialBridge:
    """The key digest is the widest bridge to the MCP funnels, so it is unconditional.

    Measured on the local stdio funnel: 83% of installs carry a key digest against 9%
    that resolve a login, and the login set is a subset of the digest set.
    """

    def test_api_key__reported_as_the_same_digest_the_mcp_server_emits(self):
        # sha256("secret-key"), the transform in opik_mcp.credential_identity.
        expected = hashlib.sha256(b"secret-key").hexdigest()

        properties = account_identity._properties(
            _config(api_key="secret-key", cloud=False)
        )

        assert properties["api_key_sha256"] == expected
        # The bridge has to survive a deployment that can resolve no name at all.
        assert properties["identity_lookup"] == "none_expected"

    def test_no_api_key__no_digest_property(self):
        properties = account_identity._properties(_config(api_key=None))

        assert "api_key_sha256" not in properties


class TestMcpInstallId:
    """The only bridge for an install with no credential at all."""

    def test_install_id_file__is_read_and_reported(self, tmp_path, monkeypatch):
        path = tmp_path / "install-id"
        path.write_text("0a597378-b4c9-4eff-a4b7-62e50a03279d\n")
        monkeypatch.setattr(account_identity, "_MCP_INSTALL_ID_PATH", path)

        properties = account_identity._properties(_config(api_key=None))

        assert properties["install_id"] == "0a597378-b4c9-4eff-a4b7-62e50a03279d"

    def test_no_install_id_file__is_never_created(self, tmp_path, monkeypatch):
        """Writing it would report every onboarding as a returning install."""
        path = tmp_path / "install-id"
        monkeypatch.setattr(account_identity, "_MCP_INSTALL_ID_PATH", path)

        properties = account_identity._properties(_config(api_key=None))

        assert "install_id" not in properties
        assert not path.exists()

    @pytest.mark.parametrize("content", ["", "not-a-uuid", "0a597378-b4c9"])
    def test_unusable_install_id__reports_nothing(self, content, tmp_path, monkeypatch):
        """An id that joins to nothing is worse than no id."""
        path = tmp_path / "install-id"
        path.write_text(content)
        monkeypatch.setattr(account_identity, "_MCP_INSTALL_ID_PATH", path)

        assert "install_id" not in account_identity._properties(_config(api_key=None))


class TestWorkspace:
    """The workspace someone is configuring, and how much a join can trust it."""

    def test_configured_workspace__outranks_the_account_default(self):
        """They may be working outside their own default, deliberately."""
        with mock.patch.object(
            account_identity.httpx,
            "Client",
            return_value=_responding(
                body={"userName": "someone", "defaultWorkspaceName": "their-ws"}
            ),
        ):
            properties = account_identity._properties(_config(workspace="other-ws"))

        assert properties["workspace"] == "other-ws"
        assert properties["workspace_kind"] == "configured"

    def test_default_sentinel__falls_back_to_the_resolved_name(self):
        with mock.patch.object(
            account_identity.httpx,
            "Client",
            return_value=_responding(
                body={"userName": "someone", "defaultWorkspaceName": "their-ws"}
            ),
        ):
            properties = account_identity._properties(_config(workspace="default"))

        assert properties["workspace"] == "their-ws"
        assert properties["workspace_kind"] == "resolved"

    def test_default_sentinel__nothing_resolved__is_marked_a_placeholder(self):
        """One name shared by every install that never set one: never a join key."""
        properties = account_identity._properties(
            _config(api_key=None, workspace="default")
        )

        assert properties["workspace"] == "default"
        assert properties["workspace_kind"] == "placeholder"

    def test_no_workspace_at_all__is_unknown_and_names_nothing(self):
        properties = account_identity._properties(_config(api_key=None, workspace=""))

        assert properties["workspace_kind"] == "unknown"
        assert "workspace" not in properties


class TestNeverFeltByTheUser:
    def test_analytics_switched_off__nothing_reported_and_nothing_asked(
        self, monkeypatch
    ):
        monkeypatch.setattr(
            account_identity.analytics, "reporting_allowed", lambda: False
        )

        with mock.patch.object(account_identity.httpx, "Client") as client:
            assert account_identity.event_properties() == {}

        client.assert_not_called()

    def test_config_unreadable__still_returns_countable_properties(self, monkeypatch):
        monkeypatch.setattr(
            account_identity.opik_config,
            "OpikConfig",
            mock.Mock(side_effect=Exception("no config")),
        )

        assert account_identity.event_properties() == {
            "identity_lookup": "miss",
            "workspace_kind": "unknown",
        }
