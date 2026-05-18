import base64
import uuid
from unittest.mock import MagicMock, patch

import click
import pytest

from opik.cli.local_runner.pairing import (
    PairingResult,
    RunnerType,
    build_pairing_link,
    generate_runner_name,
    hkdf_sha256,
    resolve_project_id,
    run_headless,
    run_pairing,
    validate_runner_name,
)
from opik.rest_api.errors.not_found_error import NotFoundError


# Helpers — fill in the keyword-only args that production callers always supply
# so tests don't have to repeat them. Tests override what they actually care
# about via **overrides.
def _resolve(api, project_name, **overrides):
    kwargs = dict(
        create_if_missing=False,
        workspace=None,
        base_url=None,
        config_file_exists=True,
    )
    kwargs.update(overrides)
    return resolve_project_id(api, project_name, **kwargs)


def _headless(api, **overrides):
    kwargs = dict(
        api=api,
        project_name="my-proj",
        runner_name="r-1",
        runner_type=RunnerType.ENDPOINT,
        create_if_missing=False,
        workspace=None,
        base_url=None,
        config_file_exists=True,
    )
    kwargs.update(overrides)
    return run_headless(**kwargs)


def _link(**overrides):
    kwargs = dict(
        base_url="https://www.comet.com/opik/api/",
        session_id="550e8400-e29b-41d4-a716-446655440000",
        activation_key=b"\x00" * 32,
        project_id="660e8400-e29b-41d4-a716-446655440000",
        project_name="my-proj",
        runner_name="r",
        runner_type=RunnerType.CONNECT,
        workspace=None,
    )
    kwargs.update(overrides)
    return build_pairing_link(**kwargs)


def _pair(api, **overrides):
    kwargs = dict(
        api=api,
        project_name="my-proj",
        runner_name="r-1",
        runner_type=RunnerType.ENDPOINT,
        base_url="http://localhost:5173/api/",
        workspace=None,
        tui=None,
        create_if_missing=False,
        config_file_exists=True,
    )
    kwargs.update(overrides)
    return run_pairing(**kwargs)


class TestHKDF:
    def test_hkdf__pinned_vector__matches_expected(self):
        ikm = bytes(range(32))
        salt = uuid.UUID("00000000-0000-0000-0000-000000000001").bytes
        info = b"opik-bridge-v1"

        okm = hkdf_sha256(ikm=ikm, salt=salt, info=info)

        assert (
            okm.hex()
            == "9647b7959765ecad68dfef02f31cfca7f7901a9076c15ebabb1f35d015f71198"
        )

    def test_hkdf__any_input__output_is_32_bytes(self):
        okm = hkdf_sha256(ikm=b"\x00" * 32, salt=b"\x00" * 16, info=b"test")
        assert len(okm) == 32

    def test_hkdf__different_info__different_output(self):
        a = hkdf_sha256(ikm=b"\x00" * 32, salt=b"\x00" * 16, info=b"a")
        b = hkdf_sha256(ikm=b"\x00" * 32, salt=b"\x00" * 16, info=b"b")
        assert a != b


class TestUUIDBytesOrder:
    def test_uuid_bytes__known_uuid__matches_java_big_endian(self):
        u = uuid.UUID("550e8400-e29b-41d4-a716-446655440000")
        assert u.bytes.hex() == "550e8400e29b41d4a716446655440000"


