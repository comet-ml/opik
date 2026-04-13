import base64
import uuid
from unittest.mock import MagicMock, patch

import click
import pytest

from opik.cli.pairing import (
    PairingResult,
    RunnerType,
    build_pairing_link,
    generate_runner_name,
    hkdf_sha256,
    resolve_project_id,
    run_pairing,
    validate_runner_name,
)
from opik.rest_api.errors.not_found_error import NotFoundError


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

        link = build_pairing_link(
            base_url="https://www.comet.com/opik/api/",
            session_id=session_id,
            activation_key=activation_key,
            project_id=project_id,
            runner_name=runner_name,
        )

        assert link.startswith("https://www.comet.com/opik/pair/v1#")

        fragment = link.split("#", 1)[1]
        padding_needed = (4 - len(fragment) % 4) % 4
        payload = base64.urlsafe_b64decode(fragment + "=" * padding_needed)

        assert payload[0:16] == uuid.UUID(session_id).bytes
        assert payload[16:48] == activation_key
        assert payload[48:64] == uuid.UUID(project_id).bytes
        assert payload[64] == len(runner_name.encode("utf-8"))
        assert payload[65 : 65 + payload[64]] == runner_name.encode("utf-8")

    def test_build_pairing_link__cloud_url__no_double_opik_path(self):
        link = build_pairing_link(
            base_url="https://www.comet.com/opik/api/",
            session_id="550e8400-e29b-41d4-a716-446655440000",
            activation_key=b"\x00" * 32,
            project_id="660e8400-e29b-41d4-a716-446655440000",
            runner_name="r",
        )
        assert "/opik/opik/" not in link

    def test_build_pairing_link__localhost_url__correct_prefix(self):
        link = build_pairing_link(
            base_url="http://localhost:5173/api/",
            session_id="550e8400-e29b-41d4-a716-446655440000",
            activation_key=b"\x00" * 32,
            project_id="660e8400-e29b-41d4-a716-446655440000",
            runner_name="r",
        )
        assert link.startswith("http://localhost:5173/opik/pair/v1#")


class TestResolveProjectId:
    def test_resolve_project_id__project_exists__returns_id(self):
        api = MagicMock()
        project = MagicMock()
        project.id = "proj-uuid-123"
        api.projects.retrieve_project.return_value = project

        result = resolve_project_id(api, "my-project")
        assert result == "proj-uuid-123"
        api.projects.retrieve_project.assert_called_once_with(name="my-project")

    def test_resolve_project_id__project_missing__raises(self):
        from opik.rest_api.core.api_error import ApiError

        api = MagicMock()
        api.projects.retrieve_project.side_effect = ApiError(
            status_code=404, body="not found"
        )

        with pytest.raises(Exception, match="not found"):
            resolve_project_id(api, "nonexistent")


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

    @patch("opik.cli.pairing.time.sleep")
    def test_run_pairing__happyflow__returns_result(self, mock_sleep):
        api = self._make_api()
        result = run_pairing(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.CONNECT,
            base_url="http://localhost:5173/api/",
        )

        assert isinstance(result, PairingResult)
        assert result.runner_id == self.RUNNER_ID
        assert result.project_name == "my-proj"
        assert result.project_id == self.PROJECT_ID
        assert len(result.bridge_key) == 32

    @patch("opik.cli.pairing.time.sleep")
    def test_run_pairing__with_tui__calls_started_and_completed(self, mock_sleep):
        api = self._make_api()
        tui = MagicMock()

        run_pairing(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.CONNECT,
            base_url="http://localhost:5173/api/",
            tui=tui,
        )

        tui.pairing_started.assert_called_once()
        tui.pairing_completed.assert_called_once()

    @patch("opik.cli.pairing.time.sleep")
    def test_run_pairing__404_during_poll__retries_until_connected(self, mock_sleep):
        api = self._make_api()
        err = NotFoundError(body=None)
        runner = MagicMock()
        runner.status = "connected"
        api.runners.get_runner.side_effect = [err, err, runner]

        result = run_pairing(
            api=api,
            project_name="my-proj",
            runner_name="test-runner",
            runner_type=RunnerType.ENDPOINT,
            base_url="http://localhost:5173/api/",
            ttl_seconds=300,
        )
        assert result.runner_id == self.RUNNER_ID
        assert api.runners.get_runner.call_count == 3

    @patch("opik.cli.pairing.time.monotonic")
    @patch("opik.cli.pairing.time.sleep")
    def test_run_pairing__timeout__calls_tui_pairing_failed(
        self, mock_sleep, mock_monotonic
    ):
        api = self._make_api()
        runner = MagicMock()
        runner.status = "pairing"
        api.runners.get_runner.return_value = runner

        # First call sets deadline (0+300=300), second call exceeds it
        call_count = 0

        def advancing_monotonic():
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return 0.0
            return 999.0

        mock_monotonic.side_effect = advancing_monotonic

        tui = MagicMock()
        with pytest.raises(click.ClickException) as exc_info:
            run_pairing(
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

    @patch("opik.cli.pairing.time.sleep")
    def test_run_pairing__keyboard_interrupt__calls_tui_pairing_failed(
        self, mock_sleep
    ):
        api = self._make_api()
        api.runners.get_runner.side_effect = KeyboardInterrupt

        tui = MagicMock()
        with pytest.raises(KeyboardInterrupt):
            run_pairing(
                api=api,
                project_name="my-proj",
                runner_name="test-runner",
                runner_type=RunnerType.CONNECT,
                base_url="http://localhost:5173/api/",
                tui=tui,
            )

        tui.pairing_failed.assert_called_once_with("interrupted")

    @patch("opik.cli.pairing.time.sleep")
    def test_run_pairing__non_404_api_error__propagates(self, mock_sleep):
        from opik.rest_api.core.api_error import ApiError

        api = self._make_api()
        api.runners.get_runner.side_effect = ApiError(
            status_code=429, body="rate limited"
        )

        with pytest.raises(ApiError) as exc_info:
            run_pairing(
                api=api,
                project_name="my-proj",
                runner_name="test-runner",
                runner_type=RunnerType.CONNECT,
                base_url="http://localhost:5173/api/",
            )
        assert exc_info.value.status_code == 429
