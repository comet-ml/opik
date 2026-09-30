import subprocess

import httpx
import pytest

from opik.configurator.mcp import handoff


class TestFirstTracedProject:
    """Which project the closing prompt points the agent at."""

    def test_no_projects__is_no_project(self):
        assert handoff._first_traced_project([]) is None

    def test_a_project_without_traces__does_not_count(self):
        """`last_updated_trace_at` stays null until the first trace arrives."""
        projects = [{"name": "my-app", "last_updated_trace_at": None}]

        assert handoff._first_traced_project(projects) is None

    def test_demo_projects__do_not_count(self):
        """Every workspace is born with them, and they are nobody's own app.

        Diagnosing data the user did not produce teaches them nothing about
        their code, so a workspace holding only demos is still "nothing to look
        at yet".
        """
        projects = [
            {"name": name, "last_updated_trace_at": "2026-09-30T10:00:00Z"}
            for name in handoff.DEMO_PROJECT_NAMES
        ]

        assert handoff._first_traced_project(projects) is None

    def test_the_users_own_traced_project__is_chosen(self):
        projects = [
            {
                "name": "Demo evaluation",
                "last_updated_trace_at": "2026-09-30T10:00:00Z",
            },
            {"name": "my-app", "last_updated_trace_at": "2026-09-29T10:00:00Z"},
        ]

        assert handoff._first_traced_project(projects) == "my-app"

    def test_several__takes_the_most_recently_traced(self):
        """The one they are working on now, not the one they abandoned."""
        projects = [
            {"name": "old", "last_updated_trace_at": "2026-01-01T10:00:00Z"},
            {"name": "current", "last_updated_trace_at": "2026-09-30T10:00:00Z"},
        ]

        assert handoff._first_traced_project(projects) == "current"

    def test_junk_in_the_listing__is_survived(self):
        """A project payload is the deployment's to shape; this only reads it."""
        projects = ["not a dict", {}, {"last_updated_trace_at": "2026-09-30T10:00:00Z"}]

        assert handoff._first_traced_project(projects) is None


class TestClosingPrompt:
    def test_no_traces__points_at_the_instrument_skill(self):
        prompt = handoff.closing_prompt(None)

        assert "/opik-instrument" in prompt

    def test_traces__points_at_the_diagnose_skill_and_names_the_project(self):
        prompt = handoff.closing_prompt("my-app")

        assert "/opik-diagnose" in prompt
        assert "my-app" in prompt

    def test_traces__asks_what_is_there__not_for_a_fix(self):
        """The first thing an agent does with a new connection should be to look.

        Opening on "fix this" commits the user to a change before they have seen
        their own data, and picks the target for them.
        """
        prompt = handoff.closing_prompt("my-app")

        assert "overview" in prompt
        assert "fix" not in prompt


class TestTracedProject:
    """The lookup itself, which must never be what breaks the command."""

    @staticmethod
    def _respond(monkeypatch, response):
        class FakeClient:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def get(self, **kwargs):
                if isinstance(response, Exception):
                    raise response
                return response

        monkeypatch.setattr(handoff.httpx_client, "get", lambda **kwargs: FakeClient())

    @pytest.mark.parametrize(
        "failure",
        [
            httpx.ConnectError("no route"),
            httpx.ReadTimeout("too slow"),
            OSError("socket closed"),
        ],
    )
    def test_an_unreachable_deployment__answers_none(self, monkeypatch, failure):
        self._respond(monkeypatch, failure)

        assert handoff.traced_project(None, None, "https://opik/api/", True) is None

    def test_a_rejected_request__answers_none(self, monkeypatch):
        self._respond(monkeypatch, httpx.Response(403, json={}))

        assert handoff.traced_project(None, None, "https://opik/api/", True) is None

    def test_a_listing__names_the_traced_project(self, monkeypatch):
        self._respond(
            monkeypatch,
            httpx.Response(
                200,
                json={
                    "content": [
                        {
                            "name": "my-app",
                            "last_updated_trace_at": "2026-09-30T10:00:00Z",
                        }
                    ]
                },
            ),
        )

        assert handoff.traced_project(None, None, "https://opik/api/", True) == "my-app"

    def test_a_body_that_is_not_json__answers_none(self, monkeypatch):
        self._respond(monkeypatch, httpx.Response(200, text="<html>nope</html>"))

        assert handoff.traced_project(None, None, "https://opik/api/", True) is None


class TestLaunching:
    def test_a_gui_client__cannot_be_launched(self):
        """Nothing to hand a prompt to; the caller shows it instead."""
        assert handoff.can_launch("cursor") is False
        assert handoff.can_launch("vscode") is False

    def test_a_terminal_agent__can_be_launched_when_it_is_installed(self, monkeypatch):
        monkeypatch.setattr(handoff.shutil, "which", lambda name: "/usr/bin/claude")

        assert handoff.can_launch("claude-code") is True

    def test_a_terminal_agent_that_is_not_installed__cannot(self, monkeypatch):
        monkeypatch.setattr(handoff.shutil, "which", lambda name: None)

        assert handoff.can_launch("claude-code") is False

    def test_launching__execs_the_agent_with_the_prompt(self, monkeypatch):
        """The user agreed to this exact question a line ago, so it is sent."""
        recorded = {}
        monkeypatch.setattr(handoff.shutil, "which", lambda name: "/usr/bin/claude")
        monkeypatch.setattr(
            handoff.os,
            "execvp",
            lambda executable, argv: recorded.update(executable=executable, argv=argv),
        )

        handoff.launch("claude-code", "look at my traces")

        assert recorded["executable"] == "/usr/bin/claude"
        assert recorded["argv"] == ["claude", "look at my traces"]

    def test_launching_on_windows__runs_the_agent_and_exits_with_its_status(
        self, monkeypatch
    ):
        """Windows has no exec: `os.execvp` there returns the console to the shell
        while the agent still reads from it, and passes the prompt unquoted, so it
        arrives split into words. The agent runs as a child instead."""
        recorded = {}
        monkeypatch.setattr(handoff.sys, "platform", "win32")
        monkeypatch.setattr(handoff.shutil, "which", lambda name: "C:\\bin\\claude.CMD")
        monkeypatch.setattr(
            handoff.os,
            "execvp",
            lambda *args: pytest.fail("Windows must not exec"),
        )
        monkeypatch.setattr(handoff.signal, "signal", lambda *args: None)

        def fake_run(argv):
            recorded["argv"] = argv
            return subprocess.CompletedProcess(argv, 3)

        monkeypatch.setattr(handoff.subprocess, "run", fake_run)

        with pytest.raises(SystemExit) as exited:
            handoff.launch("claude-code", "look at my traces")

        assert recorded["argv"] == ["C:\\bin\\claude.CMD", "look at my traces"]
        assert exited.value.code == 3

    def test_launching_something_that_cannot_be__does_nothing(self, monkeypatch):
        monkeypatch.setattr(
            handoff.os,
            "execvp",
            lambda *args: pytest.fail("a GUI client must not be exec'd"),
        )

        handoff.launch("cursor", "look at my traces")