class TestBuildPairingLink:
    def test_build_pairing_link__valid_inputs__correct_payload_layout(self):
        session_id = "550e8400-e29b-41d4-a716-446655440000"
        project_id = "660e8400-e29b-41d4-a716-446655440000"
        activation_key = b"\xaa" * 32
        runner_name = "my-runner"

        link = _link(
            base_url="https://www.comet.com/opik/api/",
            session_id=session_id,
            activation_key=activation_key,
            project_id=project_id,
            runner_name=runner_name,
        )

        assert link.startswith("https://www.comet.com/opik/pair/v1?")

        fragment = link.split("#", 1)[1]
        padding_needed = (4 - len(fragment) % 4) % 4
        payload = base64.urlsafe_b64decode(fragment + "=" * padding_needed)

        assert payload[0:16] == uuid.UUID(session_id).bytes
        assert payload[16:48] == activation_key
        assert payload[48:64] == uuid.UUID(project_id).bytes
        assert payload[64] == len(runner_name.encode("utf-8"))
        name_end = 65 + payload[64]
        assert payload[65:name_end] == runner_name.encode("utf-8")
        # Default runner type is CONNECT (0x00)
        assert payload[name_end] == 0x00

    def test_build_pairing_link__endpoint_type__encodes_0x01(self):
        link = _link(
            base_url="http://localhost:5173/api/",
            session_id="550e8400-e29b-41d4-a716-446655440000",
            activation_key=b"\x00" * 32,
            project_id="660e8400-e29b-41d4-a716-446655440000",
            runner_name="r",
            runner_type=RunnerType.ENDPOINT,
        )
        fragment = link.split("#", 1)[1]
        padding_needed = (4 - len(fragment) % 4) % 4
        payload = base64.urlsafe_b64decode(fragment + "=" * padding_needed)
        # name_len=1, name="r", then type byte
        assert payload[66] == 0x01

    def test_build_pairing_link__cloud_url__no_double_opik_path(self):
        link = _link(
            base_url="https://www.comet.com/opik/api/",
            session_id="550e8400-e29b-41d4-a716-446655440000",
            activation_key=b"\x00" * 32,
            project_id="660e8400-e29b-41d4-a716-446655440000",
            runner_name="r",
        )
        assert "/opik/opik/" not in link

    def test_build_pairing_link__localhost_url__correct_prefix(self):
        link = _link(
            base_url="http://localhost:5173/api/",
            session_id="550e8400-e29b-41d4-a716-446655440000",
            activation_key=b"\x00" * 32,
            project_id="660e8400-e29b-41d4-a716-446655440000",
            runner_name="r",
        )
        assert link.startswith("http://localhost:5173/opik/pair/v1?")

    def test_build_pairing_link__always_includes_url_param(self):
        link = _link(
            base_url="http://localhost:5173/api/",
            workspace=None,
        )
        # `/api` is stripped — we send the URL the user would visit in a
        # browser, not the internal API endpoint.
        assert "url=http%3A%2F%2Flocalhost%3A5173%2F" in link
        assert "%2Fapi%2F" not in link

    def test_build_pairing_link__cloud_api_url__strips_api_suffix(self):
        link = _link(
            base_url="https://www.comet.com/opik/api/",
            workspace=None,
        )
        assert "url=https%3A%2F%2Fwww.comet.com%2Fopik%2F" in link

    def test_build_pairing_link__url_param_precedes_workspace(self):
        link = _link(
            base_url="http://localhost:5173/api/",
            workspace="team-a",
        )
        # All query params present; order is stable for FE parsing.
        assert "url=" in link
        assert "project=my-proj" in link
        assert "workspace=team-a" in link

    def test_build_pairing_link__project_name_with_special_chars__url_encoded(
        self,
    ):
        link = _link(project_name="My Project / Demo")
        # Spaces and `/` must be percent-encoded so the query stays parseable.
        assert "project=My%20Project%20%2F%20Demo" in link

    def test_build_pairing_link__workspace_with_special_chars__url_encoded(
        self,
    ):
        # `&` and `=` in a workspace name would otherwise split the query and
        # the FE's URLSearchParams would read a truncated value.
        link = _link(workspace="weird&name=oops")
        assert "workspace=weird%26name%3Doops" in link


class TestResolveProjectId:
    def test_resolve_project_id__project_exists__returns_id(self):
        api = MagicMock()
        project = MagicMock()
        project.id = "proj-uuid-123"
        api.projects.retrieve_project.return_value = project

        result = _resolve(api, "my-project")
        assert result == "proj-uuid-123"
        api.projects.retrieve_project.assert_called_once_with(name="my-project")

    def test_resolve_project_id__project_missing__uses_404_hint(self):
        from opik.rest_api.core.api_error import ApiError

        # Project retrieve returns Opik's custom ErrorMessage shape.
        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["Project not found"]}
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(api, "nonexistent")
        message = exc_info.value.message
        assert "Could not retrieve project 'nonexistent'" in message
        assert "Project not found" in message
        assert "check the project name and try again" in message

    def test_resolve_project_id__unauthorized__surfaces_server_message_and_docs(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=401,
            body={"code": 401, "message": "API key should be provided"},
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(api, "my-project")
        message = exc_info.value.message
        assert "Could not retrieve project 'my-project'" in message
        assert "API key should be provided" in message
        assert "Run: opik configure" in message
        assert (
            "https://www.comet.com/docs/opik/tracing/advanced/sdk_configuration"
            in message
        )

    def test_resolve_project_id__forbidden__shares_auth_hint(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=403,
            body={"code": 403, "message": "User is not allowed to access workspace"},
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(api, "my-project")
        message = exc_info.value.message
        assert "User is not allowed to access workspace" in message
        assert "Run: opik configure" in message

    def test_resolve_project_id__server_error__falls_back_to_generic_hint(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=500,
            body="Internal Server Error",
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(api, "my-project")
        message = exc_info.value.message
        assert "Internal Server Error" in message
        assert "verify your Opik configuration and connectivity" in message
        assert (
            "https://www.comet.com/docs/opik/tracing/advanced/sdk_configuration"
            in message
        )

    def test_resolve_project_id__empty_body__falls_back_to_status_text(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(status_code=502, body=None)

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(api, "my-project")
        assert "server returned HTTP 502" in exc_info.value.message

    def test_resolve_project_id__missing_and_create_flag__creates_and_resolves(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        created = MagicMock()
        created.id = "proj-uuid-new"
        api.projects.retrieve_project.side_effect = [
            ApiError(status_code=404, body={"errors": ["Project not found"]}),
            created,
        ]

        result = _resolve(api, "new-project", create_if_missing=True)
        assert result == "proj-uuid-new"
        api.projects.create_project.assert_called_once_with(name="new-project")
        assert api.projects.retrieve_project.call_count == 2

    def test_resolve_project_id__known_missing__skips_initial_lookup(self):
        api = MagicMock()
        created = MagicMock()
        created.id = "proj-uuid-new"
        api.projects.retrieve_project.return_value = created

        result = _resolve(
            api,
            "new-project",
            create_if_missing=True,
            project_known_missing=True,
        )
        assert result == "proj-uuid-new"
        api.projects.create_project.assert_called_once_with(name="new-project")
        # Interactive preflight already saw the 404 — only the post-create
        # lookup should run.
        assert api.projects.retrieve_project.call_count == 1

    def test_resolve_project_id__missing_no_flag__does_not_create(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["Project not found"]}
        )

        with pytest.raises(click.ClickException):
            _resolve(api, "missing", create_if_missing=False)
        api.projects.create_project.assert_not_called()

    def test_resolve_project_id__non_404_with_flag__does_not_create(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=401, body={"message": "unauthorized"}
        )

        with pytest.raises(click.ClickException):
            _resolve(api, "x", create_if_missing=True)
        api.projects.create_project.assert_not_called()

    def test_resolve_project_id__create_fails__raises_clean_error(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["Project not found"]}
        )
        api.projects.create_project.side_effect = ApiError(
            status_code=403, body={"message": "User cannot create projects"}
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(api, "blocked", create_if_missing=True)
        message = exc_info.value.message
        assert "Could not create project 'blocked'" in message
        assert "User cannot create projects" in message

    def test_resolve_project_id__retrieval_error_with_config_context__appends_workspace_and_url(
        self,
    ):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["Project not found"]}
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(
                api,
                "missing-proj",
                workspace="team-a",
                base_url="https://opik.example.com/api/",
            )
        message = exc_info.value.message
        assert "team-a" in message
        assert "https://opik.example.com/api/" in message

    def test_resolve_project_id__retrieval_error_without_config_file__appends_opik_configure_hint(
        self,
    ):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=401, body={"message": "API key should be provided"}
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(
                api,
                "my-project",
                workspace="default",
                base_url="https://www.comet.com/opik/api/",
                config_file_exists=False,
            )
        message = exc_info.value.message
        assert "~/.opik.config" in message
        assert "Run: opik configure" in message

    def test_resolve_project_id__retrieval_error_with_config_file__omits_no_config_hint(
        self,
    ):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=500, body="Internal Server Error"
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(
                api,
                "p",
                workspace="default",
                base_url="https://www.comet.com/opik/api/",
                config_file_exists=True,
            )
        assert "~/.opik.config" not in exc_info.value.message

    def test_resolve_project_id__create_fails_with_config_context__includes_workspace_and_url(
        self,
    ):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body={"errors": ["Project not found"]}
        )
        api.projects.create_project.side_effect = ApiError(
            status_code=403, body={"message": "User cannot create projects"}
        )

        with pytest.raises(click.ClickException) as exc_info:
            _resolve(
                api,
                "blocked",
                create_if_missing=True,
                workspace="team-a",
                base_url="https://opik.example.com/api/",
                config_file_exists=False,
            )
        message = exc_info.value.message
        assert "Could not create project 'blocked'" in message
        assert "team-a" in message
        assert "https://opik.example.com/api/" in message
        assert "Run: opik configure" in message


class TestValidateRunnerName:
    def test_validate_runner_name__valid_name__passes(self):
        validate_runner_name("my-runner")

    def test_validate_runner_name__empty__raises(self):
        with pytest.raises(Exception, match="empty"):
            validate_runner_name("")

    def test_validate_runner_name__whitespace_only__raises(self):
        with pytest.raises(Exception, match="empty"):
            validate_runner_name("   ")

    def test_validate_runner_name__over_128_chars__raises(self):
        with pytest.raises(Exception, match="128 characters"):
            validate_runner_name("x" * 129)

    def test_validate_runner_name__over_255_utf8_bytes__raises(self):
        name = "\U0001f600" * 64
        with pytest.raises(Exception, match="255 UTF-8 bytes"):
            validate_runner_name(name)


class TestGenerateRunnerName:
    def test_generate_runner_name__explicit_name__returns_it(self):
        assert generate_runner_name("my-name") == "my-name"

    def test_generate_runner_name__none__generates_random_hex(self):
        name = generate_runner_name(None)
        assert "-" in name
        hex_part = name.rsplit("-", 1)[1]
        assert len(hex_part) == 6
        int(hex_part, 16)


class TestRunPairing:
    SESSION_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    RUNNER_ID = "11111111-2222-3333-4444-555555555555"
    PROJECT_ID = "66666666-7777-8888-9999-aaaaaaaaaaaa"

    def _make_api(self, project_id=None):
        if project_id is None:
            project_id = self.PROJECT_ID
        api = MagicMock()
        project = MagicMock()
        project.id = project_id
        api.projects.retrieve_project.return_value = project

        session_resp = MagicMock()
        session_resp.session_id = self.SESSION_ID
        session_resp.runner_id = self.RUNNER_ID
        api.pairing.create_pairing_session.return_value = session_resp

        runner = MagicMock()
        runner.status = "connected"
        api.runners.get_runner.return_value = runner

        return api

    @patch("opik.cli.local_runner.pairing.time.sleep")
    def test_run_pairing__happyflow__returns_result(self, mock_sleep):
        api = self._make_api()
        result = _pair(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.CONNECT,
            base_url="http://localhost:5173/api/",
            ttl_seconds=1,
        )

        assert isinstance(result, PairingResult)
        assert result.runner_id == self.RUNNER_ID
        assert result.project_name == "my-proj"
        assert result.project_id == self.PROJECT_ID
        assert len(result.bridge_key) == 32

    @patch("opik.cli.local_runner.pairing.time.sleep")
    def test_run_pairing__with_tui__calls_started_and_completed(self, mock_sleep):
        api = self._make_api()
        tui = MagicMock()

        _pair(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.CONNECT,
            base_url="http://localhost:5173/api/",
            tui=tui,
            ttl_seconds=1,
        )

        tui.pairing_started.assert_called_once()
        tui.pairing_completed.assert_called_once()

    @patch("opik.cli.local_runner.pairing.time.sleep")
    def test_run_pairing__404_during_poll__retries_until_connected(self, mock_sleep):
        api = self._make_api()
        err = NotFoundError(body=None)
        runner = MagicMock()
        runner.status = "connected"
        api.runners.get_runner.side_effect = [err, err, runner]

        result = _pair(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.ENDPOINT,
            base_url="http://localhost:5173/api/",
            ttl_seconds=1,
        )
        assert result.runner_id == self.RUNNER_ID
        assert api.runners.get_runner.call_count == 3

    @patch("opik.cli.local_runner.pairing.time.monotonic")
    @patch("opik.cli.local_runner.pairing.time.sleep")
    def test_run_pairing__timeout__calls_tui_pairing_failed(
        self, mock_sleep, mock_monotonic
    ):
        api = self._make_api()
        runner = MagicMock()
        runner.status = "pairing"
        api.runners.get_runner.return_value = runner

        # Strictly-increasing clock: each call returns previous + 1000s. This
        # guarantees the while-loop exits on the call immediately after
        # `deadline = monotonic() + ttl_seconds` is computed, regardless of
        # how many other calls (from logging, coverage, plugins, etc.) land
        # on the patched monotonic before or after the test's own calls.
        clock = {"t": 0.0}

        def advancing_monotonic():
            clock["t"] += 1000.0
            return clock["t"]

        mock_monotonic.side_effect = advancing_monotonic

        tui = MagicMock()
        with pytest.raises(click.ClickException) as exc_info:
            _pair(
                api=api,
                project_name="my-proj",
                runner_name="test-runner",
                runner_type=RunnerType.CONNECT,
                base_url="http://localhost:5173/api/",
                tui=tui,
                ttl_seconds=300,
            )
        assert "timed out" in exc_info.value.message

        tui.pairing_failed.assert_called_once_with("timed out")

    @patch("opik.cli.local_runner.pairing.time.sleep")
    def test_run_pairing__keyboard_interrupt__calls_tui_pairing_failed(
        self, mock_sleep
    ):
        api = self._make_api()
        api.runners.get_runner.side_effect = KeyboardInterrupt

        tui = MagicMock()
        with pytest.raises(KeyboardInterrupt):
            _pair(
                api=api,
                project_name="my-proj",
                runner_name="test-runner",
                runner_type=RunnerType.CONNECT,
                base_url="http://localhost:5173/api/",
                tui=tui,
                ttl_seconds=1,
            )

        tui.pairing_failed.assert_called_once_with("interrupted")

    @patch("opik.cli.local_runner.pairing.time.sleep")
    def test_run_pairing__non_404_api_error__propagates(self, mock_sleep):
        from opik.rest_api.core.api_error import ApiError

        api = self._make_api()
        api.runners.get_runner.side_effect = ApiError(
            status_code=429, body="rate limited"
        )

        with pytest.raises(ApiError) as exc_info:
            _pair(
                api=api,
                project_name="my-proj",
                runner_name="test-runner",
                runner_type=RunnerType.CONNECT,
                base_url="http://localhost:5173/api/",
                ttl_seconds=1,
            )
        assert exc_info.value.status_code == 429


class TestRunHeadless:
    SESSION_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    RUNNER_ID = "11111111-2222-3333-4444-555555555555"
    PROJECT_ID = "66666666-7777-8888-9999-aaaaaaaaaaaa"

    def _make_api(self):
        api = MagicMock()
        project = MagicMock()
        project.id = self.PROJECT_ID
        api.projects.retrieve_project.return_value = project

        session_resp = MagicMock()
        session_resp.session_id = self.SESSION_ID
        session_resp.runner_id = self.RUNNER_ID
        api.pairing.create_pairing_session.return_value = session_resp
        api.pairing.activate_pairing_session.return_value = None

        return api

    def test_run_headless__creates_and_self_activates(self):
        api = self._make_api()
        result = _headless(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.ENDPOINT,
        )

        assert result.runner_id == self.RUNNER_ID
        assert result.project_id == self.PROJECT_ID
        assert result.bridge_key == b""
        api.pairing.create_pairing_session.assert_called_once()
        api.pairing.activate_pairing_session.assert_called_once()

    def test_run_headless__no_polling(self):
        api = self._make_api()
        _headless(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.ENDPOINT,
        )
        # No get_runner polling — headless activates immediately
        api.runners.get_runner.assert_not_called()

    def test_run_headless__connect_type__raises(self):
        api = self._make_api()
        with pytest.raises(click.ClickException, match="not supported"):
            _headless(
                api=api,
                project_name="my-proj",
                runner_name="test-runner",
                runner_type=RunnerType.CONNECT,
            )
